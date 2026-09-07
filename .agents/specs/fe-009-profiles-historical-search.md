# FE-009 - Perfiles y búsqueda histórica

## Objetivo

Ofrecer directorio compartido, disponibilidad, correcciones versionadas y búsqueda histórica sólo tras confirmación explícita y elegible.

## Referencias

- `docs/PRD.md`, secciones 7 y 8.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-009.
- `docs/FRONTEND_ROADMAP.md`, FE-009.
- `.agents/specs/016-candidate-profile-corrections.md` a `018-historical-candidate-search.md`.

## Alcance

### Incluido

- Directorio compartido, disponibilidad, correcciones con versión, elegibilidad y confirmación de búsqueda histórica.
- Resultados históricos devueltos por backend.

### Excluido

- Fusión manual, descarga de CV, papelera, privacidad y búsqueda automática.

## Comportamiento y reglas

- El directorio es compartido; la UI presenta conflicto de versión y no sobrescribe correcciones.
- La búsqueda histórica permanece bloqueada hasta que backend indique elegibilidad y el reclutador complete una confirmación visible.
- El valor sugerido de 70 es sólo presentación backend/producto, no un cálculo o filtro implícito del cliente.

## Contratos

Consumir OpenAPI 016-018 para perfiles, disponibilidad, correcciones, elegibilidad, confirmación y resultados.

## Datos y persistencia

No persistir correcciones, criterios, resultados o PII de candidato fuera de memoria.

## Integraciones

No acceder a archivos, Graph ni Claude.

## Errores y estados

Mostrar vacío, no elegible, confirmación requerida, conflicto, loading, warning y error seguro.

## Seguridad y privacidad

No iniciar búsqueda por URL, recarga o navegación sin confirmación vigente. No exponer PII en telemetry o URL.

## Observabilidad

Eventos técnicos sin criterios de búsqueda ni datos de perfil.

## Estrategia de pruebas

Componentes de elegibilidad/confirmación/conflicto, contrato y E2E que pruebe que la búsqueda no inicia sin confirmación.

## Criterios de aceptación

1. Reclutadores ven y corrigen perfiles compartidos con control de versión.
2. Búsqueda histórica sólo se ejecuta después de elegibilidad y confirmación explícita.
3. Resultados no cambian reportes históricos ni muestran datos no autorizados.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI 016-018 y FE-006 no aprobados. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** Elegibilidad y búsqueda pertenecen al backend; la confirmación cliente es una barrera UX adicional.

## Definition of Ready

`BLOCKED`
