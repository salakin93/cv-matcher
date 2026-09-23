# 001 - Identidad y sesiones

## Objetivo
Establecer cuentas de producto, verificacion de correo, sesiones y el bootstrap del primer `ADMIN` para proteger los incrementos posteriores.

## Referencias
- `docs/prd-001-access-users-administration.md` (FR-ACC-001--014 y 029--034).
- `docs/architecture.md`, secciones 4--6, 9--11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.

## Alcance
### Incluido
- Registro publico `RECRUITER`, reenvio y verificacion de correo.
- Login, refresh rotativo, logout, `me`, bloqueo temporal y sesiones simultaneas.
- Provisionamiento unico del primer `ADMIN`, cambio obligatorio de contrasena y auditoria minima de identidad.
### Excluido
- Recuperacion o cambio voluntario de contrasena/correo y administracion de usuarios (002).
- UI, SSO, MFA, notificaciones in-app y consulta de auditoria.

## Comportamiento y reglas
- El correo se normaliza para unicidad. Registro duplicado responde neutralmente; una cuenta pendiente puede reenviar hasta tres veces por hora. Una cuenta activa no recibe un nuevo enlace ni revela su existencia.
- Registro crea cuenta pendiente; verificar un token unico de 24 h activa la cuenta sin crear sesion. Un reenvio invalida el token anterior.
- Password: minimo ocho caracteres, mayuscula, minuscula y numero. Cinco fallos consecutivos bloquean 15 minutos; login correcto reinicia el contador. Cuenta pendiente o desactivada con password correcta no suma fallo; la bloqueada con credenciales correctas recibe aviso de bloqueo y acceso a recuperacion.
- Access JWT dura 15 minutos; refresh opaco rotativo tiene maximo absoluto de ocho horas desde crear sesion. Logout revoca solo la sesion actual.
- `INITIAL_ADMIN_EMAIL`/`INITIAL_ADMIN_PASSWORD` crean una sola cuenta `ADMIN` verificada. Hasta confirmar la contrasena inicial y definir una nueva, solo puede cambiarla o cerrar sesion. Al completarlo se revocan sesiones y debe iniciar sesion otra vez.

## Contratos API
- `POST /api/v1/auth/register`, `/verify-email`, `/resend-verification`, `/login`, `/refresh`, `/logout`; `GET /api/v1/auth/me`.
- Login devuelve access token y perfil minimo; refresh usa cookie segura y rota el token. Los endpoints de token unico reciben token no reutilizable y no lo devuelven.
- Registro, login y reenvio usan mensajes espanoles seguros. Errores siguen el envelope de arquitectura; `401` para credenciales/sesion invalidas.

## Configuracion centralizada
En `application.yml` y `IdentityProperties`: duraciones de access/sesion, bloqueo, expiraciones y limites de reenvio. Secretos, claves JWT y bootstrap solo por entorno; seguir SSOT de `docs/architecture.md` seccion 10.

Valores iniciales: access 15 min, sesion absoluta 8 h, verificacion 24 h,
bloqueo 15 min y tres reenvios por hora.

## Datos y persistencia
Flyway agrega `user_account`, `user_session`, `email_verification` y `audit_event` minimo. El outbox durable usa `outbox_message`, propiedad de `notification`, mediante puerto transaccional. UUID, UTC, versionado donde aplique; correo normalizado unico; hashes para password, refresh y tokens de verificacion. Migraciones nuevas son inmutables.

## Integraciones
El correo de verificacion se registra en el outbox durable definido por arquitectura; un fallo de SMTP no revierte la cuenta pendiente. No se integra Outlook ni Claude.

## Errores y estados
Estados: pendiente, activa, bloqueada temporalmente, desactivada y cambio obligatorio. Token vencido/usado/reemplazado no modifica estado. Verificacion valida activa sin crear sesion. Cuenta pendiente con password correcta recibe instruccion de verificar y no suma fallo; cuenta desactivada, correo inexistente o password incorrecta reciben un mensaje generico.

## Seguridad y privacidad
Argon2id; JWT firmado; refresh hash en PostgreSQL y cookie `HttpOnly`, `Secure`, `SameSite=Lax` con CSRF. No exponer password, hashes, tokens, enlaces ni correo de terceros en logs, errores o auditoria.

## Observabilidad
Logs JSON y metricas agregadas de registro, verificacion, login, bloqueo y outbox con correlation ID seguro, sin PII. Auditar provisionamiento ADMIN, verificacion, bloqueo y desbloqueo; la auditoria es inmutable, indefinida y solo ADMIN. No guardar IP, user-agent, passwords, hashes, tokens, enlaces ni secretos.

## Estrategia de pruebas
### Validacion manual
Con datos ficticios: registro-verificacion-login-refresh-logout; duplicado neutral; cinco fallos; token vencido/reemplazado; y bootstrap restringido. Registrar entorno, casos y resultado.
### Automatizacion diferida
Estabilizacion: unitarias de politica/estados, integracion PostgreSQL de rotacion y consumo atomico, API de `401`/CSRF y seguridad de no filtracion.

## Criterios de aceptacion
1. Una cuenta pendiente no accede y una verificada activa si puede iniciar sesion sin que verificar cree una sesion.
2. Registro duplicado no revela una cuenta activa; reenvio respeta limite e invalida token previo.
3. Logout solo revoca la sesion actual y ninguna sesion supera ocho horas.
4. Cinco passwords incorrectos bloquean 15 minutos.
5. El primer `ADMIN` no usa funciones de negocio antes de confirmar y cambiar su password inicial.

## Riesgos y dependencias
Depende de PostgreSQL, Flyway y configuracion de correo de prueba. La entrega real de email depende de SMTP; el flujo debe seguir siendo consistente si falla.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: autenticacion y almacenamiento de secretos siguen `docs/architecture.md`; no duplicar componentes transversales.
- ARCHITECTURAL DECISION: el correo se registra en el outbox durable definido en arquitectura; SMTP no revierte una mutacion confirmada.

## Definition of Ready
`READY_FOR_DEV`.
