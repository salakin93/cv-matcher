# 022 - Job Completion Notifications

## Objetivo

Notificar al solicitante de un job cuando termina, termina con advertencias o
falla, mediante bandeja in-app y correo seguro asíncrono, sin CVs ni resultados
sensibles en el mensaje.

## Alcance

- `notification` con outbox durable, deduplicación por job/evento/canal,
  notificación in-app, adaptador `MailGateway`, retries y estado leído.
- Eventos de job terminales y preferencias server-side de canal.

## Excluido

- Notificaciones de chat, campañas, UI React, contenido de CV/score/evidencia,
  SMTP real en test, envío a terceros o cambio de job/reporte.

## Persistencia/contrato

Crear sólo `V23__job_notifications.sql`: `notification` y `notification_outbox`
con UUID, receptor, tipo, estado, payload mínimo cifrado, dedupe key,
claim/lease y timestamps. API protegida: `GET /api/v1/notifications`,
`PATCH /api/v1/notifications/{id}/read`; nunca expone email de otros usuarios.

## Reglas

1. Al terminal efectivo del job, transacción escribe outbox; replay no duplica.
2. In-app contiene título/mensaje español seguro y URL autenticada de reporte.
3. Correo sólo dice que hay resultado disponible; no CV, score, candidatos,
   evidencias, links públicos ni detalles de error.
4. Worker respeta retries/backoff, no bloquea job y no pierde outbox por restart.

## Criterios de aceptación

1. Solicitante recibe una notificación única por terminal de job.
2. In-app/correo no filtran PII, CV, score o enlaces públicos.
3. Retries/lease/replay no duplican envío ni cambian job/reporte.
4. Usuario sólo lee/marca sus notificaciones; `401`/`403` seguros.
5. V23/Testcontainers y MailGateway fake cubren outbox, dedupe, fallos y redacción.

## Definition of Ready

`READY_FOR_DEV`
