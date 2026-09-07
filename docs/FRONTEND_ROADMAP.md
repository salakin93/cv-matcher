# Roadmap frontend — CV Matcher

## Principios

- React 19, TypeScript estricto, Vite y npm en `cv-matcher-frontend/`; cliente
  API tipado desde OpenAPI aprobada con `openapi-typescript`.
- Tailwind CSS y shadcn/ui son la base de estilos y componentes accesibles. No
  se usan MUI ni Ant Design como librerías UI principales.
- Sesión en memoria; refresh sólo con cookie segura backend; nunca JWT en localStorage.
- UI española, accesible, responsive, con loading/empty/warning/retry/error.
- `401` vuelve a login; `403` muestra acceso denegado; correlation ID sin detalle sensible.
- Frontend no calcula score/ranking, no llama Graph/Claude y no guarda secretos,
  CVs, tokens, hashes, rutas ni PII no entregada por API.

## Entregas

| Fase | Alcance frontend | Dependencias backend |
| --- | --- | --- |
| FE-001 | Fundación SPA, registro, verificación, login, refresh, logout y recuperación. | 001 |
| FE-002 | Administración ADMIN de cuentas, filtros, rol, estado y conflictos. | 002 |
| FE-003 | Vacantes: listado, formulario, requisitos, archivo, reactivación y version conflict. | 003 |
| FE-004 | Solicitud, polling, cancelación/retry y estado de trabajos. | 004–006 |
| FE-005 | Panel ADMIN Outlook y callback OAuth seguro. | 005 |
| FE-006 | Versiones, ranking, assessments, warnings y detalle de reportes. | 009–012 |
| FE-007 | Estado humano candidato–reporte y descarga protegida de CV. | 013–014 |
| FE-008 | Solicitud/estado/descarga de exportación y bandeja de notificaciones. | 015, 022 |
| FE-009 | Perfiles, disponibilidad, correcciones y búsqueda histórica confirmada. | 016–018 |
| FE-010 | Papelera, restauración, expiración y privacidad ADMIN. | 019–021 |
| FE-011 | Configuración ADMIN, integraciones y consulta de auditoría. | 023–024 |

## Criterios por bloque

### FE-001 — Autenticación

Rutas públicas y protegidas, layout autenticado, cierre de sesión y expiración.
No mostrar datos protegidos si backend responde `401`/`403`.

### FE-002 y FE-003 — Administración y vacantes

Tablas paginadas, filtros exactos, validación de formularios, confirmaciones e
interfaz de conflictos que recarga en vez de sobrescribir datos automáticamente.

### FE-004 a FE-006 — Trabajos, Outlook y resultados

Jobs asíncronos con polling/backoff; OAuth sólo navega a URL backend; ranking se
renderiza en el orden backend y nunca recalcula scores o desempates.

### FE-007 y FE-008 — Acciones sobre resultados

Estados humanos con control de versión. Descarga/exportación sólo vía endpoints
autenticados, sin enlaces permanentes, rutas, storage key o doble solicitud.

### FE-009 y FE-010 — Ciclo de vida de candidato

Correcciones compartidas con conflicto visible. Búsqueda histórica exige mostrar
elegibilidad y confirmación explícita. Papelera y privacidad explican retención
e irreversibilidad sin prometer recuperación.

### FE-011 — Operación

Sólo ADMIN ve configuración, estado de integraciones y auditoría. Nunca renderiza
secretos, tokens, payloads de proveedor, documentos, rutas o PII no autorizada.

## Gates de calidad

- Componentes y contratos TypeScript testeados; E2E de login, autorización,
  conflictos, polling, descarga y confirmaciones destructivas.
- Accesibilidad: foco, labels, teclado, contraste y lectores de pantalla.
- Privacidad: no PII/secretos en consola, telemetry, caché persistente, URL o
  pantallas de error.
- QA responsive/red lenta; seguridad confirma que guards visuales no sustituyen
  la autorización backend.

## Orden recomendado

Redactar e implementar primero FE-001, FE-003, FE-004 y FE-006. Continuar con
los demás bloques sólo cuando sus contratos backend estén aprobados y disponibles.
