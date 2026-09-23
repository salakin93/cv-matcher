# 002 - Seguridad y administración de cuentas

## Objetivo
Completar recuperacion y cambios de cuenta, y permitir a `ADMIN` administrar roles y activacion sin invalidar las garantias de identidad de 001.

## Referencias
- `docs/prd-001-access-users-administration.md` (FR-ACC-015--028, 032--034).
- `docs/architecture.md`, secciones 5, 7, 9 y 10.
- Spec 001.

## Alcance
### Incluido
- Recuperacion, cambio voluntario y cambio obligatorio de contrasena.
- Solicitud/verificacion de cambio de correo.
- Listado administrativo y cambio de rol o activacion de otras cuentas.
- Outbox de avisos seguros y auditoria de cambios efectivos.
### Excluido
- Registro, login, bootstrap y tokens base (001); eliminar cuentas, reset por administrador, MFA, SSO y UI.

## Comportamiento y reglas
- Recuperacion es neutral, maximo tres solicitudes/hora, token unico de 30 min; completarla cambia password, desbloquea y revoca todas las sesiones. Cuenta pendiente recibe reenvio de verificacion, no reset.
- Cambio voluntario exige sesion, password actual y password valida; revoca todas las sesiones. Cambio obligatorio conserva las restricciones de 001.
- Cambio de correo exige password actual; mantiene el correo vigente hasta verificar el nuevo token de 24 h. El correo nuevo debe ser unico; si pertenece a otra cuenta se rechaza con mensaje seguro. Admite tres reenvios por hora, cada uno invalida el anterior. Verificar reemplaza correo, revoca sesiones y no crea sesion.
- Solo `ADMIN` activo lista y cambia rol/estado ajenos. No cambia su propio rol/estado ni degrada/desactiva al ultimo `ADMIN` activo. Cambios efectivos revocan sesiones y notifican por correo seguro a la cuenta afectada.

## Contratos API
- `POST /api/v1/auth/password-reset/request|confirm`, `/password/change`, `/email-change/request|verify|resend`.
- `GET /api/v1/admin/users` paginado y `PATCH /api/v1/admin/users/{id}` con rol/activo y version esperada.
- Respuestas no revelan existencia de cuenta; conflicto de version retorna `409` seguro. `401`/`403` normalizados.

## Configuracion centralizada
Extender `IdentityProperties` de 001 con ventana de reset, cambio de correo y rate limits. Usar el `MailGateway` y outbox centrales de arquitectura; no hay configuracion administrable por API.

## Datos y persistencia
Flyway agrega `password_reset` y almacenamiento de cambio de correo/tokens con hash, expiracion, consumo atomico y versionado de cuenta. Reutiliza sesiones/auditoria/outbox de 001; no modifica migraciones aplicadas. Los eventos efectivos incluyen reset completado, cambio de password/correo, rol, activacion y desactivacion.

## Integraciones
Solo correo de producto mediante outbox. Un fallo de entrega no revierte cambio de seguridad o privilegio confirmado.

## Errores y estados
Token usado, vencido o reemplazado no cambia datos. Reset o cambio de password no crean sesion y el reset desbloquea la cuenta. Peticiones administrativas repetidas sin cambio no auditan. El ultimo `ADMIN` activo recibe `409` seguro al intentar una transicion prohibida.

## Seguridad y privacidad
No almacenar ni revelar passwords, tokens, enlaces, IP o user-agent. Aplicar autorizacion por rol y recurso en backend; invalidar sesiones en toda transicion exigida por PRD.

## Observabilidad
Eventos tecnicos agregados de reset, cambios y revocaciones; auditoria inmutable minima de resultados efectivos, sin secretos ni datos de red.

## Estrategia de pruebas
### Validacion manual
Con usuarios ficticios, comprobar neutralidad, expiracion/uso unico, desbloqueo, revocacion global, cambio de correo y proteccion del ultimo `ADMIN`.
### Automatizacion diferida
Pruebas de transacciones/competencia del ultimo admin y tokens, integracion PostgreSQL/outbox y API de autorizacion, neutralidad y `409` optimista.

## Criterios de aceptacion
1. Un reset valido revoca sesiones y desbloquea; no inicia sesion.
2. El correo nuevo no sustituye al vigente antes de verificarse.
3. Cambiar password, correo, rol o activo revoca las sesiones afectadas.
4. Un `ADMIN` no administra su propia cuenta ni deja el sistema sin `ADMIN` activo.
5. Avisos y auditoria no contienen secretos ni contenido personal innecesario; la auditoria no guarda IP, user-agent ni credenciales.

## Riesgos y dependencias
Depende de 001 y SMTP/outbox. Las mutaciones de cuenta requieren control de concurrencia para preservar el ultimo administrador.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: la invalidacion de sesion se implementa en el modulo `identity`; administracion consume su caso de uso, sin acceso directo a tablas.

## Definition of Ready
`READY_FOR_DEV`.
