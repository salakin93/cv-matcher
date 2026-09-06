# 016 - Candidate Profile Availability and Corrections

## Objetivo

Entregar el mantenimiento compartido y auditable de perfiles de candidato:
`RECRUITER` y `ADMIN` pueden consultar datos mínimos, cambiar disponibilidad y
corregir nombre, correo o ubicación extraídos, preservando valor/origen previo.
Las correcciones sólo afectan perfil y búsquedas futuras; reportes históricos
permanecen inmutables.

## Referencias

- `docs/PRD.md`, secciones 5, 7 y 8.
- `docs/PRODUCT_BACKLOG.md`, Epic 5, Feature 5.2.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 011, 012 y 013.

## Alcance

### Incluido

- Listado paginado y detalle mínimo de perfiles compartidos.
- Disponibilidad `DISPONIBLE`, `NO_DISPONIBLE`, `DESCONOCIDO`.
- Corrección explícita de nombre, email y ubicación con `expectedVersion`.
- Historial append-only de valor original, corrección, actor, fecha y origen.
- PII cifrada AES-GCM, igualdad por HMAC donde corresponda y acceso autenticado.
- Auditoría, métricas sin PII, OpenAPI y pruebas Testcontainers.

### Excluido

- Búsqueda histórica contra una vacante, filtros de habilidades, sugerencia 70,
  ranking, reportes, recalcular scores o modificar versiones históricas.
- Papelera, purga, eliminación por privacidad, fusión manual de perfiles,
  descarga de CV, exportación, notificaciones y UI.
- Crear perfiles manualmente, editar documentos/texto CV, cambiar identidad de
  deduplicación automáticamente o modificar disponibilidad por score/Claude.

## Decisiones arquitectónicas

1. `candidate` es dueño de perfil actual y revisiones. Los snapshots de
   `report_version` nunca se actualizan al corregir perfil.
2. Datos personales actuales y revisiones se cifran AES-GCM; email usa HMAC
   normalizado para detectar colisión. No exponer ciphertext, HMAC, origen Graph
   ni valores de sender.
3. Corrección humana requiere versión actual y se registra aunque el valor nuevo
   coincida sólo si cambia semánticamente tras normalización. Solicitud idéntica
   es `204` sin evento/auditoría adicional.
4. Un email corregido no puede colisionar con perfil existente. No fusionar ni
   reasignar documentos automáticamente; conflicto requiere flujo futuro.
5. Datos de perfil son compartidos para reclutamiento. Sólo `ADMIN` conserva
   funciones administrativas; ambos roles pueden consultar/corregir perfiles.

## Modelo y persistencia

Crear exclusivamente `V17__candidate_profile_corrections.sql`; no modificar
V1–V16.

### Extensión de `candidate_profile`

Agregar `location_ciphertext`, `location_normalized_hmac`, `availability`,
`availability_updated_at`, `availability_updated_by_user_id`, `source` y
`version`. `availability` inicia `DESCONOCIDO`. Nombre/email actuales continúan
cifrados; ubicación es opcional y no se infiere si no existe evidencia.

### `candidate_profile_revision`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `candidate_profile_id` | FK no nula. |
| `field_name` | `FULL_NAME`, `EMAIL`, `LOCATION`, `AVAILABILITY`. |
| `previous_ciphertext` | Valor previo cifrado nullable. |
| `new_ciphertext` | Valor nuevo cifrado nullable. |
| `previous_hmac`, `new_hmac` | HMAC nullable para igualdad, no público. |
| `source` | `EXTRACTED` o `HUMAN_CORRECTION`. |
| `changed_by_user_id` | UUID nullable para valor extraído, no nulo para corrección. |
| `created_at`, `correlation_id` | UTC y UUID. |

Tabla append-only. Nunca editar/borrar revisiones dentro de esta spec. Para
disponibilidad se cifra representación sólo si es necesario para consistencia;
el enum actual puede permanecer en claro por no ser PII sensible.

## Contrato API

JWT válido, cuenta activa, sesión vigente y rol `RECRUITER` o `ADMIN` son
obligatorios.

| Método y ruta | Solicitud/respuesta |
| --- | --- |
| `GET /api/v1/candidates` | `page?`, `size?`, `availability?`; `200 CandidateProfilePage`. |
| `GET /api/v1/candidates/{candidateId}` | `200 CandidateProfileDetail`. |
| `PATCH /api/v1/candidates/{candidateId}` | `CandidateProfileCorrectionRequest`; `204`. |
| `PATCH /api/v1/candidates/{candidateId}/availability` | `{ availability, expectedVersion }`; `204`. |

Corrección permite sólo `fullName?`, `email?`, `location?`, `expectedVersion`.
Al menos un campo debe cambiar. Campos desconocidos, blancos, límites inválidos,
email inválido o versión negativa devuelven `422 VALIDATION_ERROR`.

Lista/detalle muestran id, nombre, email, ubicación opcional, disponibilidad,
origen, versión y `updatedAt`. No muestran revisiones, actor, HMAC, sender,
documentos, score, reportes, estado humano ni PII adicional.

## Reglas de negocio

1. Listado ordena establemente por `updatedAt desc`, `id asc`; `availability` es
   filtro exacto y paginación usa page 0/size 1–100.
2. Corrección normaliza Unicode y trim. Nombre 1–200, email 1–320 y ubicación
   1–200 tras normalizar; email se baja a minúsculas antes de HMAC.
