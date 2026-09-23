# PRD - Acceso, Usuarios y Administracion

## Estado

APROBADO_PARA_SPECS. Revision de arquitectura completada. Este documento define
el alcance funcional; no define la arquitectura ni la implementacion tecnica.

## Objetivo

Permitir que reclutadores accedan de forma controlada a CV Matcher y que los
administradores gestionen dicho acceso, sin exponer credenciales, tokens,
secretos ni informacion sobre cuentas ajenas.

## Actores

| Actor | Capacidades |
| --- | --- |
| Visitante | Registro, verificacion, inicio de sesion y solicitud de recuperacion. |
| Cuenta pendiente | Puede verificar correo y reenviar verificacion; no accede al producto. |
| Reclutador activo | Usa las funcionalidades autorizadas y gestiona su contrasena y correo. |
| Administrador activo | Gestiona usuarios, roles y activacion de otras cuentas. |
| Primer administrador | Administrador provisionado con cambio obligatorio de contrasena. |
| Operador de despliegue | Configura el primer administrador mediante variables seguras. |

## Estados de cuenta

| Estado | Puede iniciar sesion | Descripcion |
| --- | ---: | --- |
| Pendiente de verificacion | No | Cuenta creada cuyo correo no fue confirmado. |
| Activa | Si | Cuenta verificada y habilitada. |
| Bloqueada temporalmente | No | Alcanzo cinco intentos consecutivos de contrasena incorrecta. |
| Desactivada | No | Un administrador suspendio el acceso. |
| Cambio obligatorio de contrasena | Solo para cambiar contrasena | Aplica al primer administrador antes de usar el sistema. |

## Alcance

### Incluido

- Registro publico de reclutadores.
- Verificacion y cambio de correo.
- Inicio y cierre de sesion.
- Sesiones, tokens y expiracion.
- Recuperacion y cambio de contrasena.
- Bloqueo temporal por intentos fallidos.
- Administracion de usuarios, roles, activacion y desactivacion.
- Provisionamiento y primer acceso del administrador inicial.
- Auditoria inmutable de cambios y seguridad de cuentas.

### Fuera de alcance

- MFA y SSO.
- Eliminacion de cuentas.
- Restablecimiento de contrasenas por administradores.
- Almacenamiento de IP, user-agent o identificadores de dispositivo en auditoria.
- Inicio de sesion automatico despues de verificar correo, recuperar contrasena o cambiar correo.
- Cambio de nombre o perfil personal, salvo el correo definido aqui.

## Requisitos funcionales

### Registro publico

**FR-ACC-001.** Un visitante puede crear una cuenta con nombre completo, correo
valido y contrasena valida. La cuenta recibe el rol `RECLUTADOR` y queda
pendiente de verificacion.

**FR-ACC-002.** El correo es un identificador unico sin distinguir mayusculas y
minusculas.

**FR-ACC-003.** La contrasena requiere al menos ocho caracteres, una mayuscula,
una minuscula y un numero.

**FR-ACC-004.** El registro responde de forma neutral cuando el correo ya
pertenece a una cuenta. Si esta pendiente, puede reenviar verificacion; si esta
activa, no revela su existencia ni envia un enlace nuevo.

**FR-ACC-005.** Un enlace de verificacion vence a las 24 horas. Cada enlace
nuevo invalida el anterior y se permiten hasta tres reenvios por hora.

| Caso | Resultado |
| --- | --- |
| Datos validos y correo nuevo | Crea cuenta pendiente y envia verificacion. |
| Correo pendiente | Respuesta neutral y reenvio sujeto al limite. |
| Correo activo | Respuesta neutral sin revelar existencia. |
| Correo o contrasena invalidos | Rechaza la entrada sin crear cuenta. |
| Entrega de correo falla | La cuenta permanece pendiente y puede reenviar. |

### Verificacion de correo

**FR-ACC-006.** Una verificacion valida activa la cuenta, pero no crea una
sesion. El usuario debe iniciar sesion de forma explicita.

**FR-ACC-007.** Un enlace vencido, usado o reemplazado no cambia el estado de
la cuenta y permite solicitar uno nuevo.

**FR-ACC-008.** Una cuenta pendiente que usa una contrasena correcta no inicia
sesion, recibe instruccion de verificar su correo y no suma un intento fallido.

### Inicio, cierre de sesion y tokens

**FR-ACC-009.** Una cuenta activa con credenciales correctas puede abrir
sesiones simultaneas en varios dispositivos.

**FR-ACC-010.** El token de acceso dura 15 minutos. La sesion dura como maximo
ocho horas desde su inicio; renovar tokens dentro de la sesion no extiende ese
limite absoluto.

**FR-ACC-011.** El cierre de sesion invalida solo la sesion actual.

**FR-ACC-012.** Correo inexistente, contrasena incorrecta y cuenta desactivada
reciben el mismo mensaje publico seguro de acceso no completado.

| Caso | Resultado |
| --- | --- |
| Cuenta activa y contrasena correcta | Crea una nueva sesion. |
| Correo inexistente o contrasena incorrecta | Mensaje generico; la contrasena incorrecta suma un fallo. |
| Cuenta pendiente y contrasena correcta | Sin sesion; ofrece verificar correo; no suma fallo. |
| Cuenta desactivada y contrasena correcta | Mensaje generico; no suma fallo. |
| Cuenta bloqueada y credenciales correctas | Informa bloqueo temporal y ofrece recuperacion. |
| Primer ADMIN pendiente | Solo permite cambiar contrasena o cerrar sesion. |

### Bloqueo temporal

**FR-ACC-013.** Cinco intentos consecutivos de contrasena incorrecta bloquean
la cuenta durante 15 minutos.

