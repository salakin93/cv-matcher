# 015 - Report filters and base exports

## Estado
`READY_FOR_DEV` — backend only; PRD 008; depends on 012 and 013.

## Objetivo
Consultar una versión terminada con filtros no persistentes y producir exportaciones PDF/XLSX minimizadas del reporte completo.

## Referencias
`docs/prd-008-report-filters-and-exports.md`, `docs/architecture.md` §§4, 9.

## Alcance
### Incluido
- Filtros por totalScore, cumplimiento obligatorio, advertencias y evidencia insuficiente, combinados con `Y`.
- Solicitud asíncrona, consulta y descarga protegida de exportación PDF/XLSX del reporte completo, con expiración y auditoría.
### Excluido
- Disponibilidad, texto de perfil, búsqueda histórica, estado humano en exportación, CVs, enlaces públicos y otros formatos.

## Comportamiento y reglas
- Sólo `COMPLETED`/`COMPLETED_WITH_WARNINGS` permiten filtros/exportación. Los filtros afectan la respuesta de consulta, no el reporte ni la exportación.
- Exportar ignora siempre filtros de pantalla. Incluye exactamente nombre, correo, ubicación, disponibilidad, mandatoryScore, optionalScore, totalScore y evidencias breves permitidas. Antes de existir perfil compartido, disponibilidad es `DESCONOCIDO`; 016 la sustituye dinámicamente en exportaciones futuras.
- `TODOS_CUMPLEN` exige que todos los obligatorios estén `CUMPLE`; `ALGUNO_NO_CUMPLE` y `ALGUNO_NO_DEMOSTRADO` exigen al menos uno en ese estado. Sin obligatorios, el primero incluye todas las entradas y los otros dos ninguna.
- Cada solicitud crea un artefacto para esa versión; el contenido se captura al generarse. Una entrada anonimizada usa el texto prescrito y omite correo, ubicación y evidencias personales.

## Contratos
- Extiende el `GET /api/v1/report-versions/{id}/candidates` de 013 con `minTotalScore=&maxTotalScore=&mandatoryCompliance=&hasWarnings=&hasInsufficientEvidence=&page=&size=` y respuesta paginada que conserva `humanStatus`/`humanStatusVersion`. `mandatoryCompliance` es `TODOS_CUMPLEN|ALGUNO_NO_CUMPLE|ALGUNO_NO_DEMOSTRADO`; booleanos aceptan sólo `true|false`.
- `POST /api/v1/report-versions/{id}/exports` recibe `{ "format": "PDF"|"XLSX" }`, responde `202 { exportId, statusUrl }`. Doble solicitud con la misma clave `Idempotency-Key` devuelve la misma operación.
- `GET /api/v1/exports/{exportId}` devuelve estado `QUEUED|GENERATING|COMPLETED|FAILED|EXPIRED`, expiración UTC y URL relativa de descarga sólo al completar. `GET /api/v1/exports/{exportId}/download` transmite bytes una vez autorizado.
- `422 INVALID_REPORT_FILTER` para rango inválido/valor desconocido; `409 REPORT_NOT_COMPLETED`; `404 EXPORT_NOT_AVAILABLE` para fallida, vencida o inexistente.

## Configuración centralizada
`reporting.export` en `application.yml` + `ReportExportProperties`: TTL de artefacto y límite de tamaño; no son parámetros ADMIN. La ruta privada usa el proveedor de almacenamiento de `document`, sin URL pública.

## Datos y persistencia
- Flyway: `report_export` (UUID, report_version_id, requester, format, estado, idempotency_key, timestamps, expires_at, referencia opaca al artefacto, fallo seguro) y constraint único `(requester_user_id, idempotency_key)` cuando la clave exista.
- El módulo `reporting` posee filtros, modelo permitido y exportación; usa un puerto de artefactos privados. No guarda CV, texto extraído, rutas, nombres de archivo ni PII en auditoría.
- Un worker durable reclama exportaciones con lease; falla sin archivo parcial. Purga artefactos vencidos y marca `EXPIRED`.

## Integraciones
Motor PDF/XLSX encapsulado por `reporting`; almacenamiento privado vía puerto. No se llama a Graph, Claude o SMTP.

## Errores y estados
- `FAILED` conserva sólo código seguro consultable por solicitante; no ofrece descarga. Artefacto corrupto/ausente se elimina y pasa a `FAILED`.
- `401`/`403` aplican a cada endpoint y la descarga valida que el export pertenece al reporte compartido, no sólo conocer su UUID.

## Seguridad y privacidad
- Sólo `RECRUITER`/`ADMIN`; `Cache-Control: no-store, private` en descarga. Excluir teléfono, dirección, atributos sensibles, estado humano, IDs, rutas y enlaces de CV.
- Auditar `REPORT_EXPORTED` sólo al completar/descargar con éxito según la política única de audit, con actor, UTC, formato y referencia de reporte.

## Observabilidad
Métricas de filtros, exportaciones por formato/estado/duración y purga; logs seguros con exportId/correlationId y códigos.

## Estrategia de pruebas
### Validación manual
- Aplicar cada filtro y combinaciones; validar que export PDF/XLSX contiene todas las entradas, no el subconjunto filtrado, y no contiene campos prohibidos.
- Probar `422`, reporte en progreso, idempotencia, error de generación, expiración, descarga y auditoría.
### Backlog de automatización diferida
- Unitarias de predicados; integración PostgreSQL de idempotencia/lease/TTL; inspección de contenido PDF/XLSX; API de roles y minimización; regresión de reporte inmutable.

## Criterios de aceptación
- AC-008-01 a AC-008-06 se cumplen. La disponibilidad inicial de toda exportación es `DESCONOCIDO` y no se modifica ninguna versión.

## Riesgos y dependencias
- Depende de snapshots de 012, estado de 013 y puertos de artefacto privado. 016 extiende contratos de filtro/exportación.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: las exportaciones son artefactos efímeros privados y durables; no son parte de `report_version` ni la mutan.
- `ARCHITECTURAL DECISION`: disponibilidad es `DESCONOCIDO` hasta que 016 habilite su lectura dinámica de perfil; no forma parte de `report_version`.

## Definition of Ready
`READY_FOR_DEV`
