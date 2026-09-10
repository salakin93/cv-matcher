# 025 - Backend Foundation Refactoring

## Objetivo

Eliminar violaciones verificadas de DRY, SOLID y KISS en los incrementos backend
001--005 sin cambiar comportamiento funcional, contratos REST, autorización ni
datos de producto. El resultado separa responsabilidades, preserva límites de
módulo y hace durable el correo originado por mutaciones confirmadas.

## Referencias

- `docs/architecture.md`, secciones 4, 5, 7, 9, 10 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 001--005 y sus pruebas existentes.
- Hallazgos Technical Review: SRP Outlook/Identity, DIP Job worker, DRY de
  auditoría/correlation/error rendering y entrega de correo transaccional.

## Alcance

### Incluido

- Refactor interno de `identity`, `job`, `outlook`, `audit` y `shared`.
- Puerto `AuditPort` append-only y proveedor común de correlation ID.
- Contrato común de errores HTTP seguros y único renderer de `ApiError` para
  MVC y filtros Spring Security.
- Outbox durable para los correos de identidad: verificación, reenvío, reset y
  confirmación de cambio de correo cuando aplique.
- Separación de responsabilidades de `IdentityService` y consolidación del
  coordinador `OutlookService` sobre colaboradores tipados.
- Contratos propios del worker de jobs, sin tipos anidados de `JobService`.
- Migración Flyway nueva e inmutable para el outbox, pruebas unitarias e
  integración PostgreSQL/Testcontainers de regresión.

### Excluido

- Nuevas rutas REST, cambios de request/response JSON, roles, reglas de cuenta,
  vigencias, límites, estados de job, score o comportamiento UI.
- SMTP real, proveedor de colas externo, Kafka/RabbitMQ, microservicios,
  notificaciones de jobs, Microsoft Graph, documentos, Claude y frontend.
- Reescribir V1--V6, migrar datos de producción manualmente o cambiar secretos.
- Refactors no vinculados a los hallazgos listados.

## Decisiones arquitectónicas

1. No se crea un framework genérico de repositorios, eventos o servicios. Cada
   colaborador nuevo tiene un contrato pequeño y dueño de módulo explícito.
2. `shared` es dueño de `ApiException` (status y code), `ApiErrorRenderer` y
   `CorrelationIdProvider`. Controllers/filtros no construyen `ApiError`.
3. `audit` es dueño de `AuditPort` y de la escritura `audit_event`. Los módulos
   envían `AuditCommand(actorId, action, targetType, targetId)`; valores OAuth,
   correo, tokens, CVs y payloads siguen prohibidos.
4. `identity` registra un `identity_mail_outbox` en la misma transacción que la
   mutación que genera un correo. El dispatcher se ejecuta sólo después de
   commit y recupera pendientes al iniciar y periódicamente. No entrega SMTP
   dentro de una transacción de cuenta/sesión/token.
5. El outbox es idempotente por `id`, tiene estado `PENDING`, `SENDING`,
   `SENT` o `FAILED`, máximo tres intentos, `available_at`, `locked_until`,
   `last_error_code` seguro y timestamps UTC. Dos instancias reclaman filas con
   `FOR UPDATE SKIP LOCKED`. El contenido no almacena contraseña, refresh token,
   CV ni secreto; sólo propósito, destinatario necesario y token de acción
   cifrado. El hash existente de `account_action_token` conserva la validación;
   el ciphertext permite únicamente la entrega posterior. Log/auditoría no
   registran esos valores.
6. `job` publica contratos propios: `JobStatus`, `ClaimedJob` y comandos/resultados
   del worker viven junto al puerto, no en `JobService`. El contrato REST conserva
   valores y semántica de estado actuales.
7. `outlook` mantiene `OutlookService` como coordinador. Intentos de autorización,
   conexión/locking, access-token cache, cliente OAuth tipado y observabilidad
   son colaboradores internos; no cambia `OutlookAccessTokenPort` ni se llama
   Microsoft Graph.

## Datos y persistencia

Crear exclusivamente `V7__identity_mail_outbox.sql`.

### `identity_mail_outbox`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `purpose` | `EMAIL_VERIFICATION`, `PASSWORD_RESET` o `EMAIL_CHANGE`; no nulo. |
| `recipient_email` | Máximo 320; no se devuelve por API, log ni métrica. |
| `action_token_ciphertext` | Token de acción AES-GCM requerido sólo para entrega; nunca log/API/auditoría. |
| `status` | `PENDING`, `SENDING`, `SENT`, `FAILED`; no nulo. |
| `attempt_count` | Entero no nulo, inicia 0, máximo 3. |
| `available_at`, `locked_until` | UTC; permiten retry/recuperación. |
| `last_error_code` | Código seguro máximo 80, sin respuesta SMTP. |
| `created_at`, `sent_at`, `updated_at` | UTC no nulos según estado. |

Índices: filas entregables por `status, available_at, created_at`; y recuperación
de lease por `status, locked_until`. La migración no modifica tablas existentes.

## Comportamiento y reglas

### Identity y correo

1. Registro, reenvío, reset y cambio de correo conservan exactamente las respuestas
   públicas actuales, incluso cuando la entrega posterior falla.
2. La transacción confirma cuenta/token/outbox juntos o ninguno. El correo sólo se
   intenta tras commit.
3. Un dispatcher reclama un lote pequeño, entrega por `MailGateway`, marca `SENT`
   o reprograma con backoff acotado. Agotado el tercer intento pasa a `FAILED`.
