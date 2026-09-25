# Mapa de Persistencia y Contratos API

## Estado

`READY_FOR_DEV`. Este mapa es la referencia documental de ARC-005 para la
secuencia Flyway, ownership de datos y composicion de contratos. No autoriza
crear, editar ni renumerar migraciones.

## Reglas de secuencia

- Por la decision aprobada de reinicio antes de crear entornos compartidos, el
  historial anterior fue reemplazado por `V1__identity_baseline.sql`. Desde ese
  baseline, toda tabla, constraint o indice futuro se agrega en una nueva
  migracion; nunca se edita una migracion aplicada.
- Un modulo accede a datos de otro modulo mediante puerto de aplicacion, nunca
  mediante repositorio o SQL directo.
- `matching_job` es la fuente durable de estados, claims y checkpoints. Los
  workers de Outlook, documentos y analisis usan sus contratos de lease y
  cancelacion.
- `report_version` es inmutable. Los overlays mutables son el estado humano y
  la disponibilidad actual de perfil; no modifican scores ni snapshots.

## Historial Flyway Aplicado

| Version | Spec principal | Datos e invariantes actuales |
| --- | --- | --- |
| V1 | 001-002 | `user_account`, `user_session`, `account_action_token`, `verification_resend_attempt`, `outbox_message`, `audit_event`; correo normalizado unico, tokens hasheados de un uso y outbox cifrado. |
| V2 | 003 | `vacancy`, `vacancy_requirement`; amplía el constraint de rol para permitir `ADMIN` requerido por el bootstrap de identidad. |

## Plan de Persistencia por Spec

| Specs | Modulo owner | Tablas o cambios futuros | Invariantes relevantes |
| --- | --- | --- | --- |
| 001-002 | `identity`, `audit` | Implementadas en `V1`. | Correo unico, token hasheado de un uso y auditoria append-only. |
| 003 | `vacancy` | `V2`: `vacancy`, `vacancy_requirement`. | Rango UTC valido, peso 1--5, orden unico. |
| 004 | `job` | `matching_job`, requisitos snapshot y eventos. | Un job activo por vacante, claim/lease y transiciones idempotentes. |
| 005-006 | `outlook` | Conexion, intento OAuth y mensajes descubiertos. | Singleton Outlook, intento OAuth unico, mensaje unico por job/referencia inmutable. |
| 007-008 | `document` | Documentos y vinculo job-documento; estado de extraccion y texto cifrado. | Adjunto unico, hash tecnico indexado, documento disponible con storage/hash/formato validos. |
| 009-010 | `analysis`, `reporting` | `document_requirement_assessment` y resultado de score por documento. | Conjunto completo de evaluaciones validado antes de score; score referencia snapshot. |
| 011 | `candidate` | `candidate_profile` y relacion documento-identidad. | Igualdad protegida de correo; un candidato seleccionado por job/identidad; anonimos por documento. |
| 012-013 | `reporting` | `report_version`, `report_candidate`, `requirement_assessment`; overlay de estado humano. | Maximo un reporte no vacio por job; referencia inmutable a documento, perfil nullable; version optimista de estado humano. |
| 014 | `reporting`, `document` | `document_download_attempt`. | Admision concurrente por reserva; solo stream `COMPLETED` consume cuota. |
| 015 | `reporting` | `report_export`. | Idempotency key por solicitante; artefacto privado con expiracion. |
| 016 | `candidate` | Extensiones de perfil y `candidate_profile_correction`. | Disponibilidad default `DESCONOCIDO`; correccion versionada con valor anterior. |
| 017-018 | `job`, `candidate`, `reporting` | `historical_search_confirmation`; extensiones de job y report-version para tipo, linaje y confirmacion. | Confirmacion consumida una vez; maximo 500 elegibles; predecessor solo para version combinada. |
| 019-020 | `document` | Estado/fechas de papelera y datos de purge o work-item. | `TRASHED` excluye operacion; `PURGING` bloquea restore; `PURGED` no se restaura. |
| 021 | `administration` coordinando puertos | `privacy_deletion`, flags de bloqueo y anonimizado de `report_candidate`. | Bloqueo prevalece sobre descarga/seleccion; no PII ni referencia descargable tras anonimizar. |
| 022 | `notification` | `notification` y outbox durable. | Un aviso por destinatario/job/tipo/canal; referencia interna de job no se expone en avisos ADMIN. |
| 023 | `administration` | `system_operational_configuration` singleton. | Concurrencia entre 1 y 10; modelo allowlisted; control optimista. |
| 024 | `audit` | Evolucion append-only de `audit_event` e indices de consulta. | Solo `audit.append`; sin UPDATE/DELETE para rol de aplicacion cuando PostgreSQL lo permita. |

## Ciclo de Vida Documental

`document` es el unico interprete del ciclo de vida y expone un puerto de
disponibilidad a `job`, `candidate`, `reporting` y descarga.

