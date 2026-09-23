# PRD - Outlook e Identificacion de Mensajes

## Estado

APROBADO_PARA_SPECS. Revision de arquitectura completada. Este documento define
comportamiento de producto; no define arquitectura ni implementacion tecnica.

## Objetivo

Usar una unica cuenta Outlook compartida para encontrar mensajes del Inbox

## 1. Conexion Outlook Compartida

- Existe una sola conexion Outlook para toda la organizacion.
- Solo un `ADMIN` puede consultar estado, conectar o reautorizar Outlook.
- Un `RECLUTADOR` no puede conectar Outlook ni ver su estado tecnico.
- No existe conexion por reclutador ni multiples buzones en esta version.

### Estados visibles para ADMIN

| Estado | Significado |
| --- | --- |
| `NOT_CONNECTED` | Outlook aun no fue configurado. |
| `CONNECTED` | La conexion puede ser usada por jobs. |
| `REAUTHORIZATION_REQUIRED` | Microsoft revoco permisos, token o consentimiento; un ADMIN debe autorizar de nuevo. |
| `ERROR` | Existe un problema tecnico seguro que impide usar Outlook. |

El estado puede mostrar fecha de conexion, ultima renovacion y codigo seguro.
Nunca muestra correo del buzon, tokens, secretos, tenant, identidad Microsoft ni
mensajes de proveedor.

## 2. Autorizar y Reautorizar

- Un `ADMIN` inicia la autorizacion.
- El sistema dirige al administrador a Microsoft.
- Microsoft devuelve el resultado al backend.
- El backend valida el resultado y actualiza la conexion compartida.
- Solo puede existir una autorizacion pendiente para toda la organizacion.
- Un nuevo inicio de autorizacion reemplaza cualquier intento pendiente anterior.

| Caso | Resultado |
| --- | --- |
| Outlook no conectado y autorizacion exitosa | Pasa a `CONNECTED`. |
| Outlook conectado y nueva autorizacion exitosa | Reemplaza la conexion anterior. |
| Outlook conectado y autorizacion cancelada o fallida | La conexion anterior continua disponible. |
| Outlook no conectado y autorizacion cancelada o fallida | Permanece `NOT_CONNECTED` o muestra error seguro. |
| Permiso revocado o token invalido | Pasa a `REAUTHORIZATION_REQUIRED`. |
| Error tecnico no recuperable | Pasa a `ERROR`. |
| Usuario no ADMIN intenta administrar Outlook | Acceso denegado. |

## 3. Tokens y Secretos

- Tokens, secretos y claves de cifrado permanecen exclusivamente en servidor.
- El sistema cifra los tokens necesarios para renovar acceso.
- Ninguna API, pantalla, auditoria, metrica o log muestra tokens, secretos,
  codigos OAuth, enlaces completos de autorizacion o payloads Microsoft.
- Los modulos de jobs y documentos pueden usar Outlook, pero no conocen ni
  descifran credenciales.

## 4. Uso de Outlook por Jobs

- Un job solo consulta la carpeta `Inbox`.
- El job solo consulta el rango de fechas guardado en su snapshot.
- El usuario no puede elegir otra carpeta, cambiar filtros Outlook ni enviar
  parametros Graph.
- Las fechas se usan como rango UTC derivado de la vacante.
- Cada job conserva su rango original aunque la vacante sea editada despues.

| Caso | Resultado |
| --- | --- |
| Outlook conectado y permisos validos | El job puede descubrir mensajes. |
| Outlook no conectado o en `ERROR` | El job falla con codigo seguro. |
| Outlook requiere reautorizacion | El job pasa a `REAUTHORIZATION_REQUIRED`; no reintenta automaticamente. |
| ADMIN resuelve Outlook | El reclutador puede reintentar el job fallido o de reautorizacion. |

## 5. Identificacion de Mensajes

El sistema descubre unicamente la informacion minima necesaria:

