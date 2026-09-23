# 004 - Jobs durables de reporte

## Objetivo
Crear, consultar, cancelar, reintentar y recuperar jobs de reporte sin esperar trabajo externo y preservando un snapshot de vacante.

## Referencias
- `docs/prd-002-vacancies-async-jobs.md`, secciones 5--10.
- `docs/architecture.md`, secciones 7, 9--11.
- Specs 001 y 003.

## Alcance
### Incluido
- Creacion `QUEUED`, snapshot, listado/detalle, cancelacion, retry manual, claim/lease y recuperacion durable.
### Excluido
- Graph, documentos, analisis, reporte, notificaciones de usuario, configuracion ADMIN de concurrencia y UI.

## Comportamiento y reglas
- Solo vacante `ACTIVE` sin job activo crea job. Activos: `QUEUED`, `DISCOVERING`, `INGESTING_DOCUMENTS`, `ANALYZING`; terminales: `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`, `REAUTHORIZATION_REQUIRED`, `CANCELLED`.
- Crear y retry capturan titulo, rango, requisitos/pesos/orden y umbral entero 0--100 (default 70) inmutables. Retry solo de `FAILED`/`REAUTHORIZATION_REQUIRED`, crea nuevo `QUEUED` con el snapshot y umbral anteriores; no tiene limite funcional. Un retry tras `REAUTHORIZATION_REQUIRED` exige que un `ADMIN` haya resuelto antes la conexion Outlook de su alcance.
- Cancelar activo es idempotente; worker se detiene seguro, conserva datos tecnicos ya persistidos y no publica reporte parcial. Claim/lease y checkpoints impiden doble proceso y recuperan leases vencidos sin duplicar documentos, conteos o transiciones finales.

## Contratos API
- `POST /api/v1/vacancies/{id}/report-jobs` responde `202` con `jobId`, snapshot/resumen y URL de estado.
- `GET /api/v1/vacancies/{id}/report-jobs?status=` admite filtro de estado; `GET /api/v1/report-jobs/{id}`, `POST /api/v1/report-jobs/{id}/cancel`, `POST /api/v1/report-jobs/{id}/retry`.
- Estado muestra intento, fechas, conteos agregados, advertencias y codigo seguro; nunca lease, snapshot sensible, IDs proveedor o PII.

## Configuracion centralizada
`JobProperties` en `application.yml` concentra concurrencia global (inicial 1), duracion/renovacion de lease y recuperacion. Al alcanzar la concurrencia, los jobs adicionales permanecen `QUEUED`. La futura API ADMIN de 011 modifica solo el valor permitido de concurrencia mediante este SSOT.

## Datos y persistencia
Flyway agrega `matching_job`, snapshot normalizado/serializado inmutable y `matching_job_event`; constraint parcial/estrategia equivalente garantiza un activo por vacante. Persistir claim, lease, intento y contadores en transacciones cortas.

## Integraciones
No llama Graph/Claude ni SMTP. Expone los puntos de transicion para los workers de 006--012.

## Errores y estados
Carrera de creacion/retry con job activo retorna `409`. Vacante archivada/inexistente devuelve `409`/`404`. Transiciones invalidas no alteran historial; terminales no se reclaman automaticamente. Cancelar un terminal no altera su historial, fechas ni auditoria.

## Seguridad y privacidad
Solo roles activos autorizados para vacantes compartidas. Snapshots y eventos no incluyen CV, texto, correo, tokens o datos de proveedor.

## Observabilidad
Metricas de backlog, estados, lease vencido, reintentos y duracion; logs seguros con `jobId`/correlation ID. Auditoria de crear, cancelar y retry efectivos.

## Estrategia de pruebas
### Validacion manual
Crear job, observar `202`, carrera de dos solicitudes, snapshot tras editar/archivar, cancelacion idempotente, retry permitido/no permitido y recuperacion simulada de lease.
### Automatizacion diferida
Integracion PostgreSQL/Testcontainers de constraint, claim concurrente y recuperacion; API de estados/autorizacion y pruebas de transicion/idempotencia.

## Criterios de aceptacion
1. HTTP no espera procesamiento externo y cada job inicia `QUEUED` con snapshot.
2. Una vacante tiene como maximo un job activo incluso bajo carrera.
3. Cancelar no publica reporte parcial; retry solo crea nuevo job desde estados permitidos.
4. Reinicio o lease vencido no duplica conteos ni trabajo final.
5. Estado expone solo informacion segura.

## Riesgos y dependencias
Depende de 003 y PostgreSQL. Los workers posteriores deben honrar el contrato de lease y cancelacion.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: `matching_job` es fuente durable de verdad; no usar tareas en memoria.

## Definition of Ready
`READY_FOR_DEV`.
