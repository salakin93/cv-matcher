# 011 - Candidate Identity and Deduplication Foundation

## Objetivo

Entregar la fundación privada de perfiles de candidato y deduplicación de CVs
para un job. El backend identifica de manera conservadora una persona desde el
CV y, cuando sea necesario, desde el remitente del mensaje, cifra todo dato
personal y vincula varios documentos a un único perfil compartido. No expone un
directorio, no cambia resultados históricos y no toma decisiones humanas.

## Referencias

- `docs/PRD.md`, secciones 5, 7 y 8.
- `docs/PRODUCT_BACKLOG.md`, Epics 4 y 5.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 006, 008 y 010.

## Alcance

### Incluido

- Módulo `candidate` con perfiles privados compartidos y vínculos documento-perfil.
- Extracción conservadora de email/nombre desde texto de CV ya cifrado.
- Obtención mínima y cifrada de sender email sólo como fallback de identidad.
- Deduplicación por email de CV, luego sender email y finalmente nombre
  normalizado de menor confianza, tal como define el PRD.
- Selección determinista del CV más reciente por persona para cada job futuro.
- PII cifrada AES-GCM, índices HMAC de igualdad y trazabilidad de fuente/
  confianza sin mostrar datos personales.
- Auditoría, métricas sin PII, pruebas de colisión/concurrencia y puertos
  internos para composición de reportes posteriores.

### Excluido

- API/UI de perfil o directorio histórico, búsqueda por candidato, disponibilidad,
  correcciones humanas, papelera, purga y eliminación por privacidad.
- Ranking, score, `report_version`, resultados públicos, estados humanos,
  exportación, descarga de CV y notificaciones.
- Reanalizar Claude, alterar texto/evaluaciones/scores o usar identidad para
  contratación, descarte, filtrado automático o cambio de vacante.
- Guardar correo/nombre/remitente en claro, logs, auditoría, métricas o respuestas.

## Decisiones arquitectónicas

1. `candidate` es dueño de perfiles e identidad. `document`, `analysis` y
   `reporting` usan puertos públicos y no leen/correlacionan PII directamente.
2. El email extraído del CV tiene prioridad; si falta, se permite sender email
   de Graph cifrado; si ambos faltan, nombre normalizado tiene menor confianza.
   Nunca se mezcla identidad de fuentes incompatibles sin una clave de igualdad.
3. Correo y nombre se cifran AES-GCM. La igualdad se busca con HMAC-SHA-256
   normalizado y una clave distinta `CANDIDATE_IDENTITY_HMAC_KEY`; no usar hash
   simple por ser susceptible a diccionario.
4. Nombre normalizado sólo deduplica cuando ambos documentos no tienen email y
   registra confianza `LOW`. No fusiona perfiles existentes de email distinto.
5. Obtener sender email es la única ampliación permitida al descubrimiento: se
   solicita sólo para un mensaje con documento `AVAILABLE`, se cifra/HMAC de
   inmediato y jamás se persiste o expone en claro. No se lee body ni asunto.

## Modelo y persistencia

Crear exclusivamente `V12__candidate_identity_deduplication.sql`; no modificar
V1–V11.

### `candidate_profile`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `email_ciphertext` | AES-GCM nullable. |
| `email_hmac` | HMAC nullable, único cuando existe. |
| `full_name_ciphertext` | AES-GCM nullable. |
| `normalized_name_hmac` | HMAC nullable, no único globalmente. |
| `identity_source` | `CV_EMAIL`, `SENDER_EMAIL` o `NORMALIZED_NAME`. |
| `identity_confidence` | `HIGH`, `MEDIUM` o `LOW`. |
| `created_at`, `updated_at` | `timestamptz` UTC. |
| `version` | `bigint` no nulo para cambios futuros. |

Una fila debe tener email HMAC o nombre HMAC. El HMAC se calcula sobre email
trim/lowercase Unicode-normalizado o nombre canónico Unicode-normalizado. No
guardar email/nombre normalizados en claro.

