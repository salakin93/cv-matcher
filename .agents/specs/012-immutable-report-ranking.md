# 012 - Immutable Report Ranking

## Objetivo

Entregar la composición de una versión inmutable de reporte y ranking
reproducible por vacante. El backend usa perfiles deduplicados, CV seleccionado,
evaluaciones y scores ya calculados para publicar resultados explicables a
`RECRUITER` y `ADMIN`, y completar el job con o sin advertencias. No permite
alterar resultados, descargar archivos, exportar ni cambiar estados humanos.

## Referencias

- `docs/PRD.md`, secciones 4, 6 y 7.
- `docs/PRODUCT_BACKLOG.md`, Epic 4, Feature 4.2.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 003, 004, 009, 010 y 011.

## Alcance

### Incluido

- Composición worker de `report_version`, `report_candidate` y
  `requirement_assessment` desde snapshots ya persistidos.
- Ranking determinista: score total, obligatorio, cantidad de obligatorios
  cumplidos, fecha de CV y UUID como desempate estable.
- Resultados inmutables con configuración de vacante, algoritmo, modelo/prompt,
  documento seleccionado y advertencias snapshot.
- Consulta paginada de versiones de una vacante y detalle de una versión.
- Lectura compartida por `RECRUITER`/`ADMIN`, con PII mínima de candidato
  autorizada para revisar el reporte.
- Transición de job a `COMPLETED` o `COMPLETED_WITH_WARNINGS`, auditoría,
  OpenAPI, métricas y pruebas Testcontainers.

### Excluido

- Editar/eliminar versiones, recalcular reportes históricos, cambiar pesos o
  score después de completar un job.
- Descargar CV, enlaces de archivo, exportaciones PDF/XLSX, filtros avanzados,
  búsqueda textual, Top 5 UI, notificaciones y React.
- Estados humanos por candidato, disponibilidad, corrección de perfil,
  directorio histórico, papelera y privacidad.
- Outlook, documentos, Claude, reintentos de análisis o cálculos nuevos.
- Contratación, descarte automático o modificación de una persona por score.

## Decisiones arquitectónicas

1. `reporting` es dueño de las versiones y entradas de reporte. Consume puertos
   de `job`, `candidate`, `analysis` y scoring; nunca relee la vacante actual ni
   descifra documentos directamente.
2. Una versión es snapshot completo. Cambios posteriores de vacante, perfil,
   documento, modelo, prompt o algoritmo no afectan el reporte existente.
3. Un job produce a lo sumo una versión. Constraint única por `matching_job_id`
   hace la composición idempotente incluso con replay/lease vencido.
4. El ranking backend usa en orden: `totalScore desc`, `mandatoryScore desc`,
   `mandatoryMetCount desc`, `documentReceivedAt desc`, `candidateProfileId asc`.
   El frontend nunca recalcula, reordena ni resuelve empates.
5. PII de presentación se obtiene mediante `CandidateReportViewPort`, se cifra
   en la versión como snapshot mínimo y se entrega sólo a usuarios autenticados.
   Nunca se expone sender, hash, HMAC, ruta, documento sin seleccionar ni token.

## Modelo y persistencia

Crear exclusivamente `V13__immutable_report_ranking.sql`; no modificar V1–V12.

### `report_version`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `matching_job_id` | FK única al job. |
| `vacancy_id`, `vacancy_version` | Snapshot de origen. |
| `version_number` | Entero secuencial por vacante, único. |
| `status` | `COMPLETED` o `COMPLETED_WITH_WARNINGS`. |
| `scoring_algorithm_version` | Snapshot no vacío. |
| `analysis_model_id`, `analysis_prompt_version` | Snapshot nullable si no hubo análisis válido. |
| `candidate_count`, `warning_count` | Conteos derivados no negativos. |
| `created_at`, `completed_at` | `timestamptz` UTC. |

### `report_candidate`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `report_version_id` | FK no nula. |
| `candidate_profile_id` | UUID no nula, referencia lógica privada. |
| `candidate_name_ciphertext`, `candidate_email_ciphertext` | Snapshot PII AES-GCM nullable. |
| `candidate_identity_confidence` | `HIGH`, `MEDIUM` o `LOW`. |
| `candidate_document_id` | Documento seleccionado, no público. |
| `document_received_at` | UTC para desempate. |
| `rank_position` | Entero desde 1, único por versión. |
| `mandatory_score`, `optional_bonus`, `total_score` | Decimales snapshot, escala 2. |
| `mandatory_met_count` | Entero no negativo. |
| `warning_count` | Entero no negativo. |

