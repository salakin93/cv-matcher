# 002 - Account Administration

## Objetivo

Entregar la administración backend de cuentas para que un `ADMIN` gestione el
rol y la activación de usuarios verificados sin debilitar las garantías de
sesión, autorización, auditoría ni el acceso a un administrador activo.

## Referencias

- `docs/PRD.md`, secciones 2, 3 y 9.
- `docs/PRODUCT_BACKLOG.md`, Epic 1, Feature 1.2.
- `docs/architecture.md`, secciones 4, 5, 6, 9 y 10.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- `.agents/specs/001-identity-account-foundation.md`.

## Alcance

### Incluido

- Consulta paginada de cuentas para administradores.
- Cambio administrativo entre roles `RECRUITER` y `ADMIN`.
- Activación y desactivación administrativa de cuentas verificadas.
- Revocación de todas las sesiones, incluidos los bearer JWT asociados, tras
  cambio de rol o estado.
- Protección transaccional del invariante de al menos un `ADMIN` activo.
- Auditoría, OpenAPI, métricas y pruebas de integración para estas operaciones.

### Excluido

- Pantallas React, búsqueda UI, invitaciones y creación manual de usuarios.
- Cambio administrativo de contraseña, correo o `forcePasswordChange`.
- Consulta o exportación de auditoría. Se define en Epic 6, Feature 6.2.
- Eliminación física de cuentas, recuperación de cuentas desactivadas por email,
  MFA, SSO, SMTP real y administración de claves JWT.
- Vacantes, candidatos, documentos, Outlook, Claude, trabajos, notificaciones y
  exportaciones.

## Comportamiento y reglas

### Administración de cuentas

1. Sólo un usuario autenticado con rol efectivo `ADMIN` puede usar las rutas de
   administración. El rol se toma de la cuenta y sesión activas, no del claim
   JWT por sí solo.
2. El listado devuelve únicamente identidad administrativa segura: UUID, nombre
   completo, correo, rol, estado, fecha de verificación, indicador de cambio de
   contraseña obligatorio y `updatedAt`. Nunca devuelve hashes, tokens, fallos
   de login, `lockedUntil` ni sesiones.
3. Las cuentas creadas por registro público siguen siendo `RECRUITER`. Esta spec
   no permite al cliente escoger rol al registrarse.
4. Un cambio de rol sólo acepta `RECRUITER` o `ADMIN`. El usuario objetivo debe
   tener correo verificado; una cuenta `PENDING_VERIFICATION` no puede ser
   promovida ni degradada.
5. Desactivar sólo permite la transición `ACTIVE -> DISABLED`. Activar sólo
   permite `DISABLED -> ACTIVE` cuando `email_verified_at` existe. Un
   administrador no puede activar una cuenta pendiente como atajo de
   verificación de correo.
6. No se permiten cambios de rol ni estado sobre la propia cuenta del actor. El
   actor debe usar los flujos de identidad existentes para gestionar su propia
   contraseña y correo.
7. Debe existir al menos un `ADMIN` con estado `ACTIVE` después de cada cambio.
   Desactivar o degradar el último administrador activo falla con `409` y no
   modifica datos ni sesiones.
8. Cambiar rol, desactivar o activar una cuenta revoca todas sus sesiones. Un
   bearer JWT emitido por una sesión revocada debe recibir `401` de inmediato.
9. Las mutaciones administrativas son idempotentes respecto del estado deseado:
   solicitar el rol o estado ya persistido devuelve `204` sin revocar sesiones
   ni crear un evento de auditoría adicional.

### Concurrencia

1. Antes de evaluar o modificar rol/estado, la transacción obtiene un bloqueo
   transaccional global de administración mediante `pg_advisory_xact_lock` con
   una clave constante documentada en código. Así se serializan operaciones que
   podrían afectar a administradores distintos.
2. La transacción bloquea además la fila objetivo con `FOR UPDATE`, recalcula el
   número de administradores activos y recién entonces aplica la mutación,
   revoca sesiones y registra auditoría.
3. Dos operaciones concurrentes que intenten dejar sin administradores activos
   producen exactamente una operación exitosa como máximo; la otra recibe
   `409 LAST_ACTIVE_ADMIN`.

## Contratos

