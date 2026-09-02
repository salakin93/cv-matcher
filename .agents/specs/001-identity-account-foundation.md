# 001 — Identity and Account Foundation

## Objetivo

Entregar la fundación backend de identidad para que usuarios verificados puedan
autenticarse y consumir futuras APIs protegidas de CV Matcher. El incremento
crea las garantías de cuenta, sesión, roles y auditoría mínima, pero no crea
interfaz ni integra un proveedor SMTP real.

## Referencias

- `docs/PRD.md`, secciones 2 y 3.
- `docs/PRODUCT_BACKLOG.md`, Epic 1, Features 1.1 y 1.2.
- `docs/architecture.md`, secciones 3, 5, 6, 9 y 10.
- `.agents/context/project.md` y `.agents/context/constraints.md`.

## Alcance

### Incluido

- Migración Flyway inicial para identidad, sesiones, tokens de propósito y
  auditoría mínima.
- Bootstrap idempotente del primer `ADMIN` mediante configuración segura.
- Registro de `RECRUITER`, verificación de correo y reenvío limitado.
- Login, access token JWT, refresh token opaco rotativo, logout y sesión
  máxima.
- Bloqueo temporal después de cinco intentos de login fallidos.
- Solicitud y confirmación de restablecimiento de contraseña.
- Cambio autenticado de contraseña y cambio de correo con verificación.
- Resolución de identidad actual y autorización base `RECRUITER` / `ADMIN`.
- Respuestas JSON seguras para 401, 403, validación y errores controlados.
- Auditoría mínima de acciones de identidad sensibles.
- OpenAPI, configuración `local`/`test`/`prod` y pruebas unitarias e
  integración con PostgreSQL/Testcontainers.

### Excluido

- Pantallas React, almacenamiento de token en el cliente y diseño visual.
- Administración de usuarios, cambios de rol, activación/desactivación y
  consulta de auditoría. Se implementarán en una spec posterior.
- Vacantes, reportes, candidatos, documentos, búsqueda histórica, Outlook,
  Microsoft Graph, Claude, jobs, notificaciones y exportaciones.
- Envío SMTP real. Este incremento publica un puerto de salida de correo y usa
  un fake en pruebas.
- MFA, SSO, login social, passkeys, recuperación por SMS y administración de
  claves de firma.

## Comportamiento y reglas

### Cuenta y bootstrap

1. Una cuenta tiene UUID, nombre completo, correo original, correo normalizado,
   hash Argon2id, rol, estado, `emailVerifiedAt`, contador de fallos,
   `lockedUntil`, `forcePasswordChange` y timestamps UTC.
2. El correo normalizado es `trim` + minúsculas y es único. Los mensajes
   públicos nunca revelan si existe una cuenta.
3. En un entorno vacío, el bootstrap crea exactamente un `ADMIN` con
   `INITIAL_ADMIN_EMAIL` y `INITIAL_ADMIN_PASSWORD`; falla el arranque en
   `prod` si falta cualquiera de esas variables. Si ya existe una cuenta, no
   crea ni modifica otra.
4. El administrador bootstrap tiene `forcePasswordChange=true`. Puede iniciar
   sesión, obtener su identidad, cambiar contraseña y cerrar sesión, pero las
   demás APIs protegidas responden 403 con código seguro
   `PASSWORD_CHANGE_REQUIRED` hasta que la cambie.
5. Registro público crea sólo cuentas `RECRUITER`, sin verificar y sin sesión.
   No admite rol enviado por el cliente.

### Contraseña y tokens de propósito

1. Contraseña inicial, nueva o restaurada: mínimo 8 caracteres, una mayúscula,
   una minúscula y un número. Nunca se devuelve ni registra.
2. Los tokens de verificación, cambio de correo y reset son aleatorios de alta
   entropía, de un solo uso y se guardan sólo como hash. Incluyen propósito,
   usuario, expiración y consumo atómico.
3. Vigencias configurables con valores iniciales: verificación/cambio de correo
   24 horas; reset de contraseña 30 minutos.
4. El reenvío de verificación invalida tokens no consumidos del mismo propósito
   y se limita a tres solicitudes por correo/hora. La respuesta permanece
   neutral aun cuando la cuenta no exista o ya esté verificada.
5. La solicitud de reset siempre responde de forma neutral. Si la cuenta es
   válida y verificada, invalida tokens de reset previos y solicita el envío por
   `MailGateway`.

### Login, sesión y autorización

1. Sólo una cuenta activa y verificada puede obtener sesión. Un error de login
   devuelve 401 con mensaje genérico y no indica si la cuenta existe, está sin
   verificar o tiene contraseña incorrecta.
2. Cinco fallos consecutivos bloquean la cuenta 15 minutos. Un login exitoso
   reinicia el contador; los intentos durante bloqueo no extienden el bloqueo.
3. Login exitoso emite:
   - JWT access token firmado, con `sub`, rol, `sessionId`, `iat` y `exp` de
     15 minutos; se entrega en respuesta JSON;
   - refresh token opaco de alta entropía, asociado a sesión de máximo ocho
     horas, enviado únicamente en cookie `HttpOnly`, `Secure`, `SameSite=Lax`.