- Referencia interna inmutable del mensaje.
- Fecha de recepcion.
- Indicador de si tiene adjuntos.
- Correo del remitente, solo para un mensaje con adjuntos y unicamente como
  segunda clave interna de deduplicacion si el CV no contiene correo. Se cifra,
  no se muestra ni se envia a Claude, y se elimina con los datos del documento.

El sistema no lee ni conserva:

- Cuerpo o vista previa.
- Asunto.
- Nombre visible del remitente.
- Destinatarios.
- Categorias.
- Contenido de correo.
- Adjuntos en esta fase.

El remitente no se lee durante discovery. Una etapa posterior de identidad puede
obtenerlo exclusivamente como fallback para un CV disponible, bajo los controles
de privacidad definidos en `prd-005-extraction-ai-scoring-ranking.md`.

Las referencias Outlook son internas. No aparecen en APIs, pantallas,
exportaciones, auditoria, logs ni metricas.

## 6. Mensajes sin Adjuntos o sin CV Valido

- Todos los mensajes dentro del rango pueden contabilizarse como descubiertos.
- Solo los mensajes con adjuntos pasan a la etapa de documentos.
- Si el rango no tiene CVs validos disponibles, el job termina en `FAILED` con
  `NO_VALID_CV_DOCUMENTS`.
- No se crea reporte vacio ni candidatos.

## 7. Paginacion y Limites

- El sistema consulta mensajes por paginas hasta terminar el rango.
- Usa referencias inmutables para que mover un correo no rompa el procesamiento.
- El sistema aplica un limite operativo de 1000 mensajes por job.
- Si se alcanza ese limite, procesa el subconjunto seguro descubierto y el
  resultado final debe indicar advertencia de alcance parcial.
- El reclutador puede crear otro job con un rango mas acotado.

## 8. Reintentos y Retry-After

- Si Microsoft limita solicitudes, el sistema espera el tiempo indicado antes de
  reintentar.
- Errores temporales de red o Microsoft se reintentan automaticamente hasta tres
  veces.
- Si se agotan los reintentos, el job falla con un codigo seguro.
- No se reintentan automaticamente errores de permisos, revocacion o
  consentimiento retirado.
- Un reclutador puede reintentar manualmente un job terminal permitido despues de
  corregir la causa.

## 9. Leases, Recuperacion e Idempotencia

- Solo un worker procesa un job al mismo tiempo.
- Si un worker cae o deja de responder, otro puede continuar despues de que venza
  su lease.
- Reinicio, reintento, paginacion repetida o recuperacion no duplican mensajes
  descubiertos ni conteos.
- Si un job se cancela, el worker deja de consultar Outlook tan pronto como sea
  seguro.
- Los mensajes ya registrados no se eliminan automaticamente por la cancelacion.

## 10. Estado del Job

El reclutador puede ver informacion segura:

- Estado actual.
- Fecha de inicio y finalizacion.
- Cantidad de mensajes descubiertos.
- Conteos de documentos posteriores.
- Advertencias de alcance parcial.
- Codigo seguro de error o reautorizacion requerida.

El reclutador nunca ve datos de correos, referencias Outlook, tokens, URLs Graph
ni detalles tecnicos internos.

## Criterios de Aceptacion

1. Solo un `ADMIN` puede conectar o reautorizar la unica conexion Outlook.
2. Una reautorizacion conserva la conexion previa hasta completar una nueva
   autorizacion exitosa.
3. Los secretos y tokens nunca se muestran ni salen del servidor.
4. Cada job consulta solo Inbox y su rango UTC snapshot.
5. El discovery no lee ni expone cuerpo, asunto, remitente o destinatarios.
6. Los reintentos, leases y paginacion no duplican mensajes ni conteos.
7. Revocacion de permisos exige reautorizacion administrativa y no se reintenta
   automaticamente.
8. Sin CV valido disponible, el job falla con `NO_VALID_CV_DOCUMENTS`.
9. El estado de job expone solo conteos, advertencias y codigos seguros.
