# 009 - Structured CV Requirement Analysis

## Objetivo

Entregar el análisis server-side de cada texto de CV extraído contra los
requisitos snapshot de una vacante. Claude devuelve evaluación y evidencia
estructurada en español; el backend valida exhaustivamente la respuesta y la
persiste de forma idempotente. Este incremento no calcula puntajes finales,
ranking, candidatos deduplicados ni decisiones humanas.

## Referencias

- `docs/PRD.md`, secciones 4, 6 y 9.
- `docs/PRODUCT_BACKLOG.md`, Epic 4, Feature 4.1.
- `docs/architecture.md`, secciones 4, 7, 8, 10 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 004, 008.

## Alcance

### Incluido

- Módulo `analysis` y worker durable para documentos con texto extraído de un
  job `ANALYZING`.
- Cliente server-side de Anthropic mediante puerto `ClaudeAnalysisPort`.
- Prompt/contrato versionado de salida JSON estricta por requisito snapshot.
- Validación de estructura, IDs, rangos, estados, longitud, idioma y evidencia.
- Persistencia inmutable e idempotente de evaluaciones por documento, requisito,
  hash de texto, versión de prompt y modelo.
- Manejo seguro de respuestas inválidas, rate limits, timeouts, retry y fallas
  parciales sin perder resultados válidos ya persistidos.
- Auditoría técnica, métricas sin PII, configuración por entorno, OpenAPI de
  estado de job y pruebas con dobles Claude.

### Excluido

- Cálculo de `mandatoryScore`, bono opcional, `totalScore`, desempates, ranking,
  Top 5, `report_version` y finalización del job.
- Crear o modificar candidatos, identificar personas, correo, disponibilidad,
  estados humanos, privacidad, papelera, búsqueda o descargas.
- Cambiar pesos, requisitos, vacante, estado de contratación o descarte.
- Exponer prompt, texto de CV, payload Claude, API key, provider request ID,
  evidencia completa o evaluaciones por API/UI.
- Notificaciones, exportaciones, modificaciones de modelo desde UI y llamadas
  directas del cliente a Anthropic.

## Decisiones arquitectónicas

1. `analysis` es el único cliente Anthropic. `job` orquesta mediante un puerto
   público; `analysis` obtiene texto sólo desde `DocumentTextPort`, nunca desde
   filesystem o tablas de `document`.
2. Claude recibe sólo texto extraído necesario, requisitos snapshot, idioma de
   salida español e instrucciones de que no puede contratar, descartar, cambiar
   pesos, calcular score, ordenar ni ejecutar acciones. El texto CV es contenido
   no confiable y no puede cambiar las instrucciones del sistema.
3. El backend es autoridad exclusiva para validar la respuesta. No persiste una
   evaluación si faltan requisitos, sobran IDs, hay valores fuera de rango,
   evidencia no localizada, texto excesivo o estado incompatible.
4. Cada análisis conserva `model_id`, `prompt_version` y hash de texto. Un
   reintento del mismo triple es idempotente; cambiar modelo/prompt crea nueva
   revisión y no sobrescribe la evaluación histórica.
5. El uso de proveedores externos se deshabilita por defecto. En producción
   requiere configuración explícita, clave server-side y aprobación operativa
   de la transferencia de texto; pruebas usan exclusivamente un doble local.

## Modelo y persistencia

Crear exclusivamente `V10__structured_cv_analysis.sql`; no modificar V1–V9.

### `document_requirement_assessment`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `matching_job_id` | FK no nula a job. |
| `candidate_document_id` | FK no nula a documento. |
| `job_requirement_position` | Entero no negativo del snapshot. |
| `text_sha256` | Hash del texto exacto analizado. |
| `model_id` | Identificador validado, máximo 120. |
| `prompt_version` | Identificador no vacío, máximo 80. |
| `compatibility` | Entero 0–100; evaluación Claude, no score final. |
| `assessment_status` | `CUMPLE`, `NO_CUMPLE` o `NO_DEMOSTRADO`. |
| `evidence_ciphertext` | Evidencia breve cifrada AES-GCM. |
| `explanation_ciphertext` | Explicación breve en español cifrada AES-GCM. |
| `created_at` | `timestamptz` UTC. |

Constraint único por job, documento, requisito, hash, modelo y versión de
prompt. `NO_DEMOSTRADO` permite evidencia vacía; los otros estados exigen
evidencia localizada y no vacía. Ninguna columna guarda request/response crudo,
prompt completo, texto de CV, token o headers Anthropic.

