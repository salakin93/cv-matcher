# 013 - Report Candidate Human Status

## Objetivo

Entregar el estado operativo humano y compartido de cada candidato dentro de
una versión inmutable de reporte. `RECRUITER` y `ADMIN` pueden marcar una entrada
como pendiente, en revisión, preseleccionada o descartada, con concurrencia y
auditoría. El cambio nunca modifica perfil, documento, análisis, score, ranking
ni otro reporte.

## Referencias

- `docs/PRD.md`, secciones 6 y 7.
- `docs/PRODUCT_BACKLOG.md`, Epic 4, Feature 4.3.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Spec 012.

## Alcance

### Incluido

- Estado por `report_candidate`, compartido entre reclutadores.
- Estados `PENDIENTE`, `EN_REVISION`, `PRESELECCIONADO`, `DESCARTADO`.
- Cambio con `expectedVersion`, idempotencia y control optimista.
- Lectura de estado/version/timestamp en detalle de reporte.
- Auditoría, métricas sin PII, errores JSON seguros, OpenAPI y pruebas.

### Excluido

- Contratación, rechazo automático, workflow, comentarios, responsable o alertas.
- Cambiar score, ranking, assessment, perfil, vacante, documento o reporte.
- Descarga, exportación, UI, búsqueda, directorio, papelera, privacidad y
  correcciones de candidato.
- Historial de decisiones expuesto a usuarios en este incremento.

## Decisiones arquitectónicas

1. El estado pertenece a `report_candidate`, no a `candidate_profile`. Una misma
   persona puede tener estados distintos en versiones/vacantes sin propagación.
2. `reporting` guarda el estado en una tabla mutable separada de snapshots
   inmutables; no reescribe reporte, ranking ni score.
3. Toda mutación exige versión actual. `expectedVersion` desactualada retorna
   `409 VERSION_CONFLICT` sin cambiar estado, timestamp, auditoría o métrica.
4. Ningún score, Claude, requisito o búsqueda histórica cambia estado humano.
5. Solicitar el estado actual con versión actual devuelve `204` sin efectos.

## Modelo y persistencia

Crear exclusivamente `V14__report_candidate_human_status.sql`; no modificar
V1–V13.

### `report_candidate_human_status`

| Columna | Regla |
| --- | --- |
| `report_candidate_id` | UUID PK/FK a `report_candidate`, `ON DELETE RESTRICT`. |
| `status` | Enum permitido, inicia `PENDIENTE`. |
| `version` | `bigint` no nulo, inicia 0. |
| `updated_by_user_id` | UUID no nulo del último actor. |
| `created_at`, `updated_at` | `timestamptz` UTC no nulos. |

Crear fila inicial al componer cada `report_candidate`. No almacenar motivo,
comentario, correo, nombre, score, copia de PII ni estado global de perfil.

### `report_candidate_status_event`

Evento técnico append-only: UUID, candidato de reporte, actor UUID, estado
anterior/nuevo, versión anterior/nueva, correlation ID y timestamp. No exponer
esta tabla por API en esta spec.

## Contrato API

JWT válido, cuenta `ACTIVE`, sesión persistida y rol efectivo `RECRUITER` o
`ADMIN` son obligatorios.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| `PATCH /api/v1/report-versions/{reportVersionId}/candidates/{reportCandidateId}/human-status` | `HumanStatusRequest` | `204`. |

```json
{
  "status": "EN_REVISION",
  "expectedVersion": 0
}
```

Detalle de reporte añade `humanStatus`, `humanStatusVersion` y
`humanStatusUpdatedAt`; no devuelve actor ni historial. Campos desconocidos,
enum inválido, estado ausente o versión negativa devuelven `422 VALIDATION_ERROR`.

## Reglas de negocio

1. Sólo se modifica una entrada perteneciente al `reportVersionId` de ruta. Una
   entrada inexistente o de otra versión devuelve `404` seguro sin filtración.
2. Cualquier estado puede cambiar explícitamente a cualquiera de los cuatro; no
   existe flujo implícito ni transición bloqueada por score.
3. La transacción bloquea fila, compara versión y actualiza estado/version/
   timestamps/actor junto con evento y auditoría una sola vez.
4. Estado igual con versión actual es idempotente. Con versión antigua retorna
   `409 VERSION_CONFLICT`, incluso si el estado solicitado coincide.
