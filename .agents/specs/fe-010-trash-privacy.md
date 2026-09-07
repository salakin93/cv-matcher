# FE-010 - Papelera y privacidad

## Objetivo

Permitir gestionar restauración de CVs en papelera y solicitudes ADMIN de eliminación por privacidad con explicaciones claras de retención e irreversibilidad.

## Referencias

- `docs/PRD.md`, secciones 5 y 9.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-010.
- `docs/FRONTEND_ROADMAP.md`, FE-010.
- `.agents/specs/019-cv-trash-and-restore.md` a `021-privacy-deletion.md`.

## Alcance

### Incluido

- Lista de papelera, restauración, vencimiento y flujo ADMIN de eliminación por privacidad con confirmación fuerte y seguimiento de estado.

### Excluido

- Recuperación tras purga, exposición de PII eliminada, exportación de papelera y descarga de archivos.

## Comportamiento y reglas

- La papelera explica que los CVs quedan excluidos de reportes y búsquedas; restauración sólo se ofrece cuando backend lo permite dentro de 180 días.
- Privacidad requiere confirmación fuerte definida por el contrato, muestra irreversibilidad y no promete reversión.
- Después de eliminación, la UI elimina datos personales locales y presenta sólo el estado seguro/anónimo proporcionado por backend.

## Contratos

Consumir OpenAPI 019-021 para papelera, restauración, vencimiento, solicitud/estado de privacidad y errores autorizados.

## Datos y persistencia

No almacenar referencias de archivo, rutas, PII eliminada ni confirmaciones persistentes.

## Integraciones

Purgas, archivos y anonimización son server-side.

## Errores y estados

Estados loading, vacía, restaurable, vencida, confirmación, procesando, completada, fallo seguro y retry sólo cuando API lo permita.

## Seguridad y privacidad

- Cualquier reclutador autorizado usa papelera; sólo ADMIN ejecuta privacidad.
- No renderizar ni registrar archivos, rutas, contenido de CV o PII tras eliminación.

## Observabilidad

Sin datos de candidato, documento o solicitud en logs/telemetry de cliente.

## Estrategia de pruebas

Componentes de retención/confirmación, contrato y E2E de restauración, expiración, autorización ADMIN e irreversibilidad.

## Criterios de aceptación

1. Papelera y restauración reflejan exactamente disponibilidad y vencimiento backend.
2. Privacidad requiere confirmación explícita y sólo ADMIN puede iniciarla.
3. La interfaz no conserva ni vuelve a mostrar PII eliminada.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI 019-021 y FE-007/FE-009 no aprobados. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** Purga y anonimización son irreversibles y controladas por backend.

## Definition of Ready

`BLOCKED`