Todos los endpoints están bajo `/api/v1/admin/users` y requieren bearer JWT de
un `ADMIN` activo con sesión vigente.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| `GET /admin/users` | Query opcional `role`, `status`, `page` (0+), `size` (1-100) | `200` página de cuentas administrativas. |
| `PATCH /admin/users/{userId}/role` | `{ "role": "ADMIN" | "RECRUITER" }` | `204` si se aplica o ya está aplicado. |
| `PATCH /admin/users/{userId}/status` | `{ "status": "ACTIVE" | "DISABLED" }` | `204` si se aplica o ya está aplicado. |

### DTOs

```json
{
  "items": [
    {
      "id": "uuid",
      "fullName": "Ana Reclutadora",
      "email": "ana@example.test",
      "role": "RECRUITER",
      "status": "ACTIVE",
      "emailVerifiedAt": "2026-09-04T12:00:00Z",
      "forcePasswordChange": false,
      "updatedAt": "2026-09-04T12:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

- La respuesta se ordena establemente por `fullName` ascendente y luego `id`
  ascendente.
- `role` y `status` son filtros opcionales exactos. Valores fuera de los enums,
  paginación inválida o payload inválido devuelven `422 VALIDATION_ERROR`.
- Una cuenta inexistente devuelve `404 USER_NOT_FOUND`. Este detalle sólo se
  expone a administradores autenticados.
- Cambiar el último administrador activo devuelve `409 LAST_ACTIVE_ADMIN`.
- Actuar sobre la propia cuenta devuelve `409 SELF_ADMINISTRATION_FORBIDDEN`.
- Promover/degradar una cuenta no verificada o activar una sin verificación
  devuelve `409 EMAIL_NOT_VERIFIED`.
- `401`, `403`, `409` y `422` usan el formato seguro común con `code` y
  `correlationId`.
- Los DTOs de mutación deben rechazar campos JSON desconocidos con `422`; no se
  ignoran silenciosamente campos destinados a contraseña, correo, sesiones o
  indicadores de seguridad.

## Datos y persistencia

- Crear migración Flyway `V3__account_administration.sql` sólo si hacen falta
  índices para los filtros y el recuento de administradores activos. La tabla
  existente `user_account` conserva los roles y estados definidos en V1.
- Si se crea, el índice parcial recomendado es sobre `user_account(role)` para
  filas `status='ACTIVE'`, y debe justificarse por los planes de consulta usados
  en pruebas PostgreSQL.
- No se cambia ni reescribe V1 o V2.
- Las nuevas acciones usan la tabla append-only `audit_event` existente con:
  `USER_ROLE_CHANGED`, `USER_DISABLED` y `USER_ENABLED`.
- Actor y objetivo son UUID; no se persisten correo, tokens, contraseña, IP ni
  valores anteriores/nuevos sensibles como metadata de auditoría.

## Integraciones

- No hay integraciones externas ni correo saliente en este incremento.
- Se reutiliza el control de sesión de identidad de 001: revocar una fila de
  `user_session` invalida su refresh y cualquier bearer JWT enlazado a `sid`.

## Errores y estados

- Una cuenta `DISABLED` no puede iniciar sesión ni renovar refresh existente.
- Una cuenta desactivada conserva sus datos y auditoría; reactivarla no crea una
  sesión ni cambia contraseña.
- Las mutaciones ya satisfechas retornan `204` y no auditan ni revocan sesiones.
- Las excepciones de seguridad de Spring Security continúan devolviendo JSON
  seguro, sin depender sólo del manejador global.

## Seguridad y privacidad

- Las rutas administrativas no son públicas ni accesibles a `RECRUITER`.
- La validación de sesión debe usar `sid` contra `user_session` no revocada y no
  expirada antes de crear autenticación para cualquier bearer protegido.
- El correo mostrado en la lista es PII permitida sólo a administradores; no se
  incluye en logs, auditoría, métricas ni errores.
- Las métricas no usan UUID, correo ni nombre como etiquetas.
- Todas las mutaciones usan UUID de ruta validados y consultas parametrizadas.
- La deserialización de solicitudes administrativas rechaza campos adicionales
  para impedir que se ignoren intentos de alterar contraseña, correo, sesiones o
  indicadores de seguridad.

## Observabilidad

- Métricas mínimas: `identity.admin.role_changes`,
  `identity.admin.status_changes` y `identity.admin.conflicts`, con etiquetas
  de resultado no PII.
- Logs JSON estructurados registran evento, resultado, UUID de actor/objetivo y
  correlation ID; no registran correo, tokens ni contraseña.
- Cada mutación efectiva genera el evento de auditoría correspondiente.

## Estrategia de pruebas

- Unitarias: validación de DTOs/enums, transiciones permitidas, cuenta propia,
  último administrador, mutaciones idempotentes y clasificación de errores.
- Integración Spring/PostgreSQL Testcontainers: filtros y paginación, acceso
  `ADMIN`/`RECRUITER`, revocación de refresh y bearer tras cada mutación,
  auditoría y métricas.
- Concurrencia PostgreSQL: dos administradores activos intentan desactivar o
  degradar administradores en paralelo; se preserva al menos uno activo.
- API: `401`/`403` JSON, `404`, `409`, `422`, OpenAPI de seguridad y ausencia
  de campos sensibles en listado y errores.
- Regresión: `./gradlew test`, chequeo de diff y pruebas existentes de 001.

## Criterios de aceptación

1. Un `ADMIN` puede listar cuentas paginadas y filtradas; un `RECRUITER` recibe
   `403` y una petición sin bearer recibe `401`, todos en JSON seguro.
2. El listado no expone hashes, tokens, sesiones, contadores de fallos ni
   `lockedUntil`.
3. Un `ADMIN` puede promover o degradar una cuenta verificada de otro usuario;
   la operación revoca sus sesiones y el bearer previo falla con `401`.
4. Un `ADMIN` puede desactivar y reactivar una cuenta verificada de otro usuario;
   desactivar revoca sus sesiones, impide login/refresh y reactivar no crea
   sesión.
5. Una cuenta pendiente no puede ser promovida, degradada ni activada y recibe
   el conflicto seguro documentado.
6. Un actor no puede cambiar su propio rol ni estado.
7. Ninguna secuencia, incluso concurrente, puede dejar cero administradores
   activos; el conflicto es `409 LAST_ACTIVE_ADMIN` y el estado previo se
   conserva.
8. Mutaciones idempotentes no revocan sesiones ni agregan auditoría duplicada.
9. Cada mutación efectiva registra actor, objetivo, acción y correlation ID sin
   PII ni secretos, y actualiza la métrica correspondiente sin etiquetas PII.
10. OpenAPI documenta filtros, paginación, bearer ADMIN, respuestas `401`,
    `403`, `404`, `409`, `422` y ejemplos seguros.
11. Flyway, pruebas unitarias e integración Testcontainers verifican
    autorización, transacciones, revocación de sesión y carreras sin usar datos
    ni servicios externos reales.
12. Un campo JSON administrativo desconocido recibe `422` y no genera ninguna
    mutación.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | 001 debe estar cerrado, incluida la validación de `sid` contra sesión activa. | Bloquear desarrollo si esa garantía no está integrada y verificada. |
| Riesgo | Dos administradores pueden desactivar cuentas distintas simultáneamente. | Bloqueo advisory transaccional y prueba de concurrencia PostgreSQL. |
| Riesgo | Exponer correo en una respuesta administrativa. | Sólo `ADMIN`, DTO explícito, sin logs/auditoría/métricas. |
| Riesgo | Perder el último administrador. | Recuento serializado y `409 LAST_ACTIVE_ADMIN`. |
| Dependencia | PostgreSQL/Testcontainers disponibles. | Fallar integración claramente; no reemplazar por base de producción. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** La administración de cuentas pertenece al módulo
  `administration`; puede reutilizar el puerto interno de identidad para revocar
  sesiones, pero no accede directamente a detalles de implementación ajenos a
  ese contrato.
- **ARCHITECTURAL DECISION:** `PENDING_VERIFICATION` no es activable ni
  administrable por cambio de rol. Sólo el flujo de verificación de 001 puede
  convertirla en `ACTIVE`.
- **ARCHITECTURAL DECISION:** El último administrador activo se protege con un
  bloqueo advisory de PostgreSQL para cubrir carreras entre filas distintas.
- **ASSUMPTION:** El correo es dato necesario para que un administrador
  identifique la cuenta. Su presentación queda limitada a la API administrativa
  protegida.
- **OPEN QUESTION no bloqueante:** La UI administrativa, búsqueda por texto y
  consulta de auditoría se diseñarán en increments posteriores.

## Definition of Ready

`READY_FOR_DEV`

El incremento define contratos, estados, concurrencia, persistencia, seguridad,
observabilidad y criterios verificables sin dependencias externas bloqueantes.
