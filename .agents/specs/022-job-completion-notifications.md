# 022 - Job completion notifications

## Estado
`READY_FOR_DEV` — backend only; PRD 011; depends on 004, 012 and notification/outbox foundations from 001.

## Objetivo
Notificar de forma durable al solicitante cuando un job de reporte alcanza un estado terminal, sin afectar el resultado del job.

## Referencias
`docs/prd-011-administration-audit-and-notifications.md`, `docs/architecture.md` §§7–8.

## Alcance
### Incluido
- Notificación in-app y correo por cada transición terminal `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`, `CANCELLED` o `REAUTHORIZATION_REQUIRED` de job solicitado por usuario; en reautorización, también a todos los `ADMIN` activos.
- Listado paginado del propio usuario y marcado idempotente como leído; outbox de correo con reintentos.
### Excluido
- Notificar estados intermedios, correos manuales, plantillas editables, notificar candidatos o cambiar jobs.

## Comportamiento y reglas
- Al confirmar transición terminal, `job` publica un evento interno mínimo. `notification` crea exactamente una notificación in-app y un mensaje outbox por canal/resultado/job/destinatario mediante constraint de idempotencia.
- Texto español al solicitante activo identifica título de vacante y resultado general; para completados puede referir URL interna relativa del reporte, nunca enlace público. Para `REAUTHORIZATION_REQUIRED`, indica que un `ADMIN` debe actuar, sin detalles técnicos. Cada `ADMIN` activo recibe para ese estado un aviso genérico sin datos del job ni del solicitante. No incluye candidatos, scores, CVs, warning detallado, secretos ni fallo técnico.
- Marcar leída sólo cambia `readAt` de la notificación del actor y no el job/reporte. Fallo de SMTP no cambia job ni notificación in-app.
- Si el solicitante está desactivado al alcanzar el estado terminal, no recibe ningún canal; en `REAUTHORIZATION_REQUIRED` los `ADMIN` activos siguen recibiendo el aviso genérico.

## Contratos
- `GET /api/v1/notifications?page=&size=&unreadOnly=` devuelve sólo notificaciones del usuario autenticado: id, tipo, título/mensaje seguro, jobId/reporte relativo permitido, createdAt/readAt.
- `PUT /api/v1/notifications/{id}/read` es idempotente y devuelve la notificación. `404` para UUID ajeno/inexistente.
- No añadir endpoint de envío de correo; estado de entrega no se expone a recruiter.

## Configuración centralizada
Reutilizar `MailGateway` y configuración SMTP/outbox de `docs/architecture.md`; añadir plantillas fijas en el módulo `notification`, no en base de datos ni configurables por ADMIN.

## Datos y persistencia
- Flyway: completar `notification` con recipient, job no nulo, report_version nullable, kind, payload mínimo seguro, created/read UTC y constraint único por destinatario/job/tipo; los avisos ADMIN genéricos conservan la referencia interna al job sólo para deduplicación y nunca la exponen. Completar outbox con deduplication key por job/canal/destinatario.
- `job` no escribe tablas `notification`; publica contrato de aplicación post-commit. `notification` posee persistencia, formato y entrega vía `MailGateway`.

## Integraciones
SMTP TLS sólo a través de outbox durable; reintentos acotados existentes y sin rollback de negocio.

## Errores y estados
- Estados de entrega internos `PENDING|SENT|RETRYING|FAILED`; no se reintenta indefinidamente. Un error de formato/SMTP se registra seguro y alerta.
- `401` sin sesión; no permitir lectura/marcado cross-user.

## Seguridad y privacidad
- Toda API autenticada; payload minimizado y cache privado. Correo dirige a aplicación autenticada, sin tokens/links públicos.
- Logs/outbox no contienen CV, score, candidato, correo de terceros, secretos o excepción SMTP cruda.

## Observabilidad
Métricas de notificaciones creadas/leídas, outbox enviados/fallidos/reintentos y latencia; logs jobId/notificationId/correlationId.

## Estrategia de pruebas
### Validación manual
- Terminar job en `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`, `CANCELLED` y `REAUTHORIZATION_REQUIRED`, y verificar una notificación y correo seguro por solicitante activo. Para reautorización, verificar un aviso genérico para cada ADMIN activo sin datos del job/solicitante. Forzar SMTP fallido y confirmar job intacto.
- Listar/marcar leída con dos usuarios y comprobar aislamiento/idempotencia.
### Backlog de automatización diferida
- Integración de evento terminal/outbox deduplicado; API de aislamiento; dobles SMTP y retry; inspección de minimización de texto; recuperación tras reinicio.

## Criterios de aceptación
- AC-011-03 se cumple para los cinco estados terminales indicados; correo fallido no revierte job y marcar leído no cambia datos de negocio.

## Riesgos y dependencias
- Depende de solicitante persistido en 004, reporte de 012 y SMTP operativo. Alertas de correo requieren configuración de despliegue.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: `notification` consume evento terminal post-commit; el outbox es la única salida de correo y no pertenece al módulo `job`.

## Definition of Ready
`READY_FOR_DEV`
