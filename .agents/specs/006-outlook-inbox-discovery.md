# 006 - Outlook Inbox Discovery

## Objetivo

Entregar el primer worker durable que reclama jobs `QUEUED` y descubre mensajes
en el Inbox de la única cuenta Outlook compartida dentro del rango UTC
snapshot de la vacante. El worker registra únicamente metadatos mínimos e IDs
inmutables para el siguiente incremento de adjuntos; no descarga, almacena ni
analiza documentos.

## Referencias

- `docs/PRD.md`, secciones 4 y 5.
- `docs/PRODUCT_BACKLOG.md`, Epic 3, Feature 3.2.
- `docs/architecture.md`, secciones 6, 7, 8 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 003, 004 y 005.

## Alcance

### Incluido

- Worker durable para `matching_job` con claim y lease definidos en spec 004.
- Consulta Microsoft Graph únicamente a carpeta Inbox y sólo en el rango UTC
  `[receivedFromUtc, receivedToUtcExclusive)` snapshot del job.
- Paginación segura de mensajes con IDs inmutables y campos mínimos.
- Persistencia idempotente de mensajes descubiertos y contadores seguros.
- Transición de `QUEUED` a `DISCOVERING` y luego a `INGESTING_DOCUMENTS`, lista
  para el incremento posterior de adjuntos.
- Manejo de timeout, `429 Retry-After`, errores transitorios, revocación OAuth
  y límites configurables de mensajes por job.
- Métricas, eventos técnicos, auditoría mínima, OpenAPI de estado existente y
  pruebas con doble Graph/Testcontainers.

### Excluido

- Descargar adjuntos, PDF/DOCX, almacenamiento, cifrado de archivos, ClamAV,
  extracción de texto, candidatos, deduplicación y papelera.
- Leer cuerpo, `bodyPreview`, asunto, remitente, destinatarios, categorías o
  cualquier dato de correo no indispensable para el rango y adjuntos.
- Nuevos endpoints públicos de contenido de correo, mensajes o documentos.
- Claude, ranking, reportes, notificaciones, exportaciones y UI.
- Modificar OAuth/secretos de spec 005, salvo exigir reautorización si el scope
  mínimo de descubrimiento no fue otorgado.

## Decisiones arquitectónicas

1. `job` conserva el ciclo de vida y llama un puerto de `outlook`, por ejemplo
   `InboxDiscoveryPort`; no construye URLs Graph ni maneja tokens. `outlook`
   agrega en cada solicitud relevante `Prefer: IdType="ImmutableId"`.
2. El permiso mínimo para listar metadatos de Inbox es `Mail.ReadBasic` además
   de los scopes de conexión. Una conexión de spec 005 sin ese scope cambia a
   `REAUTHORIZATION_REQUIRED`; el worker no solicita escalamiento silencioso.
3. No se persiste ni expone subject, sender, body, preview, destinatarios ni
   payload Graph. El ID inmutable de mensaje es interno y no aparece en APIs,
   logs, auditoría o etiquetas de métrica.
4. Graph se consulta fuera de transacciones de BD. Cada página se persiste en
   una transacción breve e idempotente; un reinicio o lease vencido no duplica
   mensajes ni contadores.
5. La ausencia de mensajes no es falla: el job avanza a `INGESTING_DOCUMENTS`
   con contador cero. El incremento siguiente decide si no hay CV válido.

## Modelo y persistencia

Crear exclusivamente `V7__outlook_inbox_discovery.sql`; no modificar V1–V6.

### `matching_job_discovered_message`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `matching_job_id` | FK no nula a `matching_job`, `ON DELETE RESTRICT`. |
| `graph_message_id` | ID inmutable opaco, no nulo; nunca público. |
| `received_at` | `timestamptz` UTC no nulo. |
| `has_attachments` | Booleano no nulo. |
| `created_at` | `timestamptz` UTC no nulo. |

Constraint único `(matching_job_id, graph_message_id)`. Índice por
`matching_job_id, received_at asc, id asc`. No crear columnas para subject,
sender, body, preview, dirección, nombre o contenido de correo.

Agregar a `matching_job` los campos `discovered_message_count` y
`discovery_completed_at`, ambos derivados y actualizados sólo al finalizar una
página o discovery completo. Crear eventos
append-only en `matching_job_event`: `DISCOVERY_STARTED`, `DISCOVERY_PAGE_SAVED`,
`DISCOVERY_COMPLETED`, `DISCOVERY_FAILED` y `DISCOVERY_REAUTH_REQUIRED`.