4. El refresh token se guarda como hash, rota en cada refresh y el anterior se
   invalida en la misma transacción. Reutilizar un refresh revocado invalida la
   sesión como defensa ante robo.
5. `POST /auth/refresh` y `POST /auth/logout` exigen protección CSRF basada en
   cookie: el backend entrega token CSRF en cookie no `HttpOnly` y espera el
   mismo valor en `X-CSRF-TOKEN`. Las APIs de negocio futuras usan bearer JWT.
6. Logout invalida la sesión actual y expira las cookies. Cambio/restablecimiento
   de contraseña, confirmación de cambio de correo y cambios administrativos
   futuros invalidan todas las sesiones de la cuenta.
7. `GET /auth/me` requiere access token y devuelve sólo identidad segura,
   rol, estado de verificación y `forcePasswordChange`.
8. Las reglas de rol se aplican en backend. Este incremento sólo requiere que
   futuros endpoints puedan exigir `RECRUITER` o `ADMIN`.

### Cambio de contraseña y correo

1. El cambio autenticado de contraseña exige contraseña actual y nueva válida.
   Completarlo elimina `forcePasswordChange` y revoca todas las sesiones, salvo
   una nueva sesión emitida sólo mediante login posterior.
2. Solicitar cambio de correo exige sesión válida y contraseña actual. No cambia
   el correo aún; crea token de propósito `EMAIL_CHANGE` dirigido al correo
   nuevo.
3. Confirmar el cambio valida token, reserva el correo normalizado de forma
   atómica, actualiza correo y verificación, y revoca todas las sesiones.
4. Si el correo nuevo ya pertenece a una cuenta, la confirmación falla con 409
   seguro, sin modificar la cuenta original.

### Auditoría y correo

1. Se auditan: bootstrap de admin, registro, verificación, login exitoso,
   bloqueo, logout, reset solicitado/completado, cambio de contraseña y cambio
   de correo confirmado.
2. El evento contiene actor UUID cuando exista, tipo de acción, tipo/UUID de
   objetivo, timestamp UTC, correlation ID y metadata permitida. No contiene
   contraseña, token, correo en claro ni IP completa.
3. `MailGateway` recibe un comando tipado para verificación, reset o cambio de
   correo. El backend no registra el enlace ni el token. En pruebas se usa un
   fake que permite verificar la intención sin exponer el token en logs.

## Contratos

Todos los endpoints están bajo `/api/v1`. Los errores siguen el formato de
`docs/architecture.md`; se añade opcionalmente `code` para que el frontend
distinga estados seguros.

| Método y ruta | Autorización | Respuesta |
| --- | --- | --- |
| `POST /auth/register` | pública | `202 Accepted`, mensaje neutral. |
| `POST /auth/email-verification/confirm` | pública | `204 No Content`. |
| `POST /auth/email-verification/resend` | pública | `202 Accepted`, mensaje neutral. |
| `POST /auth/login` | pública | `200 OK`, access token, expiración e identidad segura; establece cookies refresh/CSRF. |
| `POST /auth/refresh` | refresh cookie + CSRF | `200 OK`, nuevo access token y refresh rotado. |
| `POST /auth/logout` | refresh cookie + CSRF | `204 No Content`; invalida sesión. |
| `GET /auth/me` | bearer JWT | `200 OK`, identidad segura. |
| `POST /auth/password-reset/request` | pública | `202 Accepted`, mensaje neutral. |
| `POST /auth/password-reset/confirm` | pública | `204 No Content`. |
| `POST /auth/password/change` | bearer JWT | `204 No Content`. |
| `POST /auth/email-change/request` | bearer JWT | `202 Accepted`, mensaje neutral. |
| `POST /auth/email-change/confirm` | pública | `204 No Content`. |

DTOs de entrada usan validación Bean Validation y no aceptan campos de rol,
estado, timestamps o IDs de usuario. OpenAPI documenta requisitos de seguridad,
cookies, header CSRF, respuestas 401/403/409/422 y ejemplos sin secretos.

## Datos y persistencia

La migración `V1__identity_account_foundation.sql` crea, como mínimo:

| Tabla | Invariantes |
| --- | --- |
| `user_account` | `email_normalized` único; rol y estado con checks; timestamps UTC. |
| `user_session` | UUID, hash único de refresh token, expiración, revocación y rotación; índice por usuario/sesión. |
| `account_action_token` | hash único, propósito, usuario, correo objetivo opcional, expiración, consumo; índice de tokens activos por propósito/usuario. |
| `audit_event` | evento append-only, actor/objetivo opcionales y metadata permitida. |

Las operaciones que consumen/rotan tokens, cambian correo o revocan sesiones son
transaccionales y usan constraints como protección final ante carreras. Nunca se
entregan entidades JPA directamente a controllers.

## Seguridad y privacidad

- `JWT_SIGNING_KEY`, contraseñas bootstrap y futuras credenciales SMTP sólo
  llegan por entorno; no existen valores reales en repositorio.