| Estado o bloqueo | Descarga | Seleccion futura | Restore | Purga/privacidad |
| --- | --- | --- | --- | --- |
| `AVAILABLE` | Permitida si el reporte autoriza | Permitida | No aplica | Elegible para privacidad |
| `TRASHED` | No | No | Antes de 180 dias | Elegible para purge y privacidad |
| `PURGING` | No | No | No | Worker de purge retoma; privacidad prevalece |
| `PURGED` | No | No | No | Sin datos operativos recuperables |
| `privacy_blocked` | No | No | No | Privacidad coordina el borrado y anonimizado |

Los workers de 017-018 revalidan el puerto antes de seleccionar, analizar o
publicar. Esto evita una dependencia circular de implementacion entre busqueda,
papelera y privacidad.

## Contratos OpenAPI por Recurso

| Recurso | Endpoint canonico | Specs que lo definen o extienden |
| --- | --- | --- |
| Autenticacion | `/api/v1/auth/register`, `verify-email`, `resend-verification`, `login`, `refresh`, `logout`, `me`, password/email changes | 001-002 |
| Usuarios ADMIN | `GET/PATCH /api/v1/admin/users` | 002 |
| Vacantes | `POST/GET /api/v1/vacancies`, `GET/PUT /api/v1/vacancies/{id}`, `POST .../archive`, `POST .../reactivate` | 003 |
| Jobs | `POST /api/v1/vacancies/{id}/report-jobs`, `GET /api/v1/vacancies/{id}/report-jobs`, `GET /api/v1/report-jobs/{id}`, `POST .../cancel`, `POST .../retry` | 004, 006-007, 018 |
| Outlook ADMIN | `GET /api/v1/admin/outlook-connection`, `POST .../authorization`, `GET /api/v1/oauth/microsoft/callback` | 005 |
| Reportes | `GET /api/v1/report-versions/{id}`, `GET /api/v1/vacancies/{id}/report-versions`, `GET /api/v1/report-jobs/{id}/report-version` | 012 |
| Entradas de reporte | `GET /api/v1/report-versions/{id}/candidates` con paginacion, score/compliance/warning/evidence, disponibilidad y query | 013, 015-016 |
| Estado humano | `PUT /api/v1/report-versions/{id}/candidates/{candidateId}/human-status` | 013 |
| CV snapshot | `GET /api/v1/report-versions/{id}/candidates/{candidateId}/document` | 014 |
| Exportaciones | `POST /api/v1/report-versions/{id}/exports`, `GET /api/v1/exports/{id}`, `GET /api/v1/exports/{id}/download` | 015-016 |
| Perfil | `GET/PUT /api/v1/candidate-profiles/{id}` | 016 |
| Búsqueda histórica | Elegibilidad y confirmación bajo `/api/v1/report-versions/{id}/historical-search-*`; job bajo `/api/v1/historical-search-confirmations/{id}/jobs` | 017-018 |
| Papelera | `GET /api/v1/documents/trash`, `POST /api/v1/documents/{id}/trash`, `POST .../restore` | 019-020 |
| Privacidad | `POST /api/v1/privacy-deletions`, `GET /api/v1/privacy-deletions/{id}` | 021 |
| Notificaciones | `GET /api/v1/notifications`, `PUT /api/v1/notifications/{id}/read` | 022 |
| Configuración | `GET/PUT /api/v1/admin/operational-configuration` | 023 |
| Auditoría | `GET /api/v1/admin/audit-events` | 024 |

El DTO de detalle de job es propiedad de `job` (004): los workers agregan solo
conteos y advertencias seguros. Los DTOs de entrada de reporte se componen en
un unico endpoint: 013 define estado humano, 015 añade filtros/paginacion y 016
añade disponibilidad y query. El backend publica estos contratos como OpenAPI;
la SPA no crea rutas o tipos alternativos.

## Auditoría y Notificaciones

Los productores registran códigos allowlisted de acción mediante `audit.append`.
La consulta 024 proyecta únicamente actor, UTC, código y referencia segura. Los
códigos incluyen identidad, vacante, job, estado humano, descarga, exportación,
perfil, papelera, purge, privacidad, configuración e integración; no se
serializa metadata no allowlisted.

`job` emite el evento terminal después de commit. `notification` crea de forma
idempotente la notificación in-app y el outbox de correo por
destinatario/job/tipo/canal. Para reautorización, los ADMIN reciben contenido
genérico sin datos del job ni del solicitante.

## Resoluciones ARC-005

- Scoring determinista pertenece a `reporting`; `analysis` produce evaluaciones
  Claude validadas.
- `document_requirement_assessment` es el resultado mutable validado de
  análisis; `requirement_assessment` es su copia inmutable de reporte.
- La configuración ADMIN de modelo es 023, no 011.
- `report_version` es la identidad canónica del recurso de reporte.
- La concurrencia global se consulta al claim; el modelo se captura en el job.
- Ubicación y habilidades de perfil empiezan vacías y sólo se completan por
  corrección humana autorizada.
