# Validacion manual BE-001 y BE-002

**Fecha:** 2026-09-24  
**Entorno:** backend local Java 25, PostgreSQL 17 local y base recreada desde `V1__identity_baseline.sql`.

## Resultado

Los casos ejecutados completaron con los resultados esperados. No se usaron credenciales, correo, tokens ni cookies reales.

| Caso | Resultado esperado | Resultado observado |
| --- | --- | --- |
| Health check | `200` con estado `UP` | Aprobado |
| Registro valido | `202 Accepted` sin cuerpo | Aprobado |
| Contraseña no conforme | `400 PASSWORD_POLICY_VIOLATION` | Aprobado |
| Correo no valido | `422 VALIDATION_ERROR` | Aprobado |
| Registro duplicado | `202 Accepted` neutral | Aprobado |
| Outbox de verificacion | Mensaje `EMAIL_VERIFICATION` cifrado | Aprobado mediante consulta SQL |
| Reenvio de verificacion | Maximo tres intentos por ventana | Aprobado mediante consulta SQL |
| Login pendiente de verificacion | `401 EMAIL_VERIFICATION_REQUIRED` | Aprobado |
| Token de verificacion vacio | `422 VALIDATION_ERROR` | Aprobado |
| Token de verificacion invalido | `400 VALIDATION_ERROR` | Aprobado |
| Verificacion valida local | `204 No Content` y cuenta `ACTIVE` | Aprobado |
| Login activo | `200`, JWT y cookies de sesion | Aprobado |
| Identidad sin bearer | `401 UNAUTHENTICATED` | Aprobado |
| Identidad con bearer | `200` con identidad segura | Aprobado |
| Refresh | `200` y rotacion de sesion | Aprobado |
| Logout | `204` y sesion revocada | Aprobado mediante `revoked_at` SQL |
| Bloqueo temporal | Cinco fallos y `401 ACCOUNT_TEMPORARILY_LOCKED` | Aprobado |
| Restablecimiento local de bloqueo | Login valido despues de limpiar el bloqueo | Aprobado |

## Notas

- La entrega conserva un outbox cifrado; no incluye un despachador SMTP ni envio real de correo.
- Para validar la confirmacion sin integrar correo, se sustituyo temporalmente el hash de un token en la base local por el hash de un valor de prueba conocido.
- Los valores de prueba y artefactos de autenticacion no se registran en este documento.