Agregar a `matching_job` conteos `analysis_document_count`,
`analysis_warning_count`, `analysis_failed_count` y `analysis_completed_at`.
Agregar eventos `ANALYSIS_STARTED`, `ANALYSIS_DOCUMENT_COMPLETED`,
`ANALYSIS_DOCUMENT_WARNING`, `ANALYSIS_DOCUMENT_FAILED` y
`ANALYSIS_COMPLETED` a `matching_job_event`.

## Contrato interno de análisis

El contrato solicitado a Claude devuelve exactamente una lista, ordenada por
`requirementPosition`, de objetos:

```json
{
  "requirementPosition": 0,
  "compatibility": 85,
  "status": "CUMPLE",
  "evidence": "Experiencia declarada en Spring Boot.",
  "explanation": "La experiencia indicada coincide con el requisito."
}
```

Para cada requisito snapshot hay exactamente un objeto. `compatibility` es un
entero 0–100. `status` sólo admite `CUMPLE`, `NO_CUMPLE`, `NO_DEMOSTRADO`.
`evidence` y `explanation` están en español, se limitan a 500 y 1.000 caracteres
respectivamente, y no pueden contener instrucciones, URLs, secretos o datos de
proveedor. El backend comprueba que evidencia, cuando existe, sea una cita o
paráfrasis verificable del texto de CV; si no puede validarse con una regla
determinista conservadora, rechaza la respuesta completa.

## Reglas de negocio

1. El worker reclama un job `ANALYZING` y procesa sólo documentos con texto
   `EXTRACTED` vinculados a ese job. Respeta cancelación antes de obtener texto,
   invocar Claude y persistir evaluaciones.
2. Construye una solicitud por documento con requisitos snapshot ordenados. No
   envía ID de usuario, correo, sender, asunto, storage key, hash, token, rutas,
   payload Graph ni instrucciones provenientes del CV como mensajes del sistema.
3. El texto se limita a `maxAnalysisCharacters` server-side. Si excede, recorta
   de manera determinista y registra warning seguro `TEXT_TRUNCATED_FOR_ANALYSIS`;
   el recorte no cambia el documento original ni la extracción cifrada.
4. Una respuesta válida se persiste atómicamente para todos los requisitos de
   ese documento. Si un requisito falla validación, no persiste una evaluación
   parcial de la respuesta.
5. Error válido de un documento no elimina evaluaciones correctas de otros. Tras
   agotar tres reintentos transitorios, el documento queda con warning seguro y
   job permanece `ANALYZING` para la futura política de reporte con warnings.
6. `429` respeta `Retry-After`; 5xx/red/timeout son transitorios. Una respuesta
   JSON inválida, contenido bloqueado o contrato incumplido no se reintenta y
   genera `ANALYSIS_RESPONSE_INVALID` o `ANALYSIS_CONTENT_BLOCKED`.
7. Cuando todos los documentos extraídos terminan, se registra
   `analysisCompletedAt`; el job aún no pasa a `COMPLETED` porque la siguiente
   spec calcula resultados deterministas y crea una versión de reporte.
8. Todo requisito obligatorio sin evidencia persistida en un documento conserva
   `NO_DEMOSTRADO` y compatibilidad 0; el backend lo inserta sólo cuando una
   respuesta válida omitió evidencia permitida, nunca elimina el documento.

## Errores y seguridad

El cliente sólo observa estado y conteos seguros del job. No hay endpoint de
evaluaciones ni contenido. Códigos internos cerrados:

| Situación | Resultado seguro |
| --- | --- |
| Análisis deshabilitado/no configurado | `FAILED` / `ANALYSIS_NOT_CONFIGURED` |
| Timeout, 429 o 5xx agotado | warning / `ANALYSIS_TEMPORARY_FAILURE` |
| JSON, esquema, idioma o IDs inválidos | warning / `ANALYSIS_RESPONSE_INVALID` |
| Contenido bloqueado por proveedor | warning / `ANALYSIS_CONTENT_BLOCKED` |
| Texto no disponible o integridad fallida | warning / `ANALYSIS_TEXT_UNAVAILABLE` |

- Nunca registrar request, response, prompt, texto, evidencia, explicación, key,
  headers, request ID de proveedor o hashes en claro.
- Access token/clave Anthropic procede sólo de `ANTHROPIC_API_KEY` en entorno o
  secret manager; no se acepta por HTTP ni se devuelve en health/OpenAPI.
- Evidencia y explicación se cifran con clave/version server-side. Sólo el futuro
  módulo de reportes autorizado podrá descifrarlas mediante un puerto interno.

## Auditoría y observabilidad