### `candidate_document_profile`

Vínculo UUID entre `candidate_document` y `candidate_profile`; contiene
`identity_source`, `identity_confidence`, `matched_at` y `job_id` de origen.
Un documento tiene exactamente un perfil; índices por perfil/job y documento.

Agregar a `matching_job_discovered_message` sender email ciphertext/HMAC nullable
con acceso restringido a `candidate`; llenar sólo bajo la regla de fallback. No
exponer esta columna a `job` API ni a otros módulos.

### `job_candidate_selection`

Snapshot técnico por job y perfil: `candidate_profile_id`, documento seleccionado,
`received_at`, fuente/confianza y timestamp. Constraint único `(matching_job_id,
candidate_profile_id)`. No guarda PII, score ni ranking. Permite que reportes
posteriores usen un solo CV, el más reciente, por persona.

## Reglas de negocio

1. Worker procesa sólo documentos con texto `EXTRACTED` y score/revisión de
   análisis disponible. La identidad no modifica texto, análisis ni score.
2. Extrae email de CV con parser conservador y lo normaliza. Si hay más de un
   email ambiguo o inválido, no elige uno arbitrariamente; intenta sender fallback
   y, si sigue sin email, sólo usa nombre de alta confianza del encabezado del CV.
3. Si email CV existe, busca/crea perfil por `email_hmac` y asigna `HIGH`. Sin él,
   sender email válido usa `MEDIUM`; sin ambos, nombre normalizado usa `LOW`.
4. Crear o localizar perfil y vínculo ocurre en transacción con constraint única.
   Dos workers concurrentes para el mismo email dejan un solo perfil y vínculos
   correctos; colisión de constraint se relee, no se duplica.
5. Para cada job, seleccionar documento con `received_at` más reciente por perfil;
   empate se resuelve por UUID ascendente. Documentos no seleccionados permanecen
   vinculados y auditables, pero no serán entradas de ranking posterior.
6. Nunca deduplicar automáticamente perfiles con emails distintos sólo porque
   coinciden nombres. Correcciones/fusiones humanas se definen en futura spec.
7. Si identidad no puede determinarse de forma segura, crear perfil técnico por
   documento con fuente `NORMALIZED_NAME` sólo si existe nombre válido; en caso
   contrario registrar warning `CANDIDATE_IDENTITY_UNRESOLVED` y excluirlo de la
   selección futura sin borrar documento ni score.
8. Replays, lease vencido y reintentos son idempotentes por documento/perfil/job.
   Cancelación prevalece antes de crear enlaces o selección.

## Errores y seguridad

No se agregan endpoints de perfiles. `JobDetail` puede exponer sólo conteos de
perfiles seleccionados/no resueltos y estado seguro.

| Situación | Resultado seguro |
| --- | --- |
| Email/nombre ambiguo o ausente | warning `CANDIDATE_IDENTITY_UNRESOLVED` |
| Sender Graph no disponible | warning `CANDIDATE_SENDER_UNAVAILABLE` |
| HMAC/cifrado inválido | `FAILED` / `CANDIDATE_IDENTITY_CRYPTO_FAILURE` |
| Concurrencia de perfil | recuperación interna idempotente, sin error público |

- No loguear correo, nombre, sender, texto, HMAC, ciphertext, UUID, fuente de
  documento ni razón detallada de extracción.
- Las claves de identidad y cifrado son sólo server-side. Un perfil no tiene URL
  pública ni se devuelve desde APIs actuales.
- Cualquier futuro acceso a perfil exige autenticación y una spec específica de
  minimización, corrección, retención y privacidad.

## Auditoría y observabilidad

Eventos técnicos viven en `matching_job_event`: `CANDIDATE_PROFILE_LINKED`,
`CANDIDATE_PROFILE_UNRESOLVED` y `JOB_CANDIDATE_SELECTED`. Agregar auditoría
`CANDIDATE_IDENTITIES_RESOLVED` o `CANDIDATE_IDENTITIES_FAILED` una vez por job,
sin PII ni hash.

