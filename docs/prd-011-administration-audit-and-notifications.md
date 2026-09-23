# PRD - Operacion Administrativa, Auditoria y Notificaciones

## Estado

APROBADO_PARA_SPECS. Revision de arquitectura completada.

## Objetivo

Permitir operar parámetros seguros, revisar acciones sensibles y comunicar el
resultado de trabajos sin exponer secretos, CVs ni datos personales.

## Configuracion administrativa

- Solo `ADMIN` puede consultar o cambiar configuración operativa.
- Esta versión permite únicamente seleccionar el modelo de IA para análisis
  futuros y definir la concurrencia global de jobs.
- El modelo se elige de una lista permitida por el servidor. La concurrencia
  es un entero entre 1 y 10; el valor inicial es 1.
- El modelo queda en el snapshot de cada job al crearse; un cambio afecta sólo
  jobs creados después y no modifica reportes, análisis ni jobs ya creados.
- Un cambio de concurrencia no interrumpe jobs activos; se aplica a los próximos
  claims de workers, incluidos jobs ya en cola.
- La pantalla puede mostrar el estado de Outlook y Claude, pero nunca tokens,
  secretos, claves, payloads de proveedores ni configuración técnica sensible.
- Cada cambio efectivo de configuración se audita.

## Consulta de auditoria

- Solo `ADMIN` puede consultar auditoría inmutable y paginada.
- Puede filtrar por tipo de acción, usuario que realizó la acción y período UTC.
- Cada evento muestra fecha y hora UTC, tipo de acción, usuario responsable y
  referencia segura del recurso cuando sea necesaria para investigar la acción.
- La consulta no muestra CVs, texto extraído, correos de candidatos, teléfonos,
  direcciones, tokens, secretos, rutas de archivo ni payloads de proveedores.
- Incluye, cuando ocurran, cambios de estado humano, descargas, exportaciones,
  disponibilidad, correcciones de perfil, papelera, restauración, purga,
  eliminación por privacidad, configuración, usuarios, roles e integraciones.

## Notificaciones de trabajos

- El usuario que solicita un job recibe una notificación dentro de la aplicación
  y un correo para cada estado terminal: `COMPLETED`,
  `COMPLETED_WITH_WARNINGS`, `FAILED`, `CANCELLED` y
  `REAUTHORIZATION_REQUIRED`.
- La notificación identifica la vacante y el resultado general. No incluye CVs,
  scores detallados, datos de candidatos, enlaces públicos, secretos ni detalles
  técnicos de fallos.
- Para `REAUTHORIZATION_REQUIRED`, la notificación indica que un `ADMIN` debe
  actuar, sin detalles técnicos.
- `REAUTHORIZATION_REQUIRED` notifica al solicitante activo y a todos los
  `ADMIN`. Los avisos de `ADMIN` no incluyen datos del job ni del solicitante.
- Las notificaciones internas pueden marcarse como leídas. Marcar una
  notificación no modifica el job, reporte ni candidatos.
- El envío de correo fallido no revierte un job ya terminado; se intenta de
  acuerdo con el mecanismo durable de notificaciones.
- Cada destinatario recibe como máximo una notificación interna y un correo por
  estado terminal de un job, aunque existan replays o reintentos técnicos.
- Si el solicitante fue desactivado antes del estado terminal, no recibe correo
  ni notificación interna.

## Fuera de alcance

- Editar secretos, tokens, credenciales, rutas de almacenamiento o parámetros
  fuera de la lista permitida.
- Consultar auditoría como `RECRUITER`.
- Enviar correos manualmente, crear plantillas o notificar a candidatos.

## Criterios de aceptacion

### AC-011-01 - Configuracion segura

Cuando un administrador cambia un valor permitido, el cambio se aplica solo a
operaciones futuras y queda auditado; la interfaz no muestra secretos.

### AC-011-02 - Auditoria restringida

Cuando un administrador consulta auditoría con filtros, ve solo eventos mínimos
e inmutables. Un reclutador no puede consultar esa información.

### AC-011-03 - Notificacion segura

Cuando un job alcanza cualquiera de sus estados terminales definidos, su
solicitante recibe una notificación interna y un correo seguros. Para
`REAUTHORIZATION_REQUIRED`, el aviso indica que un `ADMIN` debe actuar sin
detalles técnicos. Un fallo de correo no cambia el resultado del job.

### Validacion manual

Con jobs sintéticos en `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`,
`CANCELLED` y `REAUTHORIZATION_REQUIRED`, verificar que el solicitante recibe
una notificación interna y un correo seguros por cada resultado. Para
`REAUTHORIZATION_REQUIRED`, verificar que el aviso sólo indica que un `ADMIN`
debe actuar y no contiene detalles técnicos.

## Definition of Ready

`READY_FOR_ARCHITECT`
