# 006 - Descubrimiento Outlook Inbox

## Objetivo
Descubrir de forma idempotente los mensajes Inbox elegibles dentro del rango UTC snapshot de un job, sin leer contenido de correo.

## Referencias
- `docs/prd-003-outlook-message-discovery.md`, secciones 4--10.
- `docs/prd-002-vacancies-async-jobs.md`, secciones 4, 6 y 10.
- `docs/architecture.md`, secciones 7, 8.1 y 11.
- Specs 004 y 005.

## Alcance
### Incluido
- Consulta paginada de Inbox, rango UTC inclusivo, campos minimos, referencias inmutables, conteos, retry y cancelacion cooperativa.
### Excluido
- Cuerpo, asunto, vista previa, destinatarios, adjuntos y su descarga (007); UI y ranking.

## Comportamiento y reglas
- Solo worker con job `DISCOVERING` consulta `Inbox` y el rango persistido. Cada llamada Graph relevante usa `Prefer: IdType="ImmutableId"`.
- Descubre solo referencia interna inmutable, fecha de recepcion e indicador de adjuntos; no lee ni guarda remitente, cuerpo, vista previa, asunto, destinatarios, categorias, contenido ni adjuntos.
- Pagina hasta final/rango o limite de 1000 mensajes por job, priorizando los mas recientes dentro del rango. Replays, paginas repetidas y recuperacion no duplican mensaje ni conteos. Al cancelar, deja de consultar tan pronto sea seguro.
- `429` respeta `Retry-After`; red/transitorio reintenta maximo tres veces. Permiso/consentimiento invalido pasa job y conexion a reautorizacion sin retry automatico.

## Contratos API
No agrega endpoint publico. El detalle de 004 incorpora conteo `messagesDiscovered`, advertencia de alcance parcial y codigos seguros. Las referencias y sender no aparecen en API, exportacion, auditoria, log o metrica.

## Configuracion centralizada
`OutlookProperties` centraliza timeout/retry del cliente. `JobProperties` aloja `maxMessagesPerJob=1000` como configuracion externa por entorno.

## Datos y persistencia
Flyway agrega registro de mensaje descubierto vinculado a job, referencia Graph cifrada/protegida, fecha y presencia de adjuntos; constraint de idempotencia por job/referencia inmutable. El remitente no se consulta ni persiste durante discovery.

## Integraciones
Usa exclusivamente el adaptador Graph de 005 y su conexion `CONNECTED`; no llama endpoints de adjuntos ni persiste payloads Graph.

## Errores y estados
Conexion `NOT_CONNECTED`/`ERROR` produce `FAILED` seguro; credenciales revocadas producen `REAUTHORIZATION_REQUIRED`. Al agotar transitorios, `FAILED` con codigo seguro. Cero CVs validos se decide despues de 007, no aqui.

## Seguridad y privacidad
Minimizacion estricta de campos. Las referencias son datos internos protegidos y no se envian a Claude; el remitente se obtiene, si hiciera falta, solo en la etapa posterior de identidad para un CV disponible. Los controles globales de secretos y logs remiten a arquitectura.

## Observabilidad
Metricas de paginas, mensajes, reintentos, limites y fallos Graph sin IDs ni correos; eventos de job minimos con correlation ID.

## Estrategia de pruebas
### Validacion manual
Con doble Graph y rango UTC fixture, verificar Inbox/campos minimos, paginacion/replay, 429, revocacion, cancelacion y advertencia de limite.
### Automatizacion diferida
Integracion con doble Graph para ImmutableId, query de rango, deduplicacion y retry; PostgreSQL/Testcontainers para idempotencia y lease.

## Criterios de aceptacion
1. Discovery solo lee Inbox y el rango UTC snapshot.
2. No lee ni expone cuerpo, asunto, destinatarios o referencias Outlook.
3. Discovery no consulta ni conserva remitente; una etapa posterior puede obtenerlo solo como fallback de identidad de un CV disponible.
4. Retry, paginacion y recuperacion no duplican conteos.
5. Reautorizacion requerida no se reintenta automaticamente.

## Riesgos y dependencias
Depende de 004--005 y de que 007 decida documentos validos.

## Decisiones / preguntas abiertas
- Ninguna pregunta abierta bloqueante.

## Definition of Ready
`READY_FOR_DEV`
