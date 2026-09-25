# Validacion manual BE-001 y BE-002 pendientes

**Estado:** completada en entorno local aislado.

Use PostgreSQL local con migraciones V1--V3, cuentas y direcciones `example.test`
ficticias, y el outbox local. No use credenciales, correos, tokens ni cookies
reales. Extraiga tokens de prueba solo desde la base local durante la sesion y no
los copie a documentos ni logs.

| Caso | Resultado esperado |
| --- | --- |
| Bootstrap inicial | Con variables iniciales validas y una base nueva, se crea un unico `ADMIN` verificado con `passwordChangeRequired=true`; un reinicio no crea otra cuenta. |
| Restriccion bootstrap | El access token del primer ADMIN solo permite `POST /api/v1/auth/password/change` y logout; `/api/v1/admin/users` y vacantes retornan `403`. |
| Cambio obligatorio | La contraseña inicial correcta y una nueva valida completan el cambio, revocan todas las sesiones y requieren un nuevo login. |
| Reset neutral y pendiente | Solicitudes para correo inexistente y activo retornan `202`; una cuenta pendiente recibe solo un mensaje de verificacion en outbox. |
| Reset confirmado | Token vigente cambia contraseña, consume el token, desbloquea, reinicia fallos y revoca todas las sesiones sin crear una nueva. |
| Reset limitado | Cuatro solicitudes en una hora generan como maximo tres mensajes; el ultimo token invalida los anteriores y un token usado/vencido retorna error seguro sin cambiar datos. |
| Cambio voluntario | Password actual correcta y nueva valida cambian la contraseña y revocan sesiones; password actual incorrecta retorna `401` sin cambios. |
| Cambio de correo | La solicitud conserva `email` actual, registra un mensaje `EMAIL_CHANGE` cifrado y el token vigente cambia el correo, consume el token y revoca sesiones. |
| Reenvio de correo | Tres reenvios por hora como maximo; cada uno invalida el token anterior. El correo de otra cuenta devuelve conflicto seguro y no crea mensaje. |
| Administracion | Un ADMIN activo lista usuarios paginados y puede cambiar rol o estado de otra cuenta con la version actual; todo cambio efectivo revoca sesiones, deja auditoria y registra aviso seguro en outbox. |
| Salvaguardas ADMIN | Auto-PATCH devuelve `409`; degradar o desactivar el unico ADMIN activo devuelve `409`; una version desactualizada devuelve `409`; PATCH sin cambio no agrega auditoria ni outbox. |
| Privacidad | Verifique que outbox solo contiene payload cifrado, auditoria no contiene credenciales/tokens/red y los logs no contienen correos ni tokens. |

## Casos ejecutados

| Caso | Resultado observado |
| --- | --- |
| Bootstrap inicial | Aprobado: se creo un ADMIN unico, activo y verificado con `passwordChangeRequired=true`. |
| Restriccion bootstrap | Aprobado: vacantes y administracion devolvieron `403` mientras el cambio obligatorio estaba pendiente. |
| Refresh con cambio obligatorio | Aprobado: cookies y CSRF validos devolvieron `401 UNAUTHENTICATED`. |
| Reutilizacion de password inicial | Aprobado: el cambio obligatorio con la misma contraseña fue rechazado y el estado forzado se conservo. |
| Cambio obligatorio valido | Aprobado: una contraseña distinta completa el cambio y permite el acceso ADMIN posterior. |
| Reset neutral y pendiente | Aprobado: cuentas activa e inexistente devolvieron `202`; solo la activa creo outbox `PASSWORD_RESET` cifrado. |
| Reset confirmado | Aprobado: el token se consumio, se cambio la contraseña, se desbloqueo la cuenta y se revocaron las sesiones. |
| Reset reutilizado | Aprobado: el token consumido devolvio `400 VALIDATION_ERROR` sin cambios. |
| Reset vencido | Aprobado: devolvio `400 VALIDATION_ERROR`; estado, version, bloqueo y consumo del token no cambiaron. |
| Cambio voluntario | Aprobado: password actual invalida devolvio `401`; el cambio valido revoco sesiones y exigio nuevo login. |
| Cambio de correo | Aprobado: la solicitud con password correcta conservo el correo vigente, creo outbox cifrado y el token valido actualizo el correo, se consumio y revoco sesiones. |
| Reset de cuenta pendiente y limite | Aprobado: una cuenta pendiente recibio solo verificacion y el limite de reset impidio mas de tres solicitudes en la ventana. |
| Reenvio de cambio de correo | Aprobado: se limitaron tres reenvios y el token previo quedo invalido. |
| Correo de destino ocupado | Aprobado: devolvio conflicto seguro sin crear un mensaje adicional. |
| Listado ADMIN | Aprobado: un ADMIN activo recibio una pagina de usuarios con offset y limit. |
| PATCH ADMIN efectivo | Aprobado: cambio de otra cuenta con version actual revoco sesiones y genero auditoria y outbox seguro. |
| Salvaguardas ADMIN | Aprobado: version anterior, auto-PATCH y transicion del ultimo ADMIN activo devolvieron `409`; PATCH sin cambios no agrego auditoria ni outbox. |
| Cambio de correo vencido | Aprobado: devolvio error seguro sin cambiar correo, version ni consumir el token. |
| Privacidad y logging | Aprobado: outbox expone solo metadatos y ciphertext, auditoria no contiene campos sensibles y consola usa JSON Logstash sin secretos ni PII. |
