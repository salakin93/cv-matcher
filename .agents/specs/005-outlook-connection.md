# 005 - Outlook Shared Connection

## Objetivo

Entregar la integración backend segura de una única cuenta Outlook compartida:
un `ADMIN` puede consultar su estado e iniciar o renovar una autorización OAuth
2.0. El backend intercambia el código, cifra y rota el refresh token, y ofrece
un puerto interno para que futuros workers obtengan access tokens. Este
incremento no lee mensajes, adjuntos ni documentos.

## Referencias

- `docs/PRD.md`, secciones 4, 5 y 9.
- `docs/PRODUCT_BACKLOG.md`, Epic 3, Feature 3.1.
- `docs/architecture.md`, secciones 4, 5, 6, 7, 8, 9, 10 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 001–004.

## Alcance

### Incluido

- Módulo backend `outlook` para una sola conexión compartida.
- Consulta administrativa de estado seguro de conexión.
- Inicio de OAuth 2.0 Authorization Code con PKCE y callback backend.
- Validación de `state` de un solo uso, con vencimiento, ligado al administrador
  que inició la autorización.
- Intercambio server-side de código, persistencia cifrada de refresh token y
  reemplazo atómico cuando Microsoft entrega un token rotado.
- Puerto interno para emitir access tokens de corta duración a módulos futuros,
  sin exponer refresh tokens fuera de `outlook`.
- Auditoría, métricas, OpenAPI, errores seguros, configuración por entorno y
  pruebas con dobles HTTP de Microsoft.

### Excluido

- Lectura de Inbox, consultas Graph de mensajes, adjuntos, cuerpos, remitentes
  o asuntos; descarga y procesamiento de documentos.
- Ejecución de jobs, cambio de estados de `matching_job`, candidatos, reportes,
  ranking, Claude, notificaciones y UI React.
- Alta de múltiples buzones, delegación por reclutador, administración de
  secretos desde UI, OAuth device-code, SSO y permisos de escritura de correo.
- Persistir access tokens, payloads Graph, códigos de autorización o tokens en
  logs, errores, auditoría, métricas o respuestas HTTP.

## Decisiones arquitectónicas

1. `outlook` es dueño de credenciales y estados de integración. `job`,
   `document` u otros módulos sólo dependen de un puerto como
   `OutlookAccessTokenPort`; no acceden a tablas ni descifran refresh tokens.
2. Se admite una sola conexión de organización. Toda operación administrativa
   se serializa con bloqueo transaccional de PostgreSQL para no perder un refresh
   token rotado ni aceptar dos callbacks simultáneos.
3. Se usa Authorization Code + PKCE con callback backend. El navegador recibe
   únicamente la URL de autorización; `state`, `code_verifier` y código no se
   entregan a la SPA ni se escriben en logs.
4. El refresh token se cifra con AES-GCM usando una clave de 256 bits obtenida
   exclusivamente de `MICROSOFT_TOKEN_ENCRYPTION_KEY`. Su versión proviene de
   `MICROSOFT_TOKEN_ENCRYPTION_KEY_VERSION`, entero positivo obligatorio en
   `prod` y valor ficticio `1` en `test`. La clave nunca se guarda en BD, se
   devuelve por API ni aparece en configuración de prueba real.
5. La conexión no autoriza lectura por sí misma. Los módulos futuros deberán
   declarar explícitamente el mínimo permiso Graph y rango Inbox; este incremento
   solicita sólo `openid`, `profile` y `offline_access`. El backend valida el
   `id_token` mediante OIDC discovery/JWKS de Entra (firma, `issuer`, audiencia,
   expiración, tenant y `nonce`) y persiste exclusivamente el claim `sub` como
   `account_subject`; no llama a Microsoft Graph.

## Modelo y persistencia

Crear exclusivamente la migración inmutable `V6__outlook_connection.sql`; no
modificar V1–V5.

### `outlook_connection`

Tabla singleton con PK constante `id = 1`.