Eventos por documento pertenecen a `matching_job_event`. Agregar auditoría
`STRUCTURED_ANALYSIS_COMPLETED` o `STRUCTURED_ANALYSIS_FAILED` una vez por job,
con objetivo `MATCHING_JOB`, correlation ID y sin contenido/análisis.

Métricas sin PII:

- `analysis.requests` con `outcome` (`success`, `rate_limited`, `temporary_failure`,
  `invalid_response`, `blocked`);
- `analysis.documents` con `outcome` (`completed`, `warning`, `failed`);
- `analysis.duration` sin job, documento, requisito, modelo o vacante como etiqueta.

## OpenAPI y configuración

- `JobDetail` documenta conteos/advertencias de análisis, no evaluaciones,
  evidencia, modelo interno, prompt, texto ni provider request ID.
- Propiedades server-side: `ANALYSIS_ENABLED`, `ANTHROPIC_API_KEY`, modelo
  permitido, versión de prompt, timeout, retries, máximo de texto y clave de
  cifrado de evaluaciones. Validar modelo contra allowlist fija.
- `test` usa doble local con respuestas sintéticas; no se permiten claves reales
  ni llamadas Anthropic. `prod` falla rápido si se habilita análisis sin
  configuración o aprobación operativa requerida.

## Estrategia de pruebas

### Unitarias

- Contrato estricto: requisitos faltantes/sobrantes, IDs, rangos, estados,
  idioma, longitudes y evidencia inválida.
- Protección frente a prompt injection y recorte determinista de texto.
- Cifrado de evidencia/explicación, idempotencia hash-modelo-prompt y estados.
- Retry, `Retry-After`, errores no reintentables y redacción de payloads.

### Integración Spring/PostgreSQL Testcontainers

- Claim/lease/cancelación procesan una vez cada documento y no persisten datos
  después de cancelar.
- Doble Claude válido genera una evaluación por requisito, cifrada e inmutable;
  respuesta inválida no deja requisitos parciales.
- Falla parcial conserva documentos correctos y advertencias seguras; `429`,
  timeout, bloqueo y configuración ausente producen estado correcto.
- Ningún texto/prompt/evidencia aparece en DTO, logs, auditoría, métrica o BD
  en claro; V10 desde V1–V9, OpenAPI, `./gradlew test`, `git diff --check`.

## Criterios de aceptación

1. Un worker procesa una vez documentos con texto extraído de un job `ANALYZING`;
   lease, replay y cancelación no duplican ni persisten tras cancelar.
2. Claude recibe sólo texto necesario y requisitos snapshot; nunca CVs binarios,
   identidad, email, mensajes, tokens, rutas o instrucciones no confiables.
3. Cada respuesta válida contiene exactamente una evaluación por requisito con
   posición, entero 0–100, estado permitido y español seguro.
4. Backend rechaza atómicamente respuesta con schema, IDs, rangos, idioma,
   longitudes o evidencia inválidos; no persiste evaluación parcial.
5. Evaluaciones/evidencias se persisten cifradas e idempotentes por documento,
   hash, modelo y prompt; nunca en API, logs, auditoría o métricas en claro.
6. Reintentos transitorios respetan `Retry-After`; fallas parciales preservan
   resultados válidos y errores de contrato no se reintentan.
7. Cada obligatorio sin evidencia se representa como `NO_DEMOSTRADO` con cero,
   sin excluir documento ni contratar/descartar persona.
8. Job registra finalización de análisis pero no crea score, ranking, reporte ni
   cambia a terminal hasta el siguiente incremento determinista.
9. Configuración/clave Anthropic son sólo server-side, pruebas usan dobles y
   análisis se mantiene deshabilitado si faltan requisitos operativos.
10. Auditoría, métricas y OpenAPI revelan sólo estados/conteos/códigos seguros,
    sin PII, prompt, texto, evidencia, secreto o UUID como etiqueta.
11. V10 y pruebas Testcontainers cubren validación, cifrado, concurrencia,
    retries, errores y ausencia de alcance de candidatos/ranking/UI.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 008 provee texto cifrado por documento. | Consumir sólo `DocumentTextPort`. |
| Riesgo | Prompt injection desde CV. | Separar instrucciones, schema estricto y validación backend. |
| Riesgo | Proveedor devuelve evaluación inválida. | Rechazo atómico y códigos seguros. |
| Riesgo | Texto personal llega a logs/proveedor sin control. | Mínimo necesario, redacción y gate operativo. |
| Dependencia futura | Falta score/ranking/reporte. | Job queda `ANALYZING` hasta spec determinista. |

## Definition of Ready

`READY_FOR_DEV`
