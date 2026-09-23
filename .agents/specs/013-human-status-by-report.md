# 013 - Human status by report

## Estado
`READY_FOR_DEV` — backend only; PRD 006; depends on 012.

## Objetivo
Persistir una decisión humana compartida por entrada de candidato y versión de reporte, sin alterar el snapshot ni resultados inmutables.

## Referencias
`docs/prd-006-human-status-by-report.md`, `docs/PRD.md` §7, `docs/architecture.md` §§4, 6, 9.

## Alcance
### Incluido
- Lectura y cambio por `RECRUITER`/`ADMIN` de `PENDIENTE`, `EN_REVISION`, `PRESELECCIONADO` o `DESCARTADO` para una `report_candidate` concreta.
- Control optimista, auditoría mínima y DTO de ranking que expone el estado actual y su versión.
### Excluido
- Comentarios, motivos, asignación, notificaciones, cambios de perfil, score, ranking, exportación o historial visible a reclutadores.

## Comportamiento y reglas
- Toda entrada creada por 012 inicia `PENDIENTE`; el estado pertenece a la entrada, no al perfil ni al documento.
- Cualquier transición explícita entre valores permitidos es válida. Un cambio efectivo no modifica ninguna columna del snapshot de reporte.
- El primer `PUT` que coincide con `expectedVersion` prevalece. Una versión obsoleta no sobrescribe y obliga a recargar.
- Sólo se modifica una entrada de versión `COMPLETED` o `COMPLETED_WITH_WARNINGS`; guardar el mismo estado no cambia versión, fecha ni auditoría.

## Contratos
- `GET /api/v1/report-versions/{reportVersionId}/candidates` incluye `id`, ranking inmutable, `humanStatus` y `humanStatusVersion`; no expone historial.
- `PUT /api/v1/report-versions/{reportVersionId}/candidates/{reportCandidateId}/human-status` recibe `{ "status": "EN_REVISION", "expectedVersion": 0 }` y devuelve el estado y versión nuevos.
- Errores públicos: `404` si la versión/entrada no existe, `409 VERSION_CONFLICT|REPORT_NOT_COMPLETED` si la versión no coincide o no es terminal, `422 INVALID_HUMAN_STATUS` para valor inválido.

## Configuración centralizada
No introduce configuración externa.

## Datos y persistencia
- Flyway nuevo e inmutable: añadir a `report_candidate` `human_status` no nulo con default `PENDIENTE`, `human_status_version` no nulo con default `0`, `human_status_changed_at` UTC y `human_status_changed_by_user_id` nullable FK. No modificar migraciones previas.
- Índice de clave primaria existente basta para actualización por entrada; la mutación usa condición por `human_status_version`.
- El módulo `reporting` posee el caso de uso; solicita al puerto `audit` registrar `HUMAN_STATUS_CHANGED` después de una actualización efectiva.

## Integraciones
Sólo el puerto interno `audit`; no hay proveedor externo ni outbox.

## Errores y estados
- `401` sin sesión válida; `403` sin rol permitido; respuestas uniformes y en español.
- Un estado inexistente, entrada ajena a la versión o body malformado nunca revela datos de otra entrada.
- La auditoría fallida debe hacer fallar la transacción, para no confirmar un cambio efectivo sin evento requerido.

## Seguridad y privacidad
- Autorización de recurso en `reporting`; no aceptar una entrada por UUID fuera de su versión.
- Evento mínimo: actor, UTC, tipo, referencia interna de versión/entrada y estados anterior/nuevo; sin nombre, correo, CV, evidencia ni texto extraído.

## Observabilidad
Log estructurado `human_status_changed` con correlationId, actorId interno y referencias UUID; métrica contador por estado destino. No registrar PII.

## Estrategia de pruebas
### Validación manual
- Con dos cuentas autorizadas, cambiar un estado y verificar la lectura compartida; repetir con dos pestañas para obtener `409` en la segunda.
- Confirmar que otra versión de la misma persona, scores, orden y assessments no cambian; revisar evento como ADMIN.
### Backlog de automatización diferida
- Unitarias de transiciones y mapeo; integración PostgreSQL de control optimista y auditoría transaccional; API de roles/IDOR; regresión de inmutabilidad de reporte.

## Criterios de aceptación
- AC-006-01 a AC-006-06 se cumplen, incluido inicio `PENDIENTE`, aislamiento entre reportes y conflicto de concurrencia.
- La API no permite cambiar resultados calculados ni presenta historial a `RECRUITER`.

## Riesgos y dependencias
- Depende de que 012 cree entradas inmutables y de los contratos de `audit` de 001/002.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: el estado humano es un overlay mutable, versionado, sobre `report_candidate`; no forma parte del snapshot calculado.

## Definition of Ready
`READY_FOR_DEV`