| Columna | Regla |
| --- | --- |
| `status` | `NOT_CONNECTED`, `CONNECTED`, `REAUTHORIZATION_REQUIRED` o `ERROR`. |
| `tenant_id` | Identificador no secreto; nullable hasta conectar. |
| `account_subject` | `sub`/identidad técnica del token, no correo; nullable. |
| `granted_scopes` | Lista normalizada de scopes otorgados, sin tokens. |
| `refresh_token_ciphertext` | Bytes AES-GCM; nullable fuera de `CONNECTED`. |
| `refresh_token_key_version` | Entero positivo no nulo, tomado de `MICROSOFT_TOKEN_ENCRYPTION_KEY_VERSION` al cifrar. |
| `connected_at`, `last_token_refresh_at`, `updated_at` | `timestamptz` UTC. |
| `last_error_code` | Código seguro, máximo 80; nunca mensaje Graph. |
| `version` | `bigint` para actualización optimista interna. |

La tabla tiene constraints de coherencia: `CONNECTED` exige ciphertext,
`REAUTHORIZATION_REQUIRED` no puede exponer credenciales y `version` inicia en
0. El ciphertext incluye nonce y tag; no se persiste refresh token en claro. La
migración inserta la única fila `id=1` con `NOT_CONNECTED` mediante operación
idempotente. `granted_scopes` se guarda como `text[]` no nulo, sin vacíos,
deduplicado y ordenado canónicamente. `ERROR` representa un fallo técnico o
criptográfico sin credencial utilizable; conserva ciphertext para recuperación
operativa y sólo expone un código seguro.

### `outlook_authorization_attempt`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `state_hash` | SHA-256 del state aleatorio; único, nunca state en claro. |
| `code_verifier_ciphertext` | Verifier PKCE cifrado; se elimina al consumirse. |
| `initiated_by_user_id` | UUID del ADMIN actor. |
| `expires_at`, `created_at`, `consumed_at` | `timestamptz` UTC. |

`state` tiene al menos 256 bits de entropía, expira en 10 minutos y sólo puede
consumirse una vez. Limpiar intentos vencidos mediante operación segura al crear
o consumir; no se requiere scheduler en este incremento.

## Contrato API

Las rutas administrativas requieren JWT válido, sesión persistida y rol efectivo
`ADMIN`, excepto el callback OAuth, que se autentica exclusivamente mediante
`state` de un solo uso y PKCE.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| `GET /api/v1/admin/integrations/outlook` | — | `200 OutlookConnectionStatus`. |
| `POST /api/v1/admin/integrations/outlook/authorization` | `{}` | `200 AuthorizationStart`. |
| `GET /api/v1/admin/integrations/outlook/callback` | `code`, `state`, `error?` | `302` a `${APP_BASE_URL}/admin/integrations/outlook/callback` con sólo `result=connected`, `result=denied` o `result=error`; state inválido, vencido o consumido responde JSON seguro `400`. |

`AuthorizationStart` devuelve sólo `authorizationUrl` HTTPS, `expiresAt` y un
estado público `PENDING_AUTHORIZATION`. No devuelve state separado, verifier,
client secret, token, correo de buzón ni mensajes de proveedor. Los POST con
campos desconocidos devuelven `422 VALIDATION_ERROR`.

`OutlookConnectionStatus` expone `status`, `connectedAt`, `lastTokenRefreshAt`,
`grantedScopes`, `lastErrorCode` seguro y `updatedAt`. No revela account subject,
tenant ID, tokens, datos de usuarios o configuración de cliente.

## Reglas de negocio

1. Sólo un `ADMIN` activo y con sesión vigente consulta o inicia autorización.
   `RECRUITER` recibe `403`; JWT revocado o cuenta no activa recibe `401`.
2. Iniciar autorización invalida intentos pendientes anteriores del mismo actor,
   crea state/verifier criptográficamente aleatorios y construye una URL para el
   authority configurado con redirect URI exacta, PKCE `S256` y scopes mínimos.
3. Callback requiere state existente, vigente y no consumido. Un state inválido,
   vencido o repetido falla con `400 OAUTH_STATE_INVALID` sin revelar si existió.
   Los demás resultados redirigen con `302` a la ruta fija configurada, usando
   sólo el parámetro permitido `result=connected|denied|error`.
4. Antes de intercambiar código, el callback marca el intento consumido dentro
   de la transacción. Un código nunca se reintenta ni se registra.
5. Intercambio exitoso valida issuer/tenant configurado, expiración y scopes.
   Reemplaza el ciphertext anterior en una sola transacción, incrementa versión
   y marca `CONNECTED`. Si el refresh token fue omitido o no puede cifrarse, no
   altera la conexión previa y devuelve error seguro.
