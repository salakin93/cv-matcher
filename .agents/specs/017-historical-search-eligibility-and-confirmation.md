# 017 - Historical search eligibility and confirmation

## Estado
`READY_FOR_DEV` — backend only; PRD 009; depends on 012 and 016.

## Objetivo
Autorizar explícitamente una intención de búsqueda histórica sólo cuando la versión origen no alcanza su umbral, sin analizar CV alguno.

## Referencias
`docs/prd-009-shared-profiles-and-historical-search.md`, `docs/architecture.md` §§4, 7.

## Alcance
### Incluido
- Cálculo de elegibilidad de una versión terminada y creación idempotente de una confirmación con filtros validados.
- Selección de candidatos elegibles por metadatos, excluyendo papelera/privacidad/no disponible, sin lectura de CV ni llamada AI.
### Excluido
- Crear job, analizar documentos, publicar versión combinada, directorio global o iniciar automáticamente búsqueda.

## Comportamiento y reglas
- Es elegible sólo si la versión está terminada y ninguna entrada tiene `totalScore >= persistedThreshold`. Si no es elegible, no se puede confirmar.
- Filtros opcionales: recepción UTC inclusiva, disponibilidades, términos/habilidades, ubicación, nombre/correo y mínimo 0–100 (sugerencia 70). La confirmación fija un snapshot de filtros, versión origen, vacante y solicitante.
- La confirmación no enumera ni devuelve nombres, correos, documentos o conteos sensibles de terceros. Sólo devuelve `confirmationId`, estado `CONFIRMED`, filtros normalizados y fecha.

## Contratos
- `GET /api/v1/report-versions/{id}/historical-search-eligibility` devuelve `{ eligible, reasonCode?, suggestedMinimumScore: 70 }`.
- `POST /api/v1/report-versions/{id}/historical-search-confirmations` recibe filtros y `Idempotency-Key`; responde `201` con confirmación. Mismo actor/clave devuelve la existente.
- `409 HISTORICAL_SEARCH_NOT_ELIGIBLE`, `409 REPORT_NOT_COMPLETED`, `422 INVALID_HISTORICAL_SEARCH_FILTER`; no se crea confirmación ante esos errores.

## Configuración centralizada
La sugerencia `70` reutiliza el valor de negocio persisted threshold por defecto definido por `vacancy`; no es configuración ADMIN ni se duplica en frontend.

## Datos y persistencia
- Flyway: `historical_search_confirmation` con UUID, report_version_origen, vacancy, requester, snapshot de filtros validado y cifrado cuando contenga nombre, correo, ubicación o términos, estado `CONFIRMED|CONSUMED|INVALIDATED`, idempotency key, UTC y versionado. Constraint único de idempotencia por solicitante.
- `candidate` expone un puerto de preselección por filtros que devuelve IDs internos elegibles; `reporting`/`job` no consulta sus tablas. Esta spec no persiste selección ni inicia procesamiento.

## Integraciones
Sin Graph, Claude, archivos ni outbox.

## Errores y estados
- Una confirmación se invalida antes de consumirse si la versión origen deja de ser apta por una operación de privacidad que afecte su contexto; 018 debe revalidar elegibilidad y filtros.
- `401`/`403` para no autenticado/sin rol; `404` para versión no visible.

## Seguridad y privacidad
- Sólo `RECRUITER`/`ADMIN`; no filtrar perfiles/documentos eliminados, bloqueados, en papelera o sin archivo disponible.
- Auditoría `HISTORICAL_SEARCH_CONFIRMED` con actor, UTC y referencia de versión, sin filtros textuales ni PII.

## Observabilidad
Contadores de elegibilidad, confirmaciones y rechazos por código; logs con versión/confirmación/correlationId, no términos ni identidad.

## Estrategia de pruebas
### Validación manual
- Consultar versión con/sin entrada sobre umbral; confirmar sólo la elegible con filtros límite y repetir clave idempotente.
- Verificar que no existe job, llamada externa ni nueva versión antes de 018.
### Backlog de automatización diferida
- Unitarias de elegibilidad; integración de filtros y exclusiones; API de idempotencia/roles; regresión de no procesamiento sin confirmación.

## Criterios de aceptación
- AC-009-03 se cumple hasta la confirmación: no hay análisis sin confirmación válida y los filtros se validan sin exponer terceros.

## Riesgos y dependencias
- Depende del umbral fijado en 012, perfiles de 016 y estados de documento/papelera. 018 consume exclusivamente confirmaciones `CONFIRMED`.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: confirmación es un recurso durable separado y auditable; su creación no crea ni reserva un job.

## Definition of Ready
`READY_FOR_DEV`