## Contrato y operación

No se agrega ruta pública de mensajes. Los endpoints de job de spec 004 siguen
siendo el único contrato del cliente: `JobDetail` puede añadir los conteos de
descubrimiento y `discoveryCompletedAt`, pero nunca IDs Graph ni metadatos de
correo. Campos desconocidos conservan rechazo `422` en las rutas existentes.

El worker se activa únicamente por configuración server-side `job.discovery`
en perfiles permitidos. En `test` se usa un doble de `InboxDiscoveryPort`; en
producción requiere conexión Outlook `CONNECTED` con `Mail.ReadBasic`.

## Reglas de negocio

1. Un worker reclama un `QUEUED` mediante `FOR UPDATE SKIP LOCKED`, crea lease
   y transiciona a `DISCOVERING` en transacción corta. Dos workers nunca obtienen
   el mismo job vigente.
2. La consulta Graph usa exclusivamente `/me/mailFolders/inbox/messages`, filtro
   UTC `receivedDateTime ge from` y `receivedDateTime lt toExclusive`, orden
   `receivedDateTime asc`, tamaño de página máximo 50 y selección mínima:
  `id`, `receivedDateTime` y `hasAttachments`.
3. Toda página usa `Prefer: IdType="ImmutableId"`. Un `@odata.nextLink` sólo se
   sigue si pertenece al host Graph configurado y conserva la consulta iniciada;
   jamás se registra completo.
4. Se procesan como máximo `maxMessagesPerJob` (valor inicial 5.000). Al alcanzar
   el límite se termina discovery con warning técnico seguro, sin consultar fuera
   del rango. El límite no es configurable por API pública.
5. Insertar una página usa `ON CONFLICT DO NOTHING` y recalcula contadores desde
   filas persistidas. Reintentos, claim recuperado o páginas repetidas dejan el
   mismo conjunto y contadores.
6. Tras consumir todas las páginas, el worker transiciona a
   `INGESTING_DOCUMENTS`, libera claim/lease y registra `discoveryCompletedAt`.
   El worker de la siguiente spec será responsable de ese estado.
7. `429` respeta `Retry-After`; errores 5xx/red transitorios tienen máximo tres
   intentos y no mantienen transacciones abiertas. Agotados, el job pasa a
   `FAILED` con código seguro `OUTLOOK_DISCOVERY_TEMPORARY_FAILURE`.
8. Un error de token, revocación o scope insuficiente hace que `outlook` marque
   `REAUTHORIZATION_REQUIRED`; job transiciona a `REAUTHORIZATION_REQUIRED` y
   no reintenta automáticamente.
9. Cancelar un job prevalece entre páginas: antes de persistir o avanzar estado,
   el worker bloquea y confirma que no sea `CANCELLED`; no revierte páginas ya
   persistidas ni continúa llamadas Graph.

## Errores y seguridad

Los endpoints ya existentes mantienen errores JSON seguros con correlation ID.
El worker persiste sólo códigos seguros:

| Situación | Estado/código |
| --- | --- |
| Conexión no autorizada o scope ausente | `REAUTHORIZATION_REQUIRED` / `OUTLOOK_REAUTH_REQUIRED` |
| Límite de mensajes alcanzado | `INGESTING_DOCUMENTS` con warning `MESSAGE_LIMIT_REACHED` |
| Retries transitorios agotados | `FAILED` / `OUTLOOK_DISCOVERY_TEMPORARY_FAILURE` |
| Respuesta Graph inválida | `FAILED` / `OUTLOOK_DISCOVERY_PROTOCOL_ERROR` |
| Cancelación concurrente | `CANCELLED`, sin página posterior persistida |

- No permitir query params de cliente para carpeta, rango, `$select`, `$filter`,
  página o límite.
- URLs, headers `Authorization`, refresh/access tokens y payloads Graph se
  redaccionan antes de loguear excepciones.
- Logs pueden incluir jobId y correlationId como campos; métricas no incluyen
  UUID, fecha exacta, tenant, mensajes ni cualquier PII como etiqueta.

## Auditoría y observabilidad

