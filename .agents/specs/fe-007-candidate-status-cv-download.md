# FE-007 - Estado humano y descarga de CV

## Objetivo

Permitir actualizar el estado humano de un candidato dentro de un reporte y descargar su CV sólo por un endpoint autenticado.

## Referencias

- `docs/PRD.md`, sección 7.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-007.
- `docs/FRONTEND_ROADMAP.md`, FE-007.
- `.agents/specs/013-report-candidate-human-status.md` y `014-protected-cv-download.md`.

## Alcance

### Incluido

- Selector de `PENDIENTE`, `EN_REVISION`, `PRESELECCIONADO` y `DESCARTADO` por candidato-reporte.
- Conflicto de versión, confirmación de cambio y descarga autenticada del CV seleccionado.

### Excluido

- Enlaces permanentes, descarga por UUID en URL, exportación, comentarios y modificación de score.

## Comportamiento y reglas

- El estado humano se limita a la relación candidato-reporte y la UI no afirma que se propague a otros reportes.
- Cada actualización envía versión esperada; un conflicto conserva la selección local y exige recarga/cancelación.
- La descarga se inicia mediante respuesta autenticada del endpoint y no almacena URL, ruta o archivo después de entregarlo al navegador.

## Contratos

Consumir exclusivamente OpenAPI 013-014 para estado versionado, conflictos y descarga autorizada.

## Datos y persistencia

No persistir archivo, URL temporal, nombre de ruta ni estado de candidato fuera de la respuesta autorizada.

## Integraciones

No accede a filesystem, storage ni documentos de forma directa.

## Errores y estados

Mostrar loading de actualización/descarga, conflicto, `401`, `403`, no disponible y fallo seguro; evitar doble descarga accidental.

## Seguridad y privacidad

No usar enlaces públicos/permanentes ni registrar datos de CV. Backend autoriza cada descarga.

## Observabilidad

No telemetría con identificadores de documento, archivo, URL o PII de CV.

## Estrategia de pruebas

Componentes de selector/conflicto, contrato y E2E de cambio de estado, `409`, descarga autorizada y rechazo no autorizado.

## Criterios de aceptación

1. El estado humano se actualiza sólo para el candidato-reporte y controla versión.
2. Un conflicto no sobrescribe datos ni oculta la necesidad de recargar.
3. La descarga funciona autenticadamente sin exponer o persistir enlaces/rutas.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI 013-014 y FE-006 no aprobados. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** La descarga se autoriza por request backend, no por conocimiento de un identificador.

## Definition of Ready

`BLOCKED`
