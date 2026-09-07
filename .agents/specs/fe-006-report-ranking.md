# FE-006 - Reportes y ranking

## Objetivo

Presentar versiones inmutables de reporte, ranking, puntajes, assessments, evidencias y advertencias exactamente como los entrega backend.

## Referencias

- `docs/PRD.md`, secciones 6 y 7.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-006.
- `docs/FRONTEND_ROADMAP.md`, FE-006.
- `.agents/specs/009-structured-cv-analysis.md` a `012-immutable-report-ranking.md`.

## Alcance

### Incluido

- Lista de versiones, detalle, Top 5, ranking paginado, assessments, evidencias y warnings.
- Filtros y búsqueda definidos por el contrato de reporte.

### Excluido

- Editar score, ordenar o desempatar en cliente, exportar, descargar CV y cambiar estado humano.

## Comportamiento y reglas

- Score total, mandatory score, bonus, orden, empates y estados de requisito se renderizan como API; no se recalculan.
- `NO_DEMOSTRADO` permanece visible y se distingue de `NO_CUMPLE`.
- Cada versión se identifica como inmutable; warnings son seguros y no contienen datos de documento/proveedor fuera del DTO autorizado.

## Contratos

Usar OpenAPI aprobado de 009-012 para versiones, resumen, ranking, filtros, assessments, evidencias y paginación.

## Datos y persistencia

No persistir resultados, evidencias ni PII de candidatos en caché durable.

## Integraciones

No llama Claude, Graph ni almacenamiento de documentos.

## Errores y estados

Loading, vacío, paginación, warning, retry y error seguro para cada vista.

## Seguridad y privacidad

Rutas autenticadas. No incluir PII, evidencias o resultados en URL, logs o telemetry; no mostrar archivos ni rutas.

## Observabilidad

Sólo eventos técnicos agregados sin identificadores de candidato o contenido de evidencia.

## Estrategia de pruebas

Componentes de ranking/assessments, contrato OpenAPI, E2E de versión, filtros, paginación y estados `NO_DEMOSTRADO`.

## Criterios de aceptación

1. Ranking y Top 5 respetan orden y puntajes backend.
2. La UI no calcula ni altera análisis, score o desempates.
3. Versiones, warnings, evidencias y estados son accesibles y seguros.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI de resultados 009-012 y FE-004 pendientes. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** Respuestas de Claude ya validadas son datos renderizables; el cliente no las interpreta como instrucciones.

## Definition of Ready

`BLOCKED`