Constraint única `(report_version_id, candidate_profile_id)`. Una identidad no
resuelta no crea `report_candidate`; se contabiliza como warning de versión.

### `requirement_assessment`

Snapshot por `report_candidate` y posición de requisito: descripción cifrada o
referencia snapshot segura, peso, mandatory, compatibility, `CUMPLE`/
`NO_CUMPLE`/`NO_DEMOSTRADO`, evidencia y explicación cifradas. Posición única
por candidato de reporte. No guardar texto de CV, hash, prompt, request Claude
ni rutas de archivo.

### `report_warning`

UUID, `report_version_id`, código cerrado y posición opcional de candidato;
sin PII ni detalle de proveedor. Códigos iniciales incluyen
`CANDIDATE_IDENTITY_UNRESOLVED`, `ANALYSIS_TEMPORARY_FAILURE`,
`NO_USABLE_TEXT` y `MESSAGE_LIMIT_REACHED`.

## Contrato API

Rutas protegidas con JWT, cuenta activa, sesión persistida y rol efectivo
`RECRUITER` o `ADMIN`.

| Método y ruta | Respuesta |
| --- | --- |
| `GET /api/v1/vacancies/{vacancyId}/report-versions` | `200 ReportVersionPage`. |
| `GET /api/v1/report-versions/{reportVersionId}` | `200 ReportVersionDetail`. |

Listado: `page` desde 0, `size` 1–100 por defecto 20, orden
`versionNumber desc`, `id asc`. El detalle incluye estado, conteos, candidatos
ordenados por `rankPosition`, score y evaluaciones explicables. Muestra nombre
y correo sólo si existen en snapshot y al actor autorizado; para identidad sin
email muestra etiqueta española segura, no sender ni HMAC. Campos desconocidos
en filtros/paginación devuelven `422 VALIDATION_ERROR`.

## Reglas de negocio

1. Worker compone sólo un job `ANALYZING` con scoring e identidad terminados.
   Si job está cancelado, no crea versión. Claim/lease protege composición.
2. Para cada `job_candidate_selection`, usa exactamente el score revision y las
   evaluaciones snapshot del documento seleccionado. Si son incompletos, omite
   entrada y agrega warning seguro; no inventa score ni assessment.
3. Si no existe ningún candidato rankeable, job pasa `FAILED` con
   `NO_RANKABLE_CANDIDATES` y no crea versión `COMPLETED` vacía.
4. Si hay candidatos pero existen warnings, crea `COMPLETED_WITH_WARNINGS`; si
   no, `COMPLETED`. Ambos son terminales y no permiten recomposición.
5. La transacción crea versión, entradas, assessments, warnings, evento técnico
   y estado terminal del job juntos. Un fallo deja el job no terminal y sin
   versión parcial.
6. `version_number` se asigna bajo lock por vacante y es consecutivo. Dos jobs
   distintos completados concurrentemente para una vacante producen versiones
   únicas y ordenadas, sin perder resultados.
7. El score es informativo. Ninguna API de reporte actualiza candidato, vacante,
   documento, estado humano ni decisión laboral.

## Errores y seguridad

| Situación | HTTP / código |
| --- | --- |
| JWT inválido, revocado o cuenta no activa | `401 UNAUTHENTICATED` |
| Rol no autorizado | `403 FORBIDDEN` |
| Vacante o versión inexistente | `404 VACANCY_NOT_FOUND` / `REPORT_VERSION_NOT_FOUND` |
| Paginación/filtro inválido | `422 VALIDATION_ERROR` |
| Reporte aún no creado para job | no expuesto por endpoint de versión |

- PII permitida en detalle se limita a nombre y correo snapshot de candidato.
  No incluir teléfono, dirección, sender, IDs internos, hash, archivos,
  ciphertext, tokens, correo de usuarios ni contenido CV completo.
- `404` no distingue recursos inexistentes de recursos no visibles por permisos.
  Logs/auditoría/métricas no incluyen nombre, correo, score individual, evidencia
  ni UUID como etiqueta.