**FR-ACC-014.** Un inicio correcto reinicia el contador de fallos. Una
recuperacion de contrasena completada desbloquea la cuenta de inmediato y
reinicia dicho contador.

### Recuperacion y cambio de contrasena

**FR-ACC-015.** La recuperacion de contrasena responde de forma neutral para
no revelar si existe una cuenta. Permite hasta tres solicitudes por hora y cada
enlace nuevo invalida el anterior.

**FR-ACC-016.** El enlace de recuperacion es de un solo uso y vence a los 30
minutos.

**FR-ACC-017.** Una solicitud para una cuenta pendiente reenvia verificacion
de correo; no permite restablecer contrasena antes de activar la cuenta.

**FR-ACC-018.** Una recuperacion completada cambia la contrasena, revoca todas
las sesiones, desbloquea la cuenta y exige iniciar sesion nuevamente.

**FR-ACC-019.** El cambio voluntario de contrasena exige sesion activa,
contrasena vigente y una nueva contrasena valida. Al completarse, revoca todas
las sesiones y exige un nuevo inicio de sesion.

**FR-ACC-020.** Un administrador no puede cambiar ni restablecer la contrasena
de otra persona.

### Cambio de correo

**FR-ACC-021.** El cambio de correo exige sesion activa y contrasena vigente.
El correo nuevo debe ser unico sin distinguir mayusculas y minusculas.

**FR-ACC-022.** El correo actual se conserva hasta que el correo nuevo sea
verificado. Si el correo nuevo ya pertenece a otra cuenta, el cambio se rechaza
con un mensaje seguro.

**FR-ACC-023.** La verificacion del correo nuevo vence a las 24 horas; admite
hasta tres reenvios por hora y cada enlace nuevo invalida el anterior.

**FR-ACC-024.** Verificar un cambio de correo sustituye el correo vigente,
revoca todas las sesiones y exige un nuevo inicio de sesion. No crea sesion
automatica.

### Administracion de usuarios

**FR-ACC-025.** Solo `ADMIN` puede consultar usuarios y cambiar el rol,
activar o desactivar a otras cuentas.

**FR-ACC-026.** Un `ADMIN` no puede cambiar su propio rol ni su propio estado
de activacion.

**FR-ACC-027.** El ultimo `ADMIN` activo no puede ser degradado ni desactivado.
Debe existir otro `ADMIN` activo antes de permitir cualquiera de esas acciones.

**FR-ACC-028.** Cambiar rol, activar o desactivar revoca todas las sesiones de
la cuenta afectada y envia un aviso seguro por correo. Ese aviso no incluye
contrasenas, tokens, enlaces de sesion ni secretos.

### Primer administrador

**FR-ACC-029.** El primer administrador se provisiona una unica vez mediante
variables seguras de despliegue; no nace de un registro publico.

**FR-ACC-030.** El correo del primer administrador se considera verificado.
En su primer acceso debe confirmar la contrasena inicial y definir una nueva.

**FR-ACC-031.** Mientras el cambio obligatorio este pendiente, el primer
administrador solo puede cambiar su contrasena o cerrar sesion. Al completarlo,
se revocan las sesiones y debe iniciar sesion otra vez.

### Auditoria

**FR-ACC-032.** Los eventos de auditoria son inmutables, se conservan
indefinidamente y solo son visibles para `ADMIN`.

**FR-ACC-033.** La auditoria conserva actor cuando exista, accion, cuenta o
recurso afectado, resultado, fecha y correlacion tecnica no publica. No
conserva IP, user-agent, identificadores de dispositivo, contrasenas, hashes de
contrasena, tokens, enlaces ni secretos.

**FR-ACC-034.** Se auditan: provisionamiento del primer administrador,
verificacion de correo, bloqueo y desbloqueo, recuperacion completada, cambios
de contrasena o correo, cambios de rol, activacion y desactivacion. Las
reautorizaciones de integraciones se auditan en su alcance funcional propio.

## Reglas de negocio

| ID | Regla |
| --- | --- |
| BR-ACC-001 | Una cuenta publica solo es operativa despues de verificar su correo. |
| BR-ACC-002 | El bloqueo temporal responde solo a contrasenas incorrectas. |
| BR-ACC-003 | Un restablecimiento correcto de contrasena desbloquea y revoca sesiones. |
| BR-ACC-004 | Ninguna accion puede dejar el sistema sin un ADMIN activo. |
| BR-ACC-005 | Toda transicion de identidad o privilegio revoca las sesiones afectadas. |
| BR-ACC-006 | Los mensajes publicos no revelan existencia, estado o causa de rechazo de cuentas, salvo una cuenta bloqueada que presento credenciales correctas y una cuenta pendiente con credenciales correctas. |

## Criterios de aceptacion

1. Un visitante puede registrarse, verificar su correo y luego iniciar sesion
   como `RECLUTADOR`.
2. El registro y la recuperacion no revelan si una cuenta activa existe.
3. Una cuenta pendiente, desactivada o bloqueada no puede obtener sesion.
4. Cinco contrasenas incorrectas consecutivas bloquean la cuenta por 15
   minutos.
5. Una recuperacion exitosa cambia la contrasena, desbloquea la cuenta y revoca
   sus sesiones.
6. Ninguna sesion supera ocho horas desde su creacion.
7. Cambiar contrasena, correo, rol o activacion revoca sesiones afectadas.
8. No se puede degradar ni desactivar al ultimo `ADMIN` activo.
9. El primer administrador no puede usar funciones administrativas antes de
   cambiar la contrasena inicial.
10. La auditoria registra acciones sensibles sin guardar secretos ni datos de
    red o dispositivo.
