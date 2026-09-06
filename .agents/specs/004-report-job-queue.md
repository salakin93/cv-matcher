# 004 - Durable Report Job Queue

## Objetivo

Entregar la fundación backend de trabajos asíncronos de reporte para que un
`RECRUITER` o `ADMIN` solicite un reporte de una vacante activa sin esperar
procesamiento HTTP. El incremento persiste una cola durable, snapshots
inmutables, estados consultables, cancelación y reintento controlado. No
consulta Outlook ni procesa CVs todavía.

## Referencias

- `docs/PRD.md`, sección 4.
- `docs/PRODUCT_BACKLOG.md`, Epic 2, Feature 2.2.
- `docs/architecture.md`, secciones 4, 6, 7, 9 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 001, 002 y 003.

## Alcance

### Incluido

- Módulo backend `job` y API REST JSON bajo `/api/v1`.
- Encolado durable de un trabajo de reporte para una vacante `ACTIVE`.
- Snapshot inmutable de la configuración de vacante y sus requisitos.
- Consulta individual y listado paginado de trabajos por vacante.
- Cancelación de un trabajo activo y retry explícito de un terminal reintentable.
- Restricción transaccional de un único trabajo activo por vacante.
- Puerto interno para claim, lease, recuperación y terminación de un worker futuro.
- Auditoría mínima, OpenAPI, métricas sin PII y pruebas PostgreSQL/Testcontainers.

### Excluido

- UI React, notificaciones in-app o correo, SMTP y plantillas.
- Outlook, Microsoft Graph, OAuth, secretos, tokens y llamadas externas.
- Mensajes, adjuntos, documentos, parsing PDF/DOCX, antimalware, candidatos,
  deduplicación, privacidad y archivos.
- Claude, análisis, ranking, `report_version`, resultados, exportaciones y decisiones humanas.
- Worker programado o en memoria que ejecute procesamiento real.
- Cambios a contratos funcionales de specs 001–003, salvo el puerto público
  mínimo que `vacancy` expone a `job`.

## Decisiones arquitectónicas

1. `job` es dueño de `matching_job`, `matching_job_requirement` y
   `matching_job_event`. No lee tablas de `vacancy`: obtiene una instantánea
   mediante un puerto de aplicación, por ejemplo `VacancySnapshotPort`.
2. Job y snapshot se crean en una transacción breve. Un aviso a un despachador
   futuro sólo ocurre después de commit; perder el aviso no pierde el trabajo
   porque `matching_job` es la fuente de verdad durable.
3. El job conserva su configuración si la vacante se edita, archiva o reactiva.
   El futuro módulo `reporting` usará este snapshot, no la vacante actual.
4. PostgreSQL impone un único job activo por vacante con un índice único parcial.
   La colisión se traduce, también bajo carrera, a `409 ACTIVE_JOB_EXISTS`.
5. Esta spec no habilita procesamiento. El contrato lease prepara recuperación
   multiinstancia para futuros incrementos de Outlook, documentos e IA.

## Modelo y persistencia

Crear exclusivamente `V5__report_job_queue.sql`; no modificar V1–V4.

### `matching_job`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `vacancy_id` | UUID no nulo; referencia lógica a vacante. |
| `requested_by_user_id` | UUID no nulo del actor. |
| `vacancy_version` | `bigint` no nulo incluido en snapshot. |
| `vacancy_title` | Snapshot obligatorio, máximo 160 caracteres. |
| `received_from_utc`, `received_to_utc_exclusive` | Límites UTC snapshot. |
| `status` | Estado permitido; inicia `QUEUED`. |
| `attempt` | Entero no nulo; inicia 1. |
| `retry_of_job_id` | UUID nullable del job que se reintenta. |
| `claimed_by`, `lease_until` | Nullable; para worker futuro. |
| `failure_code` | Código seguro nullable, máximo 80; sin proveedor ni PII. |
| `created_at`, `started_at`, `finished_at`, `updated_at` | `timestamptz` UTC. |

Estados: `QUEUED`, `DISCOVERING`, `INGESTING_DOCUMENTS`, `ANALYZING`,
`COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`,
`REAUTHORIZATION_REQUIRED` y `CANCELLED`.

Estados activos: `QUEUED`, `DISCOVERING`, `INGESTING_DOCUMENTS`, `ANALYZING`.
Crear índice único parcial por `vacancy_id` para ellos; índices por
`vacancy_id, created_at desc, id asc` y por `status, lease_until, created_at`.