El actor de las transiciones de worker es `null`; los eventos técnicos viven en
`matching_job_event`. Añadir auditoría `OUTLOOK_DISCOVERY_COMPLETED` o
`OUTLOOK_DISCOVERY_FAILED` sólo al resultado terminal efectivo, con objetivo
`MATCHING_JOB`, correlation ID y sin payload Graph.

Métricas sin PII:

- `outlook.discovery_jobs` con `outcome` (`completed`, `warning`, `failed`,
  `reauth_required`, `cancelled`);
- `outlook.discovery_messages` como contador sin etiquetas de job o vacante;
- `outlook.graph_requests` con `outcome` (`success`, `rate_limited`,
  `transient_failure`, `protocol_failure`).

## OpenAPI y configuración

- Actualizar OpenAPI de `JobDetail` para documentar sólo los conteos y tiempos
  de discovery, estados de job y errores seguros; no publicar IDs Graph.
- Propiedades tipadas server-side: base URI Graph allowlisted, connect/read
  timeout, máximo de reintentos, tamaño de página y máximo de mensajes por job.
- Valores ficticios en `test`; no agregar secretos ni permitir cambiar parámetros
  de Graph desde API/UI.

## Estrategia de pruebas

### Unitarias

- Construcción de filtro UTC inclusivo/exclusivo desde snapshot y selección
  exacta de campos mínimos.
- Validación/normalización de nextLink, header ImmutableId, límites y Retry-After.
- Idempotencia de página, conteos derivados, estados y cancelación entre páginas.
- Redacción de URLs/tokens/payloads de excepción.

### Integración Spring/PostgreSQL Testcontainers

- Dos workers reclaman una vez el mismo `QUEUED`; lease vencido permite retomar.
- Doble Graph pagina mensajes dentro/fuera de rango: sólo persiste los del rango,
  con ID inmutable y sin campos PII en BD o DTO.
- Replay, reinicio y nextLink repetido no duplican filas ni contadores.
- Cero mensajes, límite alcanzado, `429`, 5xx, token revocado y cancelación
  producen el estado/código correcto sin transacción larga.
- Acceso API de job conserva `401`/`403`, detalle seguro, OpenAPI y V7 desde
  V1–V6; regresión `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. Un job `QUEUED` se reclama por un solo worker y pasa a `DISCOVERING` sin
   procesamiento en memoria como garantía de ejecución.
2. El worker consulta sólo Inbox y sólo el intervalo UTC snapshot inclusivo/exclusivo.
3. Cada consulta usa ID inmutable, campos mínimos, paginación segura y límite
   máximo; no lee body, asunto, remitente ni destinatarios.
4. Mensajes descubiertos se guardan idempotentemente con ID opaco, fecha UTC y
   la marca de adjuntos; no se persiste ni expone PII o contenido de correo.
5. Reintento, replay, lease vencido o nextLink repetido no duplican mensajes ni
   contadores.
6. Discovery completo lleva job a `INGESTING_DOCUMENTS` y conserva evidencia
   técnica mínima para el siguiente worker, incluso con cero mensajes.
7. `429`, fallas transitorias, Graph inválido, revocación OAuth y cancelación
   producen estados seguros y no dejan transacciones abiertas.
8. Scope ausente o token inválido exige reautorización administrativa y no se
   reintenta automáticamente.
9. APIs de job permanecen autenticadas, compartidas para `RECRUITER`/`ADMIN` y
   no exponen identificadores Graph ni metadatos de correo.
10. Auditoría, métricas, OpenAPI y logs no incluyen PII, secretos, tokens, URLs
    completas ni UUID como etiquetas de métrica.
11. V7 y pruebas Testcontainers/dobles Graph cubren concurrencia, rango UTC,
    paginación, idempotencia y errores sin descargar documentos ni habilitar
    candidatos, Claude, ranking, notificaciones, exportaciones o UI.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Specs 004/005 aportan job durable y puerto de token. | Usar sólo puertos públicos. |
| Riesgo | IDs Outlook cambian al mover mensajes. | `Prefer: IdType="ImmutableId"` en toda petición. |
| Riesgo | Paginación/retry duplica discovery. | Constraint única y contadores derivados. |
| Riesgo | Graph revela PII en logs. | `$select` mínimo y redacción probada. |
| Dependencia futura | Adjuntos y CVs aún no se descargan. | Estado `INGESTING_DOCUMENTS` queda para spec 007. |

## Definition of Ready

`READY_FOR_DEV`
