# 018 - Historical search job and combined version

## Estado
`READY_FOR_DEV` — backend only; PRD 009; depends on 004, 009–012, 016 and 017.

## Objetivo
Ejecutar una confirmación histórica como job durable `HISTORICAL_SEARCH` y, sólo con resultados que alcanzan el mínimo, publicar una versión combinada e inmutable con linaje.

## Referencias
`docs/prd-009-shared-profiles-and-historical-search.md`, `docs/architecture.md` §§4, 7.

## Alcance
### Incluido
- Consumo único de confirmación, job histórico, selección de máximo 500 CVs recientes elegibles, análisis y publicación de una versión combinada.
- Deduplicación contra origen y resultados históricos, advertencia segura de alcance parcial y predecessor explícito.
### Excluido
- Reanalizar/modificar la versión origen, búsqueda automática, más de 500 CVs, perfil/identidad manual o inclusión de papelera/privacidad.

## Comportamiento y reglas
- `POST` consume una confirmación `CONFIRMED` una vez y crea `matching_job` de tipo `HISTORICAL_SEARCH`; respeta un único job activo por vacante junto a jobs regulares.
- Al iniciar, revalidar elegibilidad y seleccionar por recepción descendente los primeros 500 CVs elegibles; si hay más, añadir advertencia segura. Usar snapshot de requisitos/umbral/filtros de confirmación.
- Analizar con pipeline 009–010. Retener sólo históricos con `totalScore >= mínimo confirmado`. Si ninguno, terminar con advertencia segura y no crear versión.
- Si hay resultados, combinar entradas origen e históricas, deduplicar sólo por correo CV y luego correo remitente protegido, conservando el CV disponible más reciente. Sin correo, cada documento permanece anónimo independiente. Recalcular ranking determinista y publicar una única versión con `predecessor_report_version_id=origen`. Origen nunca cambia.

## Contratos
- `POST /api/v1/historical-search-confirmations/{confirmationId}/jobs` responde `202 { jobId, statusUrl, sourceReportVersionId }`; `409 CONFIRMATION_ALREADY_CONSUMED|VACANCY_JOB_ACTIVE|HISTORICAL_SEARCH_NOT_ELIGIBLE`.
- Reutilizar `GET /api/v1/report-jobs/{id}` y extender DTO con `jobType`, `sourceReportVersionId`, `resultReportVersionId?`; no exponer selección/documentos.
- Nueva versión expone `predecessorReportVersionId` y `versionKind: HISTORICAL_COMBINED`; `GET` del origen conserva `versionKind: INITIAL`.

## Configuración centralizada
`historical-search.max-eligible-documents=500` en `application.yml` + `HistoricalSearchProperties`, validado como máximo duro 500; no ADMIN. Los límites de Claude/documento existentes siguen aplicando.

## Datos y persistencia
- Flyway: ampliar `matching_job` con `job_type`, `source_report_version_id`, `historical_confirmation_id` único; ampliar `report_version` con `version_kind`, `predecessor_report_version_id` FK. Constraints aseguran un predecessor sólo para `HISTORICAL_COMBINED` y una confirmación por job.
- `job` posee claim, lease y estado; llama puertos `candidate` para selección, `analysis` para evaluación y `reporting` para publicación. `reporting` es único escritor de versiones; no consulta tablas de `candidate`.
- Guardar checkpoint sólo con IDs/counters/códigos; marcar confirmación `CONSUMED` en la misma transacción de creación del job. Recuperación no duplica análisis ni versión.

## Integraciones
Usa Claude por el puerto `analysis`; no consulta Outlook. Timeouts/retries son los ya definidos para Claude y el job conserva errores seguros.

## Errores y estados
- `FAILED` no cambia origen ni crea versión. `COMPLETED_WITH_WARNINGS` cubre límite 500 o ninguna coincidencia; sólo tiene `resultReportVersionId` si publicó versión.
- Privacidad/papelera durante ejecución excluye el documento antes de análisis/publicación; no reintroduce acceso a información eliminada.

## Seguridad y privacidad
- `RECRUITER`/`ADMIN` sólo puede crear desde confirmación de una versión visible. Resultados usan contratos de reporte existentes; no revelar CVs descartados ni filtros sensibles a usuarios no autorizados.
- Auditar creación y publicación con referencias internas, actor y UTC; no CV/texto/PII.

## Observabilidad
Métricas por tipo job, selección, límite alcanzado, candidatos analizados/publicados y duración; logs seguros con jobId, sourceVersionId, correlationId.

## Estrategia de pruebas
### Validación manual
- Crear confirmación válida, iniciar job, observar estado y verificar máximo 500 recientes, warning y una sola versión combinada con predecessor.
- Probar job activo de misma vacante, confirmación reutilizada, cero resultados, fallo AI y documento enviado a papelera durante job.
### Backlog de automatización diferida
- PostgreSQL de claim/consume/idempotencia; selección ordenada/límite/exclusiones; deduplicación y linaje; recuperación de lease; API de autorización y regresión de inmutabilidad.

## Criterios de aceptación
- AC-009-03 y AC-009-04 se cumplen. Toda búsqueda es `HISTORICAL_SEARCH`, procesa como máximo 500 elegibles recientes y publica como máximo una versión combinada inmutable con predecessor.

## Riesgos y dependencias
- Requiere pipeline de análisis confiable y contratos extensibles de job/reporting. Una eliminación por privacidad de 021 debe prevalecer sobre cualquier checkpoint.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: histórico no es un retry del job inicial; es `HISTORICAL_SEARCH` distinto, con una única versión combinada y linaje inmutable.

## Definition of Ready
`READY_FOR_DEV`