## Auditoría y observabilidad

Agregar `REPORT_VERSION_CREATED` y `REPORT_VERSION_COMPLETED_WITH_WARNINGS` a
`audit_event`, con actor solicitante original cuando exista, objetivo
`REPORT_VERSION`, timestamp y correlation ID. No incluir candidatos, scores ni
warnings en auditoría.

Métricas sin PII:

- `reporting.versions` con `outcome` (`completed`, `completed_with_warnings`,
  `failed_no_candidates`);
- `reporting.ranking_entries` como contador sin etiquetas de versión/vacante;
- `reporting.composition_duration` sin etiquetas identificables.

## OpenAPI y configuración

- Documentar bearer, paginación, detalle, `401`, `403`, `404`, `422` y ejemplos
  españoles con datos sintéticos. Marcar campos PII mínimos como restringidos.
- Configuración server-side para cifrado/version de snapshot PII y límites de
  candidatos por página. No exponer fórmulas, claves o rutas internas.
- `test` usa perfiles/documentos sintéticos y claves ficticias; no invoca Graph,
  Claude, storage o correo.

## Estrategia de pruebas

### Unitarias

- Orden exacto y todos los desempates, incluyendo UUID final.
- Selección de scores/assessments snapshot, warnings y estados terminales.
- Cifrado/visibilidad de PII de presentación y rechazo de datos internos.
- Asignación de version_number y reglas de job sin candidatos.

### Integración Spring/PostgreSQL Testcontainers

- Dos compositores/replay crean una sola versión por job, entradas y assessments
  sin duplicados; cancelación no crea reporte.
- Dos jobs de misma vacante generan números consecutivos bajo concurrencia.
- Ranking de fixtures con empates coincide con contrato y permanece inmutable al
  editar vacante/perfil/documento después.
- `RECRUITER`/`ADMIN` autorizados leen detalle mínimo; `401`/`403`/`404`/`422`
  seguros; PII no permitida no aparece en API/logs/auditoría/métricas.
- V13 desde V1–V12, OpenAPI, `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. Cada job elegible crea como máximo una versión de reporte inmutable y un job
   cancelado no crea ninguna.
2. Cada perfil deduplicado aporta sólo su CV seleccionado más reciente; perfiles
   sin identidad/score válido generan warning seguro y no entrada inventada.
3. Ranking usa exactamente total, obligatorio, obligatorios cumplidos, fecha y
   UUID en el orden de desempate definido, calculado sólo en backend.
4. Score, pesos, assessments, modelo, prompt y PII de presentación quedan en
   snapshot; cambios posteriores no modifican reporte histórico.
5. Candidatos sin evidencia obligatoria se mantienen con `NO_DEMOSTRADO`; no hay
   contratación, descarte o estado humano automático.
6. Candidatos/warnings determinan `COMPLETED` o `COMPLETED_WITH_WARNINGS`; sin
   entrada rankeable el job falla seguro y no crea reporte vacío.
7. Crear versión, ranking, assessments, warnings y estado de job es transaccional
   e idempotente bajo replay/lease/concurrencia.
8. `RECRUITER`/`ADMIN` autenticados consultan versiones/detalle paginados; PII se
   limita a nombre/correo snapshot y nunca expone sender, CV, hashes o rutas.
9. Auditoría, logs, OpenAPI y métricas no filtran evidencia, texto, PII no
   permitida, score individual ni UUID como etiqueta.
10. V13 y Testcontainers cubren ranking, ties, snapshots, concurrencia, seguridad
    y errores sin descargas, exportaciones, estados humanos, UI o notificaciones.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Specs 009–011 aportan evaluación, score e identidad. | Usar puertos públicos snapshot. |
| Riesgo | Empate cambia orden entre ejecuciones. | Desempate UUID documentado y probado. |
| Riesgo | Perfil editado altera reporte histórico. | PII/score/assessment snapshot cifrado. |
| Riesgo | PII de candidato se sobreexpone. | DTO mínimo, autorización y pruebas de ausencia. |
| Dependencia futura | Faltan estado humano y exportación. | Reporte se mantiene inmutable y de sólo lectura. |

## Definition of Ready

`READY_FOR_DEV`
