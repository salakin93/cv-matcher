# Especificaciones frontend — FE-001 a FE-011

## Reglas comunes

React y TypeScript; contratos generados desde OpenAPI; rutas protegidas; sesión
en memoria y refresh mediante cookie segura. Nunca guardar JWT, tokens, secretos,
CVs, rutas, hashes o PII no devuelta por API en `localStorage`, URLs, telemetry
o logs de cliente. Todas las fases incluyen estados loading/empty/warning/retry,
accesibilidad de teclado/lector de pantalla y manejo uniforme de `401`, `403`,
`409`, `422` y correlation ID.

## FE-001 — Fundación y autenticación

**Depende de:** backend 001.  
**Incluye:** shell SPA, cliente API tipado, registro, verificación, login,
logout, refresh, reset de contraseña, guards y layout autenticado.  
**Excluye:** perfil avanzado, administración, UI de vacantes.  
**Salida:** una sesión expirada/revocada elimina vistas protegidas y lleva a login.

## FE-002 — Administración de cuentas

**Depende de:** 002, FE-001.  
**Incluye:** ruta ADMIN, tabla paginada, filtros, rol, estado, confirmaciones e
información de conflicto del último administrador.  
**Excluye:** creación manual, invitaciones, cambio administrativo de contraseña/correo.  
**Salida:** sólo ADMIN puede gestionar cuentas sin mostrar tokens, hashes o sesiones.

## FE-003 — Gestión de vacantes

**Depende de:** 003, FE-001.  
**Incluye:** listado, detalle, crear/editar, requisitos ordenables, fechas Bolivia,
archivo/reactivación y resolución visual de `VERSION_CONFLICT`.  
**Excluye:** jobs, candidatos, reportes y búsqueda histórica.  
**Salida:** UI envía `expectedVersion`, no sobrescribe cambios concurrentes y no
calcula reglas backend.

## FE-004 — Trabajos de reporte

**Depende de:** 004–006, FE-003.  
**Incluye:** solicitar job, tarjeta de estado, polling/backoff, cancelación, retry,
warnings y navegación a reporte.  
**Excluye:** Inbox, mensajes, adjuntos, logs Graph y análisis.  
**Salida:** la UI nunca bloquea esperando trabajo ni expone metadatos de correo.

## FE-005 — Integración Outlook

**Depende de:** 005, FE-001.  
**Incluye:** panel ADMIN de estado, iniciar/reautorizar OAuth y retorno seguro.  
**Excluye:** tokens, tenant, mailbox, Inbox o configuración de secretos.  
**Salida:** navegador sólo abre URL entregada por backend y no conserva state/code.

## FE-006 — Reportes y ranking

**Depende de:** 009–012, FE-004.  
**Incluye:** versiones, detalle, ranking, scores, assessments, evidencias,
warnings y paginación.  
**Excluye:** edición de score, reordenamiento frontend, exportación y estados humanos.  
**Salida:** orden, score y desempates se renderizan exactamente como API.

## FE-007 — Estado humano y descarga de CV

**Depende de:** 013–014, FE-006.  
**Incluye:** selector de estado candidato–reporte con versión/conflicto y descarga
protegida del CV seleccionado.  
**Excluye:** enlaces permanentes, descargas por UUID, exportación y comentarios.  
**Salida:** descargar sólo mediante endpoint autenticado; no persistir URL/archivo.

## FE-008 — Exportaciones y notificaciones

**Depende de:** 015, 022, FE-006.  
**Incluye:** solicitar PDF/XLSX, estado, descarga al completar, expiración y
bandeja in-app con marcar leído.  
**Excluye:** envío manual de correo, plantillas y enlaces públicos.  
**Salida:** doble click no duplica solicitudes y notificación no revela PII sensible.

## FE-009 — Perfiles y búsqueda histórica

**Depende de:** 016–018, FE-006.  
**Incluye:** directorio compartido, disponibilidad, correcciones con versión,
elegibilidad, confirmación explícita y resultados históricos.  
**Excluye:** fusión manual, descarga, papelera, privacidad y búsqueda automática.  
**Salida:** búsqueda histórica sólo inicia tras confirmación válida visible al usuario.

## FE-010 — Papelera y privacidad

**Depende de:** 019–021, FE-007/FE-009.  
**Incluye:** papelera, restauración, vencimiento y flujo ADMIN de privacidad con
confirmación fuerte y seguimiento de estado.  
**Excluye:** recuperación tras purga, PII eliminada y exportación de papelera.  
**Salida:** operaciones irreversibles se explican claramente y no prometen reversión.

## FE-011 — Operación administrativa

**Depende de:** 023–024, FE-002.  
**Incluye:** configuración allowlisted, estado de integraciones y auditoría
paginada/filtrable para ADMIN.  
**Excluye:** secretos, tokens, payloads de proveedor, CVs, cambios de migración.  
**Salida:** panel operativo no renderiza datos secretos ni permite valores fuera
de allowlist backend.

## Gate de entrega por fase

- Pruebas de componentes y E2E de rutas críticas.
- Revisión visual responsive y accesibilidad.
- Validación de contratos con OpenAPI y manejo de errores.
- Revisión QA y seguridad/privacidad antes de liberar la fase.