3. Fila de perfil se bloquea, se compara `expectedVersion` y se actualizan los
   campos juntos. Si cambia email, comprobar constraint HMAC única antes de
   persistir. Colisión devuelve `409 CANDIDATE_EMAIL_CONFLICT` sin cambios.
4. Cada campo efectivo agrega una revisión con valor anterior/nuevo y fuente
   `HUMAN_CORRECTION`; disponibilidad agrega también revisión. Una solicitud que
   repite todo valor actual es idempotente y no aumenta versión.
5. Dos correcciones concurrentes con misma versión producen un éxito y un
   `409 VERSION_CONFLICT`; ningún campo se mezcla entre solicitudes.
6. Corrección/disponibilidad no actualiza `report_candidate`, `report_version`,
   `document_score_revision`, `requirement_assessment` ni documento. Versiones
   futuras podrán tomar perfil actual bajo una spec de búsqueda/reporting.
7. Perfil inexistente devuelve `404 CANDIDATE_NOT_FOUND`. No hay eliminación,
   creación manual ni endpoint de revisiones en este incremento.

## Errores y seguridad

| Situación | HTTP / código |
| --- | --- |
| JWT inválido/revocado/cuenta no activa | `401 UNAUTHENTICATED` |
| Rol no autorizado | `403 FORBIDDEN` |
| Perfil inexistente | `404 CANDIDATE_NOT_FOUND` |
| Email ya asignado | `409 CANDIDATE_EMAIL_CONFLICT` |
| Versión desactualizada | `409 VERSION_CONFLICT` |
| Payload/filtro/paginación inválidos | `422 VALIDATION_ERROR` |

- Respuestas y logs nunca contienen HMAC, ciphertext, sender, valores previos,
  documentos, score, tokens, rutas o SQL. Error JSON usa correlation ID seguro.
- PII de lista/detalle es el mínimo necesario para reclutamiento y requiere
  autenticación persistida; métricas no llevan candidato, correo o UUID como tag.

## Auditoría y observabilidad

Mutaciones efectivas agregan `CANDIDATE_PROFILE_CORRECTED` o
`CANDIDATE_AVAILABILITY_CHANGED` a `audit_event`, con actor, objetivo
`CANDIDATE_PROFILE`, timestamp y correlation ID, sin valores personales.

Métricas sin PII:

- `candidates.profile_mutations` con `action` (`correct`, `availability`) y
  `outcome` (`success`, `conflict`, `validation_error`);
- `candidates.profile_list_requests` con `availability_filter` del enum cerrado.

## OpenAPI y configuración

- Documentar list/detail/PATCH, bearer, paginación, `200`, `204`, `401`, `403`,
  `404`, `409`, `422` y ejemplos españoles sintéticos.
- Propiedades server-side: claves/versiones de cifrado/HMAC y límites de texto.
  No exponer revisiones ni permitir configurar normalización por API.
- `test` usa PII sintética y claves ficticias; no Graph, Claude, documentos ni
  secretos reales.

## Estrategia de pruebas

### Unitarias

- Normalización Unicode/email, cifrado/HMAC, validación y colisión.
- Idempotencia, compare-and-set, revisiones append-only y aislamiento histórico.

### Integración Spring/PostgreSQL Testcontainers

- Ambos roles activos consultan/corrigen/disponibilidad; `401`/`403` seguros.
- Página/filtro/PII mínima, 404, 422, conflicto email y dos PATCH concurrentes.
- Revisión guarda valores cifrados/origen/actor sin PII en auditoría/logs/métricas.
- Cambios no alteran reportes/scores/assessments previos; V17 desde V1–V16,
  OpenAPI, `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. Reclutador/admin autenticados consultan perfiles mínimos compartidos, paginados
   y filtrables por disponibilidad; ausencia de bearer es `401` y rol no válido
   es `403` seguro.
2. Nombre, email, ubicación y disponibilidad se corrigen explícitamente con
   versión; los datos personales se cifran y comparan por HMAC cuando aplica.
3. Cada cambio efectivo conserva revisión append-only con original/nuevo, actor,
   fecha y origen sin exponer esos valores en auditoría transversal.
4. Cambio idéntico es idempotente; cambio concurrente deja un éxito y `409`.
5. Email no puede colisionar ni fusionar perfiles automáticamente.
6. Disponibilidad admite sólo tres valores y no se deriva de score/Claude.
7. Correcciones no cambian reportes históricos, ranking, assessments, documentos
   ni estado humano.
8. API/logs/métricas/OpenAPI no filtran sender, revisiones, HMAC, ciphertext,
   documentos, score o UUID como etiqueta.
9. No hay creación manual, eliminación, privacidad, búsqueda histórica, descarga,
   exportación, UI ni notificación como efecto colateral.
10. V17 y Testcontainers cubren autorización, cifrado, revisiones, conflicto,
    idempotencia, concurrencia e inmutabilidad histórica.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 011 crea perfiles cifrados. | Extender sólo módulo `candidate`. |
| Riesgo | Corrección altera reporte histórico. | Snapshots separados y pruebas. |
| Riesgo | Email duplica/fusiona perfiles. | HMAC único y conflicto explícito. |
| Riesgo | Revisiones filtran PII. | Cifrado y API no expuesta. |
| Dependencia futura | Falta búsqueda y privacidad. | Perfil actual preparado sin habilitarlas. |

## Definition of Ready

`READY_FOR_DEV`
