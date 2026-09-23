# PRD - Estado Humano por Candidato y Reporte

## Estado

APROBADO_PARA_SPECS. Revision de arquitectura completada. Define unicamente el
estado humano; descarga, filtros, exportaciones, auditoria de esos accesos y
perfiles se especifican por separado.

## Objetivo

Permitir que el equipo de reclutamiento registre y comparta su estado operativo
sobre un candidato dentro de un reporte, sin automatizar decisiones de
contratación ni modificar los resultados calculados.

## Usuarios

Los usuarios con rol `RECRUITER` y `ADMIN` pueden consultar y actualizar el
estado. Todos los reclutadores autorizados ven el estado compartido.

## Alcance funcional

- Cada candidato dentro de un reporte inicia en `PENDIENTE`.
- Un usuario autorizado puede establecer explícitamente cualquiera de estos
  valores: `PENDIENTE`, `EN_REVISION`, `PRESELECCIONADO` y `DESCARTADO`.
- El estado se guarda para la relación candidato–reporte. Una misma persona
  puede tener un estado diferente en otra vacante o en otro reporte histórico.
- Al guardar un cambio, los demás reclutadores ven el valor actualizado.
- Se conserva para auditoría administrativa quién realizó el cambio y cuándo.
  Ese historial no se muestra en la vista ordinaria del reclutador.
- Si dos usuarios intentan guardar cambios al mismo tiempo, sólo se conserva el
  primer cambio confirmado. El segundo usuario debe recargar el dato antes de
  volver a guardarlo.

## Reglas de negocio

| ID | Regla |
| --- | --- |
| BR-006-01 | El estado inicial de toda entrada candidato–reporte es `PENDIENTE`. |
| BR-006-02 | Cualquier estado puede cambiarse directamente a cualquiera de los otros estados permitidos. No existen transiciones obligatorias. |
| BR-006-03 | El estado es una decisión humana operativa y no depende del score, de Claude ni de una regla automática. |
| BR-006-04 | `DESCARTADO` no elimina al candidato, no modifica su CV ni representa una decisión automática de contratación. |
| BR-006-05 | Cambiar el estado no modifica el score, ranking, evidencias, análisis, disponibilidad, perfil ni CV. |
| BR-006-06 | El estado no se propaga a otras vacantes, otros reportes ni versiones históricas. |
| BR-006-07 | Cada cambio efectivo queda auditado para consulta exclusiva de administradores. |
| BR-006-08 | El estado sólo se modifica en versiones de reporte `COMPLETED` o `COMPLETED_WITH_WARNINGS`. |
| BR-006-09 | Guardar el mismo estado no es un cambio efectivo: no modifica fecha, versión ni auditoría. |

## Fuera de alcance

- Contratar, rechazar o descartar candidatos automáticamente.
- Comentarios, motivos de descarte, asignación de responsable, notificaciones o
  flujos de aprobación.
- Modificar el perfil del candidato, su disponibilidad, CV, análisis, score,
  ranking, requisitos o la vacante.
- Mostrar el historial de estados a reclutadores en la vista ordinaria.

## Criterios de aceptación

### AC-006-01 — Estado inicial

Dado un candidato incluido en un reporte, cuando el reclutador abre el reporte,
entonces el candidato muestra `PENDIENTE` si nadie cambió su estado antes.

### AC-006-02 — Cambio compartido

Dado un reclutador autorizado y un candidato de un reporte, cuando el
reclutador establece `EN_REVISION`, `PRESELECCIONADO`, `DESCARTADO` o
`PENDIENTE`, entonces el nuevo estado queda visible para los demás reclutadores
autorizados.

### AC-006-03 — Aislamiento

Dada una persona que aparece en dos reportes, cuando un reclutador cambia su
estado en uno de ellos, entonces el estado en el otro reporte no cambia.

### AC-006-04 — Sin efectos sobre el análisis

Cuando un reclutador cambia un estado humano, entonces score, ranking,
evidencias, disponibilidad, perfil y CV permanecen sin cambios.

### AC-006-05 — Edición simultánea

Dado que dos reclutadores abrieron el mismo estado, cuando ambos intentan
guardarlo con cambios distintos, entonces se conserva el primer cambio y el
segundo recibe una indicación para recargar antes de intentarlo nuevamente.

### AC-006-06 — Auditoría

Cuando un cambio efectivo se guarda, entonces queda registrado para auditoría
administrativa sin exponer el historial en la vista ordinaria del reclutador.

## Definition of Ready

`READY_FOR_ARCHITECT`