Métricas sin PII:

- `candidates.identity_resolution` con `outcome` (`cv_email`, `sender_email`,
  `normalized_name`, `unresolved`);
- `candidates.deduplication` con `outcome` (`new_profile`, `existing_profile`,
  `selected`, `not_selected`);
- `candidates.identity_duration` sin etiquetas identificables.

## OpenAPI y configuración

- `JobDetail` documenta sólo conteos agregados de identidad/selección y warnings
  seguros. No publicar perfil, correo, nombre, fuente, HMAC, sender o documento.
- Propiedades server-side: claves/versiones AES-GCM y HMAC, política de
  normalización Unicode y extractor de identidad. Validar claves al arranque.
- `test` usa CVs y sender sintéticos, claves ficticias y dobles Graph; no PII
  real, no llamadas externas ni lectura de correo fuera del fallback autorizado.

## Estrategia de pruebas

### Unitarias

- Normalización email/nombre, HMAC con key distinta, cifrado y ausencia de claro.
- Prioridad CV email > sender email > nombre; ambigüedad y no fusión por nombre.
- Selección más reciente/tie-break, idempotencia y cancelación.

### Integración Spring/PostgreSQL Testcontainers

- CVs con mismo email crean un perfil; emails distintos nunca se fusionan por
  nombre; sender sólo se usa si CV no ofrece email.
- Dos workers concurrentes crean un perfil único y enlaces/selección coherentes.
- Sender, email y nombre no aparecen en API, logs, auditoría, métricas ni columnas
  plaintext; una consulta directa verifica ciphertext/HMAC.
- Documentos sin identidad no rompen otros perfiles ni se convierten en candidato
  visible; V12 desde V1–V11, OpenAPI, `./gradlew test`, `git diff --check`.

## Criterios de aceptación

1. CVs se vinculan a perfiles compartidos usando email CV, sender y nombre en
   ese orden de prioridad, con fuente y confianza explícitas.
2. Correo/nombre/sender sólo se persisten cifrados y se comparan por HMAC con
   clave secreta; nunca aparecen en claro, API, logs, auditoría o métricas.
3. Dos CVs con mismo email se deduplican; emails distintos no se fusionan sólo
   por nombre y nombre sin email queda marcado de baja confianza.
4. Dos workers concurrentes dejan un único perfil para identidad igual y enlaces
   idempotentes, sin duplicar selección.
5. Cada job conserva sólo el CV más reciente por perfil, con tie-break UUID;
   otros documentos siguen vinculados para trazabilidad interna.
6. Identidad ausente/ambigua genera warning seguro sin borrar documento, score o
   aplicar contratación, descarte o estado humano.
7. Sender se consulta sólo como fallback de documento disponible y jamás se lee
   body/asunto ni se expone el valor.
8. API de job revela sólo conteos/warnings; no existe directorio, perfil público,
   búsqueda, descarga, corrección ni privacidad en esta spec.
9. Auditoría, OpenAPI, logs y métricas no filtran PII, HMAC, ciphertext o UUID
   como etiqueta de métrica.
10. V12 y Testcontainers cubren prioridad, cifrado, HMAC, concurrencia,
    deduplicación y selección sin ranking, reporte, UI o exportaciones.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Texto/documentos y scores de specs 008–010. | Consumir puertos públicos. |
| Riesgo | Colisión o adivinación de identidad. | HMAC con clave secreta y cifrado. |
| Riesgo | Falso positivo por nombre. | Sólo fallback LOW y no fusionar emails distintos. |
| Riesgo | Sender filtra PII. | Consulta mínima, cifrado inmediato y pruebas de ausencia. |
| Dependencia futura | Falta reporte/ranking y gestión de perfil. | Exponer sólo selección interna. |

## Definition of Ready

`READY_FOR_DEV`
