# 005 - Conexión Outlook compartida

## Objetivo
Administrar una unica conexion Outlook organizacional con OAuth seguro para que jobs posteriores puedan leer Inbox.

## Referencias
- `docs/prd-003-outlook-message-discovery.md`, secciones 1--4.
- `docs/architecture.md`, secciones 5, 8.1, 9--11.
- Specs 001 y 004.

## Alcance
### Incluido
- Consulta ADMIN de estado, inicio OAuth, callback, reautorizacion, reemplazo atomico de tokens y auditoria.
### Excluido
- Descubrir mensajes/adjuntos (006+), UI y multiples buzones/conexiones por usuario.

## Comportamiento y reglas
- Estados visibles: `NOT_CONNECTED`, `CONNECTED`, `REAUTHORIZATION_REQUIRED`, `ERROR`. `ERROR` es visible para `ADMIN` con codigo seguro.
- Solo existe una autorizacion pendiente; un inicio nuevo reemplaza el intento previo. Reautorizacion fallida/cancelada conserva una conexion previa util.
- OAuth Authorization Code + PKCE con callback backend confidencial solicita `openid`, `profile`, `offline_access` y `Mail.Read`. Valida `state`, nonce y `id_token` por discovery/JWKS; no expone valores OAuth al navegador.
- Revocacion/token invalido pasa a `REAUTHORIZATION_REQUIRED`; fallo tecnico no recuperable a `ERROR`.
- Un `RECRUITER` no puede consultar estado tecnico ni iniciar autorizacion. Los jobs consumen operaciones del modulo `outlook`; no reciben ni descifran tokens o secretos.

## Contratos API
- `GET /api/v1/admin/outlook-connection` devuelve estado, fechas y codigo seguro exclusivamente a `ADMIN`.
- `POST /api/v1/admin/outlook-connection/authorization` inicia redireccion; `GET /api/v1/oauth/microsoft/callback` procesa resultado server-side.
- Rechazar no ADMIN con `403`; callback invalido/caducado responde pagina/resultado seguro sin secretos.

## Configuracion centralizada
`OutlookProperties` en `application.yml`: tenant, client ID, redirect URI, endpoints/timeout y version de clave. Client secret, cifrado de token y demas secretos solo por entorno, segun arquitectura seccion 10.

## Datos y persistencia
Flyway agrega el registro singleton de conexion y estado OAuth pendiente. Refresh/access tokens se cifran, refresh rotado reemplaza al anterior atomicamente; no persistir codigo de autorizacion ni URL completa.

## Integraciones
Cliente Microsoft centralizado del modulo `outlook`; OAuth/OIDC discovery y token endpoint. No permitir a otros modulos descifrar credenciales.

## Errores y estados
Cancelacion/fallo inicial conserva `NOT_CONNECTED` o establece `ERROR` seguro; fallo de reautorizacion no destruye conexion previa. Errores de permisos conocidos actualizan `REAUTHORIZATION_REQUIRED`.

## Seguridad y privacidad
Solo `ADMIN`; PKCE/state/nonce, validacion de issuer/audience/firma. No logs, API, auditoria ni metricas con correo del buzon, tenant, tokens, secretos, codigo OAuth o payload Microsoft.

## Observabilidad
Metricas de autorizacion/renovacion/fallos por codigo seguro y alerta de reautorizacion; auditoria de inicio/resultado efectivo sin datos OAuth.

## Estrategia de pruebas
### Validacion manual
Con tenant y cuenta de prueba, validar inicio/callback, `Mail.Read`, reemplazo de autorizacion pendiente, fallo que conserva conexion previa, revocacion y acceso `ADMIN`.
### Automatizacion diferida
Dobles OIDC/Graph para state, PKCE, JWKS, rotacion atomica y mapeo de estados; API de rol y pruebas de no filtracion.

## Criterios de aceptacion
1. Solo `ADMIN` administra una unica conexion compartida.
2. Autorizacion inicial solicita `Mail.Read` y no entrega tokens al browser.
3. Una reautorizacion fallida conserva conexion previa operable.
4. Revocacion llega a `REAUTHORIZATION_REQUIRED` y error tecnico a `ERROR` visible.
5. Ninguna salida expone secretos o identidad Microsoft.

## Riesgos y dependencias
Depende de registro Entra, redirect URI, consentimiento y secretos de entorno. Depende de 001 para roles; 006 consume el puerto de conexion.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: la conexion es singleton organizacional, server-side y no es una preferencia de usuario.

## Definition of Ready
`READY_FOR_DEV`.