6. El puerto interno refresca sólo cuando el access token no reutilizable está
   ausente o próximo a vencer. Mantiene el access token exclusivamente en memoria
   de proceso y reemplaza atómicamente el refresh token cuando Microsoft rota.
   Si `refresh_token_key_version` no coincide con la versión configurada, la
   clave no está disponible o AES-GCM falla autenticación/descifrado, no llama a
   Microsoft: pasa la conexión a `ERROR`, conserva ciphertext y persiste sólo
   `last_error_code=TOKEN_DECRYPTION_FAILED`.
7. `invalid_grant`, revocación o consentimiento retirado cambia la conexión a
   `REAUTHORIZATION_REQUIRED`, elimina ciphertext y devuelve un error interno
   tipado para que el worker futuro no reintente automáticamente.
8. Timeouts explícitos, máximo tres reintentos sólo para errores transitorios y
   respeto de `Retry-After`; llamadas OAuth nunca quedan dentro de transacciones
   de BD abiertas.

## Errores y seguridad

Errores JSON comunes incluyen `status`, `code`, `message`, `timestamp`, `path`
y `correlationId`; mensajes en español, sin SQL, stacktrace, código OAuth,
state, token, tenant, subject o respuesta Microsoft.

| Situación | HTTP / código |
| --- | --- |
| JWT ausente, inválido, revocado o cuenta no activa | `401 UNAUTHENTICATED` |
| No ADMIN | `403 FORBIDDEN` |
| Configuración OAuth incompleta | `503 OUTLOOK_NOT_CONFIGURED` |
| State inválido, vencido o consumido | `400 OAUTH_STATE_INVALID` |
| Usuario cancela o Microsoft rechaza autorización | `400 OUTLOOK_AUTHORIZATION_DENIED` |
| Intercambio/identidad/scopes inválidos | `502 OUTLOOK_AUTHORIZATION_FAILED` |
| Error transitorio de Microsoft agotado | `503 OUTLOOK_TEMPORARILY_UNAVAILABLE` |
| Versión de clave o descifrado AES-GCM inválido | Estado `ERROR`, `TOKEN_DECRYPTION_FAILED`; el puerto interno falla de forma tipada. |
| JSON inválido o campos desconocidos | `422 VALIDATION_ERROR` |

- Parámetros de callback se redaccionan antes de loguear URI o error.
- Auditoría y métricas no contienen correo, tenant, subject, state, token,
  authorization code, URL completa ni payload Microsoft.
- CORS no habilita el callback para orígenes no configurados. El redirect final
  sólo puede construirse desde `APP_BASE_URL` validada al iniciar la aplicación.

## Auditoría y observabilidad

Eventos append-only en `audit_event` para mutaciones efectivas:

- `OUTLOOK_AUTHORIZATION_STARTED`
- `OUTLOOK_CONNECTION_ESTABLISHED`
- `OUTLOOK_CONNECTION_REAUTHORIZED`
- `OUTLOOK_REAUTHORIZATION_REQUIRED`

El objetivo es `OUTLOOK_CONNECTION` con UUID técnico constante, actor cuando
existe, acción, timestamp y correlation ID. No se registran valores OAuth.

Métricas sin PII:

- `outlook.authorization_attempts` con `outcome` (`started`, `success`,
  `denied`, `state_invalid`, `failed`);
- `outlook.token_refreshes` con `outcome` (`success`, `reauth_required`,
  `transient_failure`, `decryption_failed`);
- `outlook.connection_status` gauge por estado, sin etiquetas de cuenta.

## OpenAPI y configuración

- Documentar rutas, bearer ADMIN, callback, respuestas `200`, `400`, `401`,
  `403`, `422`, `502`, `503` y ejemplos seguros en español.
- Propiedades tipadas: tenant ID, client ID, authority, redirect URI, timeouts,
  reintentos y `APP_BASE_URL`. `MICROSOFT_CLIENT_SECRET`,
  `MICROSOFT_TOKEN_ENCRYPTION_KEY` y
  `MICROSOFT_TOKEN_ENCRYPTION_KEY_VERSION` sólo vienen de entorno/secret manager.
