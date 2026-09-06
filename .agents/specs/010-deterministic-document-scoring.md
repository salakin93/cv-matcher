# 010 - Deterministic Document Scoring

## Objetivo

Entregar cálculo reproducible de puntajes por documento para un job con
evaluaciones estructuradas ya validadas. El backend aplica pesos snapshot y
conserva resultados inmutables por revisión. No publica ranking ni trata un
documento como candidato definitivo.

## Referencias

- `docs/PRD.md`, sección 6.
- `docs/PRODUCT_BACKLOG.md`, Epic 4, Feature 4.2.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 003, 004 y 009.

## Alcance

### Incluido

- Módulo `reporting` para score interno por documento/job.
- Worker durable que consume evaluaciones finalizadas de spec 009.
- Fórmulas backend para score obligatorio, promedio opcional, bono y total.
- Snapshot inmutable de requisitos, pesos, evaluación, modelo, prompt y versión
  de algoritmo.
- Reglas para `NO_DEMOSTRADO`, evaluaciones incompletas e idempotencia.
- Auditoría técnica, métricas sin PII, OpenAPI de estado de job y pruebas de
  precisión decimal con PostgreSQL Testcontainers.

### Excluido

- Identidad/deduplicación de candidatos, perfiles, correo/nombre, disponibilidad,
  papelera y privacidad.
- Ranking, desempates, Top 5, filtros, búsqueda, UI, descarga, exportaciones,
  estados humanos y notificaciones.
- Llamadas Claude/Graph, cambios de evaluación/pesos/vacante/modelo.
- `report_version` público, job `COMPLETED`, contratación o descarte automático.

## Decisiones arquitectónicas

1. `reporting` usa puertos de `analysis` y `job`, no tablas internas ni
   proveedores externos.
2. Claude nunca calcula score final. Compatibilidades 0–100 validadas son entrada;
   pesos, promedio, límite y redondeo son autoridad exclusiva del backend.
3. Cada cálculo lleva `algorithm_version`; un cambio de fórmula, precisión o
   regla crea revisión inmutable y no modifica histórico.
4. Usar `BigDecimal` o entero escalado, nunca `double/float`. Persistir salida
   con escala 2 y `HALF_UP`, redondeando una sola vez.
5. Obligatorio sin evidencia usa 0 y `NO_DEMOSTRADO`; el documento permanece
   elegible y nunca implica decisión humana automática.

## Modelo y persistencia

Crear exclusivamente `V11__deterministic_document_scoring.sql`; no modificar
V1–V10.

### `document_score_revision`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `matching_job_id` | FK no nula al job. |
| `candidate_document_id` | FK no nula al documento. |
| `analysis_revision_hash` | SHA-256 determinista de entradas validadas. |
| `algorithm_version` | Versión no vacía, máximo 80. |
| `mandatory_score` | Decimal 0–100, escala 2. |
| `optional_average` | Decimal 0–100, escala 2. |
| `optional_bonus` | Decimal 0–20, escala 2. |
| `total_score` | Decimal 0–100, escala 2. |
| `mandatory_met_count` | Entero no negativo. |
| `mandatory_not_demonstrated_count` | Entero no negativo. |
| `status` | `CALCULATED` o `INCOMPLETE`. |
| `created_at` | `timestamptz` UTC. |

Constraint única por job, documento, hash de análisis y algoritmo. No guardar
texto, evidencia, explicación, correo, nombre, token, payload ni ranking.

### `document_score_requirement_snapshot`

Snapshot por revisión: posición, peso, `mandatory`, compatibilidad validada,
estado y contribución decimal. FK a revisión y posición única. Permite reproducir
la fórmula sin consultar vacante actual ni descifrar evidencias.

Agregar a `matching_job`: `scoring_completed_at`, `calculated_score_count` e
`incomplete_score_count`. Agregar eventos `SCORING_STARTED`, `SCORE_CALCULATED`,
`SCORE_INCOMPLETE` y `SCORING_COMPLETED` a `matching_job_event`.

## Fórmulas

Para obligatorios con peso `w` y compatibilidad `c`:

```text
mandatoryScore = sum(w × c) / sum(w)
```

Sin obligatorios, `mandatoryScore = 0`. `NO_DEMOSTRADO` obliga `c = 0`; una
discrepancia de entrada produce `INCOMPLETE`.

```text
optionalAverage = sum(w × c) / sum(w)
optionalBonus   = min(20, optionalAverage × 0.20)
totalScore      = min(100, mandatoryScore + optionalBonus)
```

Sin opcionales, promedio y bono son 0. `mandatory_met_count` cuenta sólo
obligatorios `CUMPLE`. La precisión interna es al menos 8 decimales; los cuatro
valores de salida se redondean al final con escala 2 y `HALF_UP`.

## Reglas de negocio

1. Worker reclama un `ANALYZING` sólo tras `analysisCompletedAt`; cancelación
   prevalece antes de calcular o persistir cada revisión.