5. Dos cambios concurrentes con la misma versión dejan un éxito y un `409`; el
   estado final corresponde enteramente a una solicitud.
6. Reporte inexistente, job no terminado o entrada no elegible no crea estado
   mediante atajo. Datos de reclutamiento son compartidos entre roles permitidos.

## Errores y seguridad

| Situación | HTTP / código |
| --- | --- |
| JWT inválido, revocado o cuenta no activa | `401 UNAUTHENTICATED` |
| Rol no autorizado | `403 FORBIDDEN` |
| Versión/candidato/relación inexistente | `404 REPORT_CANDIDATE_NOT_FOUND` |
| Versión desactualizada | `409 VERSION_CONFLICT` |
| JSON/estado/versión inválidos | `422 VALIDATION_ERROR` |

- Errores comunes con correlation ID, sin nombre, correo, score, evidencia,
  actor, UUID ajeno ni SQL.
- El endpoint no devuelve cuerpo y no expone actor/eventos. Métricas no llevan
  candidato, reporte o usuario como etiqueta.

## Auditoría y observabilidad

Cambio efectivo agrega `REPORT_CANDIDATE_HUMAN_STATUS_CHANGED` a `audit_event`,
con objetivo `REPORT_CANDIDATE`, actor, timestamp y correlation ID. No incluir
PII, score, estado anterior/nuevo o comentario en la auditoría transversal.

Métricas sin PII:

- `reporting.human_status_changes` con `to_status` cerrado y `outcome`
  (`success`, `conflict`);
- `reporting.human_status_requests` con `result` (`changed`, `idempotent`).

## OpenAPI y configuración

- Documentar PATCH, bearer, body, `204`, `401`, `403`, `404`, `409`, `422` y
  ejemplo español seguro.
- Actualizar detalle de reporte sin actor/historial. No agregar secretos,
  perfiles, scheduler ni integraciones externas.
- `test` usa reportes/perfiles sintéticos y PostgreSQL Testcontainers.

## Estrategia de pruebas

### Unitarias

- DTO, enums, compare-and-set, idempotencia y errores.
- Pertenencia versión-candidato y ausencia de efectos sobre score/ranking/perfil.

### Integración Spring/PostgreSQL Testcontainers

- `RECRUITER`/`ADMIN` con sesión vigente cambian/leen estado; `401`/`403` seguros.
- Inicial `PENDIENTE`, transiciones e idempotencia sin versión/auditoría/métrica
  extra; `404` de relación incorrecta.
- Dos PATCH concurrentes distintos: un `204`, un `409` y estado/version coherente.
- Cambio no modifica snapshots, score, assessment, candidato, vacante u otros
  reportes del perfil; V14 desde V1–V13 y regresión completa.

## Criterios de aceptación

1. Cada entrada inicia `PENDIENTE`; estado pertenece sólo a candidato–versión.
2. `RECRUITER`/`ADMIN` activos pueden establecer cuatro estados; sin bearer es
   `401` y rol no autorizado `403` seguro.
3. Cambio válido es `204`, aumenta versión una vez y se ve en detalle sin actor.
4. Idempotencia no cambia timestamp/versión/auditoría/métrica; versión antigua es
   siempre `409`.
5. Concurrencia deja un único resultado coherente y un `409 VERSION_CONFLICT`.
6. Candidato ajeno/inexistente devuelve `404` sin modificar filas.
7. Estado no altera score, ranking, requisitos, perfil, documento, vacante ni
   otros reportes.
8. No contrata, descarta automáticamente ni ejecuta acciones externas.
9. Auditoría, OpenAPI, logs y métricas no exponen PII, score, evidencia, actor o
   UUID como etiqueta.
10. V14 y Testcontainers cubren autorización, idempotencia, concurrencia y
    aislamiento sin UI, descarga, exportación, directorio o notificaciones.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 012 entrega entradas inmutables. | Crear overlay al componer versión. |
| Riesgo | Estado contamina otro reporte. | PK por candidato de reporte y pruebas. |
| Riesgo | Actualización perdida. | `expectedVersion`, bloqueo y carrera PostgreSQL. |
| Riesgo | Se interpreta como decisión automática. | Acción explícita sin automatización. |
| Dependencia futura | Historial/motivo/alertas no existen. | Evento interno sin exposición aún. |

## Definition of Ready

`READY_FOR_DEV`
