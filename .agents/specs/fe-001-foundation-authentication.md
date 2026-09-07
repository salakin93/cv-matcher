# FE-001 - Fundación y autenticación

## Objetivo

Entregar la base SPA React/TypeScript y los flujos de identidad para que una persona se registre, verifique correo, inicie/cierre sesión y recupere acceso sin persistir credenciales en el navegador.

## Referencias

- `docs/PRD.md`, secciones 2, 3 y 10.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-001.
- `docs/FRONTEND_ROADMAP.md`, FE-001.
- `.agents/specs/001-identity-account-foundation.md`.
- `docs/architecture.md`, secciones 3, 5 y 9.

## Alcance

### Incluido

- Shell SPA responsivo con Tailwind CSS y shadcn/ui, cliente TypeScript generado desde OpenAPI y manejo común de errores.
- Registro, confirmación y reenvío de verificación, login, refresh, logout y `me`.
- Solicitud y confirmación de reset, cambio autenticado de contraseña y solicitud/confirmación de cambio de correo.
- Guards de rutas, layout autenticado y redirección al login tras `401` o sesión revocada.

### Excluido

- Administración, vacantes, reportes, documentos, notificaciones, perfiles e integraciones.
- Almacenamiento persistente de tokens, secretos o datos personales.

## Comportamiento y reglas

- La interfaz está en español; escritorio es prioritario y móvil es funcional.
- El access token vive sólo en memoria. Refresh y CSRF usan las cookies y header definidos por backend; no se leen ni persisten refresh tokens.
- Las respuestas neutrales de registro, reenvío y reset se muestran sin inferir existencia de correo.
- Tras logout, expiración, revocación o cambio de credenciales, se elimina el estado autenticado y se reemplaza la navegación protegida por login.
- `forcePasswordChange` bloquea la navegación funcional hasta completar el flujo backend existente.

## Contratos

- Consumir exclusivamente OpenAPI de `001`: `/auth/register`, verificación, login, refresh, logout, `me`, reset y cambios de contraseña/correo.
- Adjuntar bearer sólo desde memoria; enviar `X-CSRF-TOKEN` sólo en operaciones basadas en cookie según el contrato generado.
- Mapear `401`, `403`, `409` y `422` a estados seguros y mostrar `correlationId` cuando exista.

## Datos y persistencia

No crea persistencia de producto. El estado de sesión, formularios y respuestas vive en memoria y se descarta al recargar o cerrar sesión.

## Integraciones

No llama SMTP, Outlook, Claude ni servicios externos desde navegador.

## Errores y estados

Cada vista define loading, validación local accesible, envío, éxito, error seguro y retry cuando sea seguro repetir. No se muestra texto de token, stack trace ni detalles de autenticación.

## Seguridad y privacidad

- No usar `localStorage`, `sessionStorage`, URL, telemetry o consola para JWT, cookies, contraseñas, tokens o PII no devuelta por API.
- Los guards mejoran UX pero no sustituyen autorización backend.
- Formularios de contraseña desactivan autocompletado inapropiado y limpian valores al completar o abandonar el flujo.

## Observabilidad

Sólo telemetría técnica aprobada sin PII, secretos ni contenido de formularios. Las pantallas de error pueden presentar el `correlationId` del servidor.

## Estrategia de pruebas

- Componentes: validación, estados neutros, foco y mensajes accesibles.
- Contrato: cliente generado contra OpenAPI 001.
- E2E: registro, login, refresh, logout, expiración/revocación y guards.

## Criterios de aceptación

1. Una persona puede completar los flujos definidos por 001 y sólo recibe mensajes españoles seguros.
2. Una sesión revocada o expirada no deja contenido protegido visible y redirige a login.
3. JWT, refresh, CSRF, contraseñas y tokens no se persisten ni exponen en cliente.
4. La interfaz es usable con teclado, lector de pantalla, escritorio y móvil.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | OpenAPI y CORS de 001. | Generar cliente y probar contra entorno local autorizado. |
| Riesgo | Bucle de refresh ante `401`. | Un único intento coordinado; después limpiar sesión. |
| Dependencia | El módulo `cv-matcher-frontend/` se crea durante FE-001 con npm y scripts de validación. | Mantenerlo separado del módulo backend. |
| Dependencia | Tipos TypeScript generados con `openapi-typescript` desde `/v3/api-docs`. | Versionar configuración y comando reproducible; no editar tipos generados manualmente. |
| Dependencia | El origen local `http://localhost:5173` debe figurar en `CORS_ALLOWED_ORIGINS`. | Configurarlo en el entorno local, sin comodines. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** React 19, TypeScript estricto y Vite conforme a `docs/architecture.md`.
- **ARCHITECTURAL DECISION:** El token access sólo reside en memoria; el refresh es cookie gestionada por backend.
- **ARCHITECTURAL DECISION:** FE-001 crea `cv-matcher-frontend/`, usa npm y genera tipos con `openapi-typescript` desde `/v3/api-docs`.
- **ARCHITECTURAL DECISION:** Tailwind CSS y shadcn/ui son la base visual y de componentes accesibles; MUI y Ant Design no forman parte del stack UI principal.

## Definition of Ready

`READY_FOR_DEV`

El contrato de 001, el módulo frontend, npm, generación OpenAPI y origen CORS
local están definidos.
