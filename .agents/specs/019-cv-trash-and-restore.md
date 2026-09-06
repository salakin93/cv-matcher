# 019 - CV Trash and Restore

## Objetivo

Entregar papelera compartida para CVs: reclutadores autorizados pueden enviar un
documento a papelera, restaurarlo durante 180 días y consultar su estado. Un CV
en papelera queda excluido de reportes, ranking, búsqueda histórica y descarga;
no se borra físicamente todavía.

## Alcance

### Incluido

- Soft delete de `candidate_document` y restauración compartida por
  `RECRUITER`/`ADMIN`.
- Retención fija de 180 días, fecha de expiración y consulta paginada de papelera.
- Exclusión transaccional de nuevos jobs, selección, ranking, búsqueda histórica
  y descargas mediante puertos de `document`/`candidate`.
- Auditoría, métricas sin PII, OpenAPI, concurrencia/idempotencia y pruebas.

### Excluido

- Purga automática o eliminación física, solicitud de privacidad, anonimización
  de reportes, recuperación de storage, fusión de perfiles, UI y notificaciones.
- Alterar reportes históricos ya creados, scores, assessments o estados humanos.
- Restaurar después de expiración, descargar desde papelera o exportar la lista.

## Arquitectura y persistencia

Crear exclusivamente `V20__cv_trash_and_restore.sql`; no modificar V1–V19.

Extender `candidate_document` con `lifecycle_status` (`AVAILABLE`, `TRASHED`,
`QUARANTINED`), `trashed_at`, `trashed_by_user_id`, `purge_eligible_at`,
`lifecycle_version`. `TRASHED` exige timestamps/actor y
`purge_eligible_at = trashed_at + 180 días UTC`; no cambia storage, hash ni
metadatos. Crear `candidate_document_lifecycle_event` append-only con documento,
actor, estado anterior/nuevo, versión, correlation ID y UTC, sin PII/rutas.

## Contrato API

JWT, cuenta activa, sesión vigente y rol `RECRUITER`/`ADMIN` son obligatorios.

| Ruta | Operación |
| --- | --- |
| `POST /api/v1/candidate-documents/{documentId}/trash` | `{ expectedVersion }`; `204`. |
| `POST /api/v1/candidate-documents/{documentId}/restore` | `{ expectedVersion }`; `204`. |
| `GET /api/v1/candidate-documents/trash` | página segura, `page?`, `size?`; `200`. |

Lista sólo muestra id técnico, formato, fecha recepción, trashedAt,
purgeEligibleAt y estado; no nombre, correo, CV, ruta, hash, perfil, sender,
storage key o texto. Campos desconocidos y versión inválida son `422`.

## Reglas de negocio

1. Sólo `AVAILABLE` puede pasar a `TRASHED`; solicitud sobre `TRASHED` con
   versión actual es idempotente. `QUARANTINED` o inexistente no se restaura por
   este flujo y retorna `404` seguro.
2. `TRASHED` sólo restaura a `AVAILABLE` antes de `purge_eligible_at`. Al vencer
   devuelve `410 DOCUMENT_RETENTION_EXPIRED`; no revive archivo ni cambia fechas.
3. Cada mutación bloquea fila, compara `expectedVersion`, aumenta versión una vez
   y registra evento/auditoría. Carrera: un `204`, un `409 VERSION_CONFLICT`.
4. Puertos de documento/candidate/historical search deben filtrar `TRASHED` antes
   de descargar, analizar, seleccionar, indexar o usar en nuevo reporte/búsqueda.
   Reportes históricos preservan snapshot y no se reescriben.
5. Restaurar no regenera análisis, score ni resultados; permite uso futuro sólo
   cuando el flujo correspondiente lo solicite.

## Errores y seguridad

`401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 CANDIDATE_DOCUMENT_NOT_FOUND`,
`409 VERSION_CONFLICT`, `410 DOCUMENT_RETENTION_EXPIRED`, `422 VALIDATION_ERROR`.
Errores/logs no incluyen PII, ruta, hash, storage key, actor ajeno ni SQL.

## Auditoría y observabilidad

Auditar `CANDIDATE_DOCUMENT_TRASHED` y `CANDIDATE_DOCUMENT_RESTORED` con actor,
objetivo `CANDIDATE_DOCUMENT`, timestamp/correlation ID, sin PII. Métricas:
`documents.lifecycle_mutations` con acción/resultado cerrados y
`documents.trash_items` gauge sin UUID como etiqueta.

## Pruebas y criterios de aceptación

1. Roles autorizados con sesión vigente trashes/restauran; `401`/`403` seguros.
2. Trash calcula exactamente 180 días y es idempotente sin auditoría/métrica extra.
3. Restore antes de vencimiento funciona; vencido retorna `410` sin mutación.
4. Concurrencia con misma versión deja un cambio y `409` coherente.
5. Documento en papelera no puede descargarse, analizarse, seleccionarse ni entrar
   en nuevos reportes/búsquedas; históricos no cambian.
6. Lista paginada minimiza datos y no filtra PII/ruta/hash/texto.
7. Auditoría/logs/OpenAPI/métricas no exponen PII o UUID como etiqueta.
8. V20 y Testcontainers cubren lifecycle, retención, concurrencia, exclusión y
   errores sin purga, privacidad, UI, exportación o notificaciones.

## Riesgos y dependencias

| Riesgo/dependencia | Tratamiento |
| --- | --- |
| Documento trash sigue apareciendo en resultados nuevos | Filtro obligatorio mediante puertos. |
| Restore después de retención | Validación UTC y próximo worker de purga. |
| Histórico se altera | Sólo exclusión futura; snapshots inmutables. |
| Privacidad requiere borrado real | Se deja para spec específica. |

## Definition of Ready

`READY_FOR_DEV`