2. Debe existir exactamente una evaluación válida por requisito snapshot.
   Faltante, duplicada, ajena, peso inválido o estado/compatibilidad inconsistente
   genera `INCOMPLETE`; no inventar evaluación opcional.
3. Hash de revisión incluye posiciones, pesos, mandatory, compatibilidades,
   estados, modelo, prompt y versión de texto; no evidencia en claro.
4. Revisión `CALCULATED` y contribuciones se persisten atómicamente. Replays,
   retries y lease vencido consultan la constraint antes de recalcular o duplicar.
5. Falla de un documento preserva otros scores y deja resultado seguro
   `INCOMPLETE`. Job continúa `ANALYZING` hasta composición futura de reporte.
6. Ningún cálculo aplica umbral, contratación, descarte, ranking ni estado humano.

## Errores y seguridad

No existe endpoint de score individual. `JobDetail` sólo puede exponer conteos
agregados y `scoringCompletedAt`.

| Situación | Resultado seguro |
| --- | --- |
| Evaluación faltante/duplicada | `INCOMPLETE` / `ANALYSIS_INCOMPLETE` |
| Estado/compatibilidad inconsistente | `INCOMPLETE` / `ANALYSIS_INCONSISTENT` |
| Snapshot inválido | `INCOMPLETE` / `REQUIREMENT_SNAPSHOT_INVALID` |
| Cálculo/persistencia no disponible | `FAILED` / `SCORING_UNAVAILABLE` |

- No loguear compatibilidad por requisito, evidencia, explicación, hash,
  documento, vacante, UUID ni fórmula con datos de entrada.
- Métricas no llevan score, modelo, requisito, job, documento o vacante como
  etiqueta. Los errores HTTP usan JSON seguro con correlation ID.

## Auditoría y observabilidad

Eventos por documento viven en `matching_job_event`. Añadir auditoría
`DOCUMENT_SCORING_COMPLETED` o `DOCUMENT_SCORING_FAILED` una vez por resultado
efectivo del job, sin detalle de score.

Métricas sin PII:

- `reporting.scoring_documents` con `outcome` (`calculated`, `incomplete`, `failed`);
- `reporting.scoring_jobs` con `outcome` (`completed`, `partial`, `failed`);
- `reporting.scoring_duration` sin etiquetas identificables.

## OpenAPI y configuración

- Documentar conteos agregados de job y errores seguros; no score individual,
  requisitos, algoritmo, hash, evidencia ni evaluación.
- Configuración server-side: `SCORING_ALGORITHM_VERSION`, escala y rounding fijos
  validados al arranque. No permitir fórmula o umbral desde API/UI.
- `test` usa fixtures sintéticos de precisión; sin Anthropic, Graph, claves
  reales ni documentos personales.

## Estrategia de pruebas

### Unitarias

- Fórmulas con obligatorios/opcionales, sin cada tipo, pesos heterogéneos,
  bordes 0/100 y bono máximo 20.
- `NO_DEMOSTRADO`, faltantes, duplicados, inconsistencias y rounding decimal.
- Hash determinista, algoritmo versionado, idempotencia y cancelación.

### Integración Spring/PostgreSQL Testcontainers

- Claim/lease/replay persiste revisión y contribuciones atómicamente.
- Dos workers no duplican score; lease vencido retoma sin alterar conteos.
- Documento incompleto preserva scores correctos y job queda no terminal.
- Tablas, DTO, logs, auditoría y métricas no contienen evidencia/texto/PII.
- V11 desde V1–V10, OpenAPI, `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. Backend calcula, no Claude, scores obligatorio/opcional/total reproducibles.
2. Pesos/requisitos provienen de snapshot de job; editar vacante no cambia histórico.
3. `NO_DEMOSTRADO` obligatorio usa 0 y no excluye documento ni decide persona.
4. Entrada faltante, duplicada o inconsistente produce `INCOMPLETE` sin parcial.
5. Precisión decimal y `HALF_UP` se aplican una vez; total 0–100 y bono 0–20.
6. Scores/contribuciones son inmutables e idempotentes por documento, análisis y
   algoritmo; recovery/concurrencia no duplica.
7. Falla de un documento preserva otros; no se crea reporte/ranking ni job terminal.
8. APIs muestran sólo conteos seguros, sin score individual, evidencia, texto,
   hash, requisito, modelo o PII.
9. Auditoría, logs y métricas no filtran evaluación/CV ni UUID/score como etiqueta.
10. V11 y Testcontainers cubren fórmulas, precisión, snapshots, concurrencia y
    errores sin candidatos, ranking, UI o exportación.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 009 entrega evaluaciones validadas. | Consumir puerto de análisis. |
| Riesgo | Punto flotante altera ranking futuro. | `BigDecimal`, escala y versión fijas. |
| Riesgo | Vacante editada altera histórico. | Snapshot y contribuciones inmutables. |
| Riesgo | Score parece decisión automática. | No candidato, ranking ni estado humano. |
| Dependencia futura | Falta identidad/reporte público. | Job sigue `ANALYZING` hasta composición posterior. |

## Definition of Ready

`READY_FOR_DEV`
