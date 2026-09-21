# 023 - Allowed admin configuration

## Estado
`DRAFT_FOR_APPROVAL` — backend only; PRD 011; depends on 004, 005 and 009.

## Objetivo
Permitir a ADMIN consultar estado seguro de integraciones y cambiar sólo modelo AI allowlisted y concurrencia global futura.

## Referencias
`docs/prd-011-administration-audit-and-notifications.md`, `docs/architecture.md` §§8, 10.

## Alcance
### Incluido
- Lectura ADMIN de salud segura Outlook/Claude, lista server-side de modelos permitidos y configuración actual.
- Cambio versionado/auditado de modelo futuro y concurrencia global entera `1..10`, default `1`, aplicable solo a jobs creados despues del cambio.
### Excluido
- Editar secretos, tokens, tenant, rutas, timeout, límites de documento, SMTP, políticas de acceso o jobs ya creados, incluidos los que permanezcan en cola.

## Comportamiento y reglas
- El modelo debe pertenecer a la allowlist derivada de configuración server-side. Un cambio afecta sólo jobs creados después de commit; cada job captura su modelo y límite de concurrencia efectivos al crearse.
- La concurrencia es un entero inclusivo 1–10, default 1. Un cambio aplica sólo a jobs creados después de su commit; jobs ya creados, incluso en cola, conservan el límite capturado al crearse.
- Health sólo devuelve estado `CONNECTED|NOT_CONNECTED|REAUTHORIZATION_REQUIRED|UNAVAILABLE` y fecha de comprobación; nunca configuración técnica o proveedor payload.

## Contratos
- `GET /api/v1/admin/operational-configuration` devuelve `{ analysisModel, allowedAnalysisModels, globalJobConcurrency, version, integrations }` con estados seguros.
- `PUT /api/v1/admin/operational-configuration` recibe `{ analysisModel, globalJobConcurrency, expectedVersion }`; ambos valores son requeridos y se actualizan atómicamente. Devuelve configuración/version nueva.
- `422 ANALYSIS_MODEL_NOT_ALLOWED|INVALID_JOB_CONCURRENCY`; `409 VERSION_CONFLICT`; `401/403` normalizados.

## Configuración centralizada
- `administration.operational.allowed-analysis-models` y default de concurrencia viven en `application.yml` + `OperationalConfigurationProperties`; la allowlist no se edita desde API.
- Valor elegido persiste en `system_operational_configuration` y es leído por `job`/`analysis` mediante puerto `administration`; no duplicar propiedades en workers.

## Datos y persistencia
- Flyway: singleton `system_operational_configuration` con modelo, concurrencia, `version`, timestamps/actor; insertar fila inicial con concurrencia `1` y modelo válido de allowlist. Constraint `global_job_concurrency between 1 and 10`.
- `administration` posee mutación; `job` captura el límite efectivo al crearse y `analysis` recibe el modelo snapshot desde job. Auditar `OPERATIONAL_CONFIGURATION_CHANGED` en misma transacción con cambios permitidos, no secretos.

## Integraciones
Usar puertos Outlook/analysis sólo para health redactada; no realizar OAuth ni llamadas Claude de prueba al consultar.

## Errores y estados
- Allowlist vacía/configuración inválida es fallo de startup/readiness, no una opción editable. Health no disponible retorna estado seguro, no stack trace.
- Conflicto optimista requiere recarga; actualización parcial es rechazada para evitar valores no intencionales.

## Seguridad y privacidad
- Sólo ADMIN; no registrar valores de secreto ni configuración sensible. Actuator/health siguen protegidos según arquitectura.

## Observabilidad
Contadores de cambios/conflictos, gauge de concurrencia configurada y logs con actor/correlationId/version, no modelo secreto (el identificador allowlisted puede estar en auditoría).

## Estrategia de pruebas
### Validación manual
- Con ADMIN, cambiar modelo permitido y concurrencia 1, 10; confirmar que sólo jobs creados después del cambio toman los valores nuevos y que jobs ya creados, incluso en cola, no cambian. Probar 0, 11, modelo ajeno, conflicto y recruiter.
- Verificar que health no muestra tokens/secrets y existe auditoría.
### Backlog de automatización diferida
- Migración/default/constraint; API RBAC/versión/allowlist; integración de claim concurrente y snapshot de modelo; prueba de redacción de health/auditoría.

## Criterios de aceptación
- AC-011-01 se cumple. Concurrencia es siempre entero 1–10, default 1, y los únicos valores mutables son los explícitamente permitidos.

## Riesgos y dependencias
- Depende de puertos de job/Outlook/analysis y de modelo snapshot en 009. Cambiar allowlist requiere despliegue/configuración, no UI.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: concurrencia global es una sola configuración persistida, versionada y allowlisted; no una propiedad por worker ni parámetro de request.

## Definition of Ready
`READY_FOR_DEV`