- La clave JWT HMAC tiene al menos 256 bits de entropía y no se registra.
- CORS acepta exclusivamente orígenes configurados. Cookies `Secure` son
  obligatorias en `prod`; `local` permite la configuración equivalente para
  desarrollo sin debilitar producción.
- Respuestas 401/403 se generan también desde Spring Security y son JSON
  seguros.
- No se incluyen CVs, documentos, Graph ni Claude en este incremento.
- Tests usan claves ficticias y una base aislada; no usan SMTP, cuentas ni
  correos reales.

## Observabilidad

- Cada request recibe correlation ID UUID generado por servidor.
- Logs de identidad registran sólo evento, resultado, tipo de error seguro,
  user UUID si ya autenticado y correlation ID.
- Métricas mínimas: registros, verificaciones, logins exitosos/fallidos,
  bloqueos, refreshes, resets y fallos del `MailGateway`, sin etiquetas PII.
- Health indica disponibilidad de base de datos; SMTP real queda fuera de este
  incremento.

## Estrategia de pruebas

- Unitarias: política de contraseña, normalización de correo, expiración/uso
  único de tokens, bloqueo, emisión/rotación/reutilización de refresh, roles,
  `forcePasswordChange`, auditoría y comandos de `MailGateway`.
- Integración Spring/PostgreSQL Testcontainers: migración V1, unicidad de correo,
  registro concurrente, consumo único de token, rotación atómica, invalidación
  de sesiones y serialización JSON de 401/403.
- API: contratos OpenAPI, validación de DTOs, rutas públicas/protegidas, CSRF
  de refresh/logout, mensajes neutrales y ausencia de campos sensibles.
- Regresión: ejecutar `./gradlew test` y el chequeo de diff configurado por el
  proyecto. No se ejecutan llamadas reales a SMTP ni proveedores externos.

## Criterios de aceptación

1. Con base limpia, el bootstrap crea un único `ADMIN` obligatorio de cambiar
   contraseña y no revela su credencial en logs o API.
2. Un registro válido crea sólo un `RECRUITER` no verificado y solicita un
   correo mediante `MailGateway`; no puede iniciar sesión antes de verificar.
3. Verificar un token válido una sola vez habilita la cuenta; token vencido,
   usado o inválido no cambia su estado ni expone detalles.
4. Login válido de cuenta verificada emite JWT de 15 minutos y refresh cookie
   rotativa; login inválido no enumera cuentas y bloquea al quinto fallo.
5. Refresh válido rota token de forma atómica; refresh revocado, vencido o
   reutilizado no entrega access token y deja la sesión revocada.
6. Logout, reset/confirmación de contraseña y cambio confirmado de correo
   invalidan las sesiones requeridas.
7. Reset y reenvío responden de forma neutral independientemente de existencia,
   verificación o estado de cuenta.
8. Cambio de correo no se aplica hasta su confirmación; al confirmar invalida
   sesiones y conserva unicidad bajo concurrencia.
9. `RECRUITER` y `ADMIN` se reconocen en autorización backend; 401 y 403 son
   JSON seguros y no dependen del manejador global de excepciones.
10. Flyway V1, unitarias e integración Testcontainers prueban constraints,
    carreras de token/sesión y comportamiento de autenticación sin datos reales.
11. OpenAPI documenta contratos y seguridad; no existen secretos ni tokens en
    diff, logs, respuestas, fixtures o documentación.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | PostgreSQL/Testcontainers disponibles para integración. | Fallar tests de integración claramente si Docker no está disponible; no sustituirlos por producción. |
| Dependencia | SMTP/dominio remitente para operación real. | `MailGateway` y configuración quedan listos; proveedor real se define en su spec/configuración posterior. |
| Riesgo | Envío de refresh cookie entre dominios. | Definir `APP_BASE_URL` y orígenes CORS antes de desplegar; no permitir comodines en prod. |
| Riesgo | Key rotation JWT. | Mantener claves externas y planificar rotación/`kid` antes de operación multi-clave; no bloquea el incremento inicial. |
| Riesgo | Bootstrap repetido. | El bootstrap es idempotente y sólo actúa sin cuentas; credenciales no se persisten en archivos de proyecto. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** JWT HMAC de corta vida y refresh opaco hash/rotativo
  es la base de web v1; la migración a firma asimétrica requiere spec posterior.
- **ARCHITECTURAL DECISION:** CSRF double-submit para endpoints que consumen
  refresh cookie; APIs de negocio usarán bearer JWT.
- **ASSUMPTION:** la interfaz final usa un origen HTTPS configurado compatible
  con `SameSite=Lax`. Si se requiere frontend en dominio cruzado, debe revisarse
  la estrategia de cookies y CSRF antes de implementar frontend.
- **OPEN QUESTION no bloqueante:** proveedor SMTP de producción y dominio
  remitente. No afecta pruebas ni contrato `MailGateway`.

## Definition of Ready

`READY_FOR_DEV`

El alcance, contratos, datos, seguridad y criterios de aceptación están
definidos. No existe pregunta funcional o arquitectónica bloqueante para
implementar este incremento backend.