### `matching_job_requirement`

UUID PK; FK `matching_job_id` no nula con `ON DELETE RESTRICT`; descripción
snapshot obligatoria de máximo 1.000; peso `smallint` 1–5; `mandatory` no nulo;
`position` entero no negativo y único por job. Conserva orden de vacante.

### `matching_job_event`

Tabla append-only: UUID, `matching_job_id`, actor nullable, acción,
`from_status`, `to_status`, `correlation_id` y timestamp UTC. No guarda CVs,
contenido de correo, payload de proveedor, secretos ni PII adicional.

## Contrato API

Todas las rutas requieren JWT válido, cuenta `ACTIVE`, sesión persistida vigente
y rol efectivo `RECRUITER` o `ADMIN`; el rol procede de cuenta/sesión, no sólo
del claim JWT.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| `POST /api/v1/vacancies/{vacancyId}/report-jobs` | `{}` | `202` y `JobAccepted`. |
| `GET /api/v1/vacancies/{vacancyId}/report-jobs` | `status?`, `page?`, `size?` | `200` y `JobPage`. |
| `GET /api/v1/report-jobs/{jobId}` | — | `200` y `JobDetail`. |
| `POST /api/v1/report-jobs/{jobId}/cancel` | `{}` | `204`. |
| `POST /api/v1/report-jobs/{jobId}/retry` | `{}` | `202` y `JobAccepted`. |

`page` inicia en 0; `size` es 1–100 y por defecto 20. El orden es
`createdAt desc`, `id asc`; `status` es un filtro exacto opcional. Campos JSON
desconocidos se rechazan con `422 VALIDATION_ERROR`. `{}` es el único body
válido para POST sin parámetros.

```json
{
  "jobId": "uuid",
  "status": "QUEUED",
  "attempt": 1,
  "statusUrl": "/api/v1/report-jobs/uuid"
}
```

`JobDetail` expone id, vacancyId, vacancyVersion, título, rango UTC, estado,
intento, `failureCode` seguro, fechas y requisitos snapshot ordenados. Nunca
expone claim/lease, solicitante, sesiones, tokens, CVs ni integración.

## Reglas de negocio

1. Sólo una vacante existente y `ACTIVE` admite jobs. Una inexistente devuelve
   `404 VACANCY_NOT_FOUND`; una archivada, `409 VACANCY_ARCHIVED`.
2. Encolar toma una sola snapshot de versión, rango y requisitos. Debe contener
   entre 1 y 30 requisitos válidos; una inconsistencia no deja job parcial.
3. Dos encolados concurrentes devuelven exactamente un `202` y un
   `409 ACTIVE_JOB_EXISTS`, con un job activo final.
4. Cancelar activo lleva a `CANCELLED`. Repetir cancelación sobre `CANCELLED`
   da `204` sin cambiar timestamps, eventos, auditoría ni métrica. Cancelar
   otro terminal devuelve `409 JOB_NOT_CANCELLABLE`.
5. Sólo `FAILED` y `REAUTHORIZATION_REQUIRED` permiten retry. Crea otro job
   `QUEUED` desde snapshot previo con `attempt + 1` y `retryOfJobId`; no muta
   histórico. Si hay un activo para vacante devuelve `409 ACTIVE_JOB_EXISTS`.
6. Worker futuro reclama con `FOR UPDATE SKIP LOCKED`, escribe claim y lease
   UTC y sólo claim vigente renueva o termina. Lease vencido permite recuperación
   sin duplicar transición terminal.
7. HTTP sólo persiste el job; nunca espera Outlook, documentos, IA ni correo.

## Errores y seguridad

Errores JSON comunes: `status`, `code`, `message`, `timestamp`, `path` y
`correlationId`, en español y sin SQL, trazas, sesiones ni detalle de proveedor.

| Situación | HTTP / código |
| --- | --- |
| Bearer ausente, inválido, revocado o cuenta no activa | `401 UNAUTHENTICATED` |
| Rol no autorizado | `403 FORBIDDEN` |
| Vacante/job inexistente | `404 VACANCY_NOT_FOUND` / `JOB_NOT_FOUND` |
| Vacante archivada | `409 VACANCY_ARCHIVED` |
| Ya existe trabajo activo | `409 ACTIVE_JOB_EXISTS` |
| Cancelación/retry no permitido | `409 JOB_NOT_CANCELLABLE` / `JOB_NOT_RETRYABLE` |
| Payload, UUID, enum o paginación inválidos | `422 VALIDATION_ERROR` |