4. Un reinicio recupera `SENDING` con lease vencido. Entrega puede ser al menos una
   vez; el contenido y enlace de token siguen siendo de un solo uso.

### Audit, errores y correlation

1. Todos los módulos 001--005 escriben auditoría mediante `AuditPort`; ninguna
   clase de aplicación contiene SQL contra `audit_event`.
2. Un único renderer produce `status`, `code`, mensaje español seguro, timestamp,
   path y correlation ID para advice MVC, authentication entry point, access denied
   y filtro de contraseña obligatoria.
3. Cada excepción de dominio aplicable implementa el contrato común; se preservan
   status/codes públicos existentes.

### Job y Outlook

1. Los consumidores del worker job dependen sólo de contratos del puerto.
2. Outlook preserva PKCE, OIDC/JWKS, AES-GCM, locking, auditoría y métricas de 005.
   Un refresh obsoleto nunca puede borrar una credencial más reciente.

## Configuración centralizada

- `application.yml` y `@ConfigurationProperties` existentes siguen siendo la
  única fuente de configuración externa.
- `identity.mail-outbox` define sólo tamaño de lote, intervalo de polling y
  backoff; valores productivos vienen de entorno y test usa valores ficticios.
- `IDENTITY_OUTBOX_ENCRYPTION_KEY` es una clave AES-256 Base64 de entorno/secret
  manager, obligatoria en `prod` y ficticia en `test`; cifra sólo el token de
  acción del outbox. No se crean host, puerto ni credencial SMTP nuevos.

## Errores y estados

- Fallo de entrega de correo no cambia la respuesta HTTP ni revierte la mutación.
- `FAILED` de outbox es observable mediante métricas/health interno, no API pública.
- Errores de auditoría o persistencia impiden la mutación para preservar trazabilidad.
- Errores de renderer no exponen stacktrace, SQL, token, correo o datos de proveedor.

## Seguridad y privacidad

- El token de acción se almacena sólo como hash en `account_action_token` y como
  ciphertext AES-GCM en outbox; el destinatario permanece en BD privada/outbox
  y en el envío SMTP necesario. Ninguno aparece en métricas, auditoría, logs ni
  respuestas.
- Workers y dispatcher no aceptan payloads externos ni tienen endpoints públicos.
- Se preservan Argon2, hashes de sesión, CSRF, roles, secretos por entorno y las
  restricciones de datos de `.agents/context/constraints.md`.

## Observabilidad

- Métricas sin PII: `identity.mail_outbox` con `outcome` (`queued`, `sent`,
  `retry`, `failed`) y gauge por estado.
- Logs seguros incluyen sólo acción técnica, código seguro y correlation ID.
- Auditoría conserva acciones actuales y no duplica eventos por reintentos SMTP.

## Estrategia de pruebas

### Unitarias

- Transición del outbox, backoff, máximo de intentos, lease vencido e idempotencia.
- `AuditPort`, renderer común y preservación de cada code/status público.
- Contratos worker sin dependencia de `JobService`.
- Colaboradores Outlook tipados, refresh obsoleto y ausencia de Graph.

### Integración Spring/PostgreSQL Testcontainers

- Registro/reset confirma token y outbox; fake mail recibe el envío sólo tras commit.
- Fallo de fake mail no revierte cuenta/token; retry y recuperación de lease son
  serializados bajo concurrencia.
- Una sola fila se reclama por dos dispatchers; auditoría append-only coherente.
- Regresión de APIs 001--005: auth, administración, vacantes, jobs y Outlook
  mantienen contratos, errores, métricas y OpenAPI.
- `./gradlew test`, `git diff --check` y migración V7 desde V1--V6.

## Criterios de aceptación

1. Ninguna mutación de identidad llama `MailGateway` dentro de su transacción.
2. V7 crea outbox durable, idempotente y recuperable sin editar V1--V6.
3. Registro, verificación, reset, login, refresh, administración, vacantes, jobs y
   Outlook conservan API, roles, estados y errores públicos previos.
4. Todas las escrituras de auditoría de módulos 001--005 pasan por `AuditPort`.
5. Todos los caminos JSON de error usan un renderer único y no contienen texto
   corrupto ni datos sensibles.
6. `MatchingJobWorkerPort` no referencia tipos de `JobService`.
7. `OutlookService` es coordinador y no construye formularios/provider URLs ni
   interpreta HTTP Microsoft; stale refresh no invalida conexión nueva.
8. Pruebas unitarias y Testcontainers cubren outbox, concurrencia, contratos y
   regresión; no usan SMTP/Microsoft/CVs/secretos reales.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Riesgo | Correo duplicado tras caída entre SMTP y `SENT`. | Token de acción es un solo uso; retry acotado y trazabilidad de outbox. |
| Riesgo | V7 contiene ciphertext de token de acción. | AES-GCM con clave de entorno, BD privada y sin API/log/auditoría/métrica. |
| Dependencia | Tests requieren PostgreSQL/Testcontainers. | Reusar configuración existente y fake `MailGateway`. |
| Riesgo | Refactor cambia contratos sin advertencia. | Pruebas de regresión OpenAPI/API antes de revisión. |

## Definition of Ready

`READY_FOR_DEV`

El alcance está limitado a deuda técnica comprobada de 001--005. No hay cambios
funcionales, de frontend ni de proveedores externos; V7 y el comportamiento de
entrega post-commit están definidos.