- Perfil `test` usa valores ficticios y un doble HTTP; nunca llama Microsoft ni
  usa secreto real. Fallar rápido en `prod` si faltan propiedades obligatorias.

## Estrategia de pruebas

### Unitarias

- Generación y consumo único de state; vencimiento y redacción de logs.
- PKCE `S256`, construcción de URL, allowlist de authority/redirect URI.
- AES-GCM: ciphertext no contiene refresh token en claro, autenticación de tag,
  key version y fallo de descifrado seguro a `ERROR` con ciphertext conservado.
- Máquina de estados y clasificación de errores transitorios, `invalid_grant`
  y autorización denegada.

### Integración Spring/PostgreSQL Testcontainers

- `401`/`403`, estado administrativo seguro y JSON desconocido `422`.
- Inicio OAuth persiste sólo hashes/ciphertext; callback exitoso consume state y
  deja exactamente una conexión `CONNECTED` sin tokens en respuesta, logs o BD
  en texto plano.
- Callback repetido, vencido o manipulado devuelve `400` y no cambia conexión.
- Refresh rotado reemplaza ciphertext una sola vez bajo concurrencia; dos
  callbacks concurrentes no pierden conexión ni crean auditoría inconsistente.
- `invalid_grant` elimina credencial y llega a `REAUTHORIZATION_REQUIRED`.
- Versión de clave no coincidente o tag AES-GCM inválido deja `ERROR`, conserva
  ciphertext y expone sólo `TOKEN_DECRYPTION_FAILED`.
- OpenAPI, V6 desde V1–V5, métricas y regresión `./gradlew test` más
  `git diff --check`.

## Criterios de aceptación

1. Sólo `ADMIN` activo con sesión vigente consulta/inicia Outlook; no bearer es
   `401` y `RECRUITER` es `403` JSON seguro.
2. El inicio OAuth devuelve URL segura con PKCE y state opaco; state/verifier no
   se exponen al cliente, logs, auditoría o métricas.
3. Callback válido de un solo uso establece la conexión compartida `CONNECTED`;
   callback inválido, vencido o repetido devuelve `400` sin mutar conexión.
4. Refresh token se persiste sólo cifrado AES-GCM; nunca existe en respuesta,
   logs, auditoría, métricas ni texto plano de BD.
5. Reautorización reemplaza atómicamente credencial anterior y preserva una sola
   conexión consistente bajo callbacks/refresh concurrentes.
6. Revocación o `invalid_grant` elimina credencial y pasa a
   `REAUTHORIZATION_REQUIRED` sin retry automático.
7. El estado administrativo revela sólo datos seguros, no identidad de buzón,
   tenant, subject, token, secreto o payload Microsoft.
8. El puerto interno entrega access tokens efímeros a módulos autorizados sin
   exponer refresh tokens ni detalles de persistencia.
9. Auditoría, métricas y OpenAPI cubren las mutaciones y errores sin PII,
   secretos, URLs OAuth completas o etiquetas identificables.
10. V6, pruebas unitarias e integración Testcontainers usan dobles HTTP y no
    realizan llamadas a Microsoft ni habilitan lectura de Inbox/documentos.
11. No se habilitan jobs de procesamiento, candidatos, reportes, Claude,
    notificaciones, exportaciones o UI como efecto colateral.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Registro Entra con redirect URI exacta y permisos aprobados. | Validar configuración al arranque; doble en pruebas. |
| Riesgo | Rotación pierde refresh token. | Cifrado y reemplazo transaccional serializado. |
| Riesgo | Clave de cifrado ausente, versión no coincidente o ciphertext ilegible. | Pasar a `ERROR`, conservar ciphertext y exponer sólo `TOKEN_DECRYPTION_FAILED`; no llamar Microsoft. |
| Riesgo | Callback CSRF/replay. | State hash, PKCE, vencimiento y consumo único. |
| Riesgo | Logs filtran OAuth. | Redacción central y pruebas de ausencia. |
| Dependencia futura | Lectura Inbox requiere permiso Graph adicional justificado. | No solicitar ni usar `Mail.Read` hasta spec de descubrimiento. |

## Definition of Ready

`READY_FOR_DEV`

005 solicita únicamente `openid`, `profile` y `offline_access`; valida el
`id_token` sin llamar a Microsoft Graph. El permiso de Inbox pertenece a 006 y
no bloquea la conexión inicial.