- SQL parametrizado; sin interpolar filtros o UUID.
- No registrar ni persistir correo, nombre de usuario, CV, texto, token, secreto
  o respuesta Graph/Claude.
- UUID de job y correlation ID pueden ser campos de log técnico, nunca etiquetas
  de métrica. No loguear título ni requisitos.

## Auditoría y observabilidad

Mutaciones efectivas agregan a `audit_event`: `REPORT_JOB_QUEUED`,
`REPORT_JOB_CANCELLED` y `REPORT_JOB_RETRIED`. Objetivo `MATCHING_JOB`, actor,
acción, timestamp y correlation ID; idempotencia no crea auditoría.

Métricas sin PII:

- `matching_jobs.mutations`: `action` (`enqueue`, `cancel`, `retry`) y
  `outcome` (`success`, `conflict`, `validation_error`);
- `matching_jobs.active`: gauge por estado activo sin identificadores;
- `matching_jobs.queue_requests`: `result` (`accepted`, `active_conflict`).

## OpenAPI y configuración

- Documentar bearer, paginación, estados, `202`, `204`, `401`, `403`, `404`,
  `409` y `422`, con ejemplos españoles seguros.
- No publicar claims/leasing, tablas, SQL, secretos ni contratos de integración.
- Sin perfiles, credenciales, schedulers ni conexiones externas nuevos. Pruebas
  con perfil `test`, PostgreSQL Testcontainers y datos sintéticos.

## Estrategia de pruebas

### Unitarias

- Máquina de estados: cancelación, idempotencia, retry y terminales.
- Snapshot: requisitos, pesos, orden e inmutabilidad.
- Validación estricta de DTOs y clasificación de errores.

### Integración Spring/PostgreSQL Testcontainers

- `401` sin bearer, `403` para autoridad no permitida y acceso de `RECRUITER` y
  `ADMIN` con sesión persistida.
- `202`, snapshot inmutable tras editar/archivar vacante y rechazo de vacante
  archivada/inexistente.
- Carrera de encolado: un aceptado, un `ACTIVE_JOB_EXISTS`, un activo final.
- Listado paginado/filtrable, orden estable y detalle sin PII ni datos internos.
- Cancelación repetida, retry válido/no válido y métricas/auditoría idempotentes.
- Dos consumidores prueban claim único, lease vencido y no duplicación terminal.
- V5 desde V1–V4, OpenAPI, `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. `RECRUITER` y `ADMIN` activos con sesión vigente gestionan jobs; sin bearer
   es `401` y rol no permitido es `403` JSON seguro.
2. Encolar vacante `ACTIVE` devuelve `202` con `QUEUED`, URL de estado y snapshot;
   HTTP no ejecuta integración.
3. Vacante inexistente/archivada no crea job y devuelve `404`/`409` seguro.
4. Dos encolados concurrentes dejan un activo; respuestas `202` y
   `409 ACTIVE_JOB_EXISTS`.
5. Snapshot no cambia por edición o estado posterior de vacante.
6. Listado compartido, paginado, filtrable y estable; no revela PII, sesiones,
   leases, tokens o documentos.
7. Cancelar activo llega a `CANCELLED`; repetir no altera timestamps, auditoría,
   evento ni métrica.
8. Retry sólo desde `FAILED`/`REAUTHORIZATION_REQUIRED` crea nuevo `QUEUED`
   vinculado; otros estados devuelven `409 JOB_NOT_RETRYABLE`.
9. Claim/lease PostgreSQL permite un consumidor y recuperación tras vencimiento
   sin duplicar terminal.
10. Auditoría, métricas, OpenAPI, V5 y Testcontainers cubren contratos,
    snapshots, concurrencia y errores sin datos sensibles.
11. No se habilitan Outlook, documentos, candidatos, Claude, resultados,
    notificaciones, exportaciones o UI.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 003 expone snapshot de vacante activa. | Puerto público mínimo; sin SQL entre módulos. |
| Riesgo | Carrera crea dos activos. | Índice único parcial y prueba PostgreSQL. |
| Riesgo | Edición cambia job solicitado. | Snapshot inmutable por job. |
| Riesgo | Reinicio duplica trabajo. | Claim/lease durable y eventos. |
| Dependencia futura | No hay integración que ejecute jobs. | Permanecen `QUEUED` hasta spec de worker. |

## Definition of Ready

`READY_FOR_DEV`
