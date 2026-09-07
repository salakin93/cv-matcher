# FE-002 - Administración de cuentas

## Objetivo

Permitir que un `ADMIN` consulte cuentas y aplique cambios seguros de rol o estado mediante la API administrativa, sin exponer sesiones, tokens ni datos sensibles.

## Referencias

- `docs/PRD.md`, secciones 2, 3 y 9.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-002.
- `docs/FRONTEND_ROADMAP.md`, FE-002.
- `.agents/specs/002-account-administration.md`.

## Alcance

### Incluido

- Ruta y navegación exclusivas de `ADMIN`.
- Tabla paginada con filtros exactos de rol y estado.
- Cambio de rol y activación/desactivación con confirmación explícita.
- Estados de conflicto, recarga de datos y gestión de revocación de la sesión propia.

### Excluido

- Crear cuentas, invitaciones, edición de contraseña/correo, `forcePasswordChange`, auditoría consultable y eliminación.
- Búsqueda textual o filtros no definidos por API.

## Comportamiento y reglas

- La tabla muestra sólo los campos de `AccountSummary` aprobados por 002 y ordena/renderiza el resultado backend sin enriquecerlo con datos locales.
- Filtros y página se envían como contrato; cambiar un filtro reinicia a página cero.
- Antes de una mutación, la UI describe cuenta objetivo y acción. Tras `204`, recarga la página actual.
- `LAST_ACTIVE_ADMIN`, `SELF_ADMINISTRATION_FORBIDDEN` y `EMAIL_NOT_VERIFIED` se muestran como conflictos seguros en español; no se intenta sobrescribir ni reintentar automáticamente.
- Si el cambio revoca la sesión propia y llega `401`, FE-001 limpia sesión y lleva a login.

## Contratos

- `GET /api/v1/admin/users` con `role`, `status`, `page`, `size`.
- `PATCH /api/v1/admin/users/{userId}/role` y `/status` con los DTOs de 002.
- Manejar `401`, `403`, `404 USER_NOT_FOUND`, `409`, `422 VALIDATION_ERROR` y `correlationId` según OpenAPI.

## Datos y persistencia

No persiste la lista ni respuestas administrativas fuera de memoria. El correo es PII permitida sólo dentro de la ruta ADMIN autenticada.

## Integraciones

No hay integraciones externas.

## Errores y estados

La tabla ofrece loading, vacía, error y retry. Los diálogos impiden doble envío mientras la solicitud está pendiente; `422` se muestra junto al campo o formulario aplicable.

## Seguridad y privacidad

- La ruta no se navega ni renderiza para `RECRUITER`; backend conserva la autoridad.
- No mostrar ni registrar hashes, tokens, sesiones, `lockedUntil`, fallos de login ni correlation IDs como datos analíticos.
- No incluir correo o UUID en URL, telemetry o logs cliente.

## Observabilidad

Registrar sólo fallos técnicos sin PII. Mostrar `correlationId` recibido para soporte, sin copiar payloads sensibles.

## Estrategia de pruebas

- Componentes: tabla, filtros, paginación, confirmaciones y conflictos.
- Contrato: operaciones generadas desde OpenAPI 002.
- E2E: guard ADMIN, `403`, cambios exitosos, `409`, `422`, `404` y sesión revocada.

## Criterios de aceptación

1. Sólo ADMIN puede ver y usar la pantalla; `401` y `403` no muestran datos previos.
2. Tabla, filtros y paginación coinciden con la respuesta API y no muestran campos excluidos.
3. Cambios efectivos requieren confirmación y refrescan datos tras éxito.
4. Conflictos y validaciones son seguros, accesibles y no realizan mutaciones adicionales.
5. Ningún dato administrativo sensible se persiste o registra en navegador.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | FE-001 y OpenAPI 002. | Reutilizar cliente, sesión y manejo de errores. |
| Riesgo | Revocar la propia sesión. | Delegar `401` al coordinador de sesión FE-001. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** La UI no calcula el invariante de último ADMIN; presenta el resultado backend `409`.

## Definition of Ready

`READY_FOR_DEV`

002 define endpoints, DTOs y errores administrativos necesarios.
