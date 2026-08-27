# Base de Datos — Automatización de Preselección de Candidatos

PostgreSQL, migraciones versionadas con Flyway. Ver reglas de negocio completas en [PRD.md](./PRD.md).

---

## 1. Diagrama de relaciones (resumen)

```
outlook_connection

job_search 1───* job_search_version 1───* job_requirement

job_search 1───* extraction_run 1───* processed_email 1───* cv_file

cv_file 1───1 candidate_evaluation 1───* requirement_evidence

extraction_run 1───* processing_error
              (referencia opcional a cv_file)

(todas las tablas relevantes) ───* audit_event
```

---

## 2. Tablas

### `outlook_connection`
Estado de la conexión OAuth2 con Microsoft.

| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `microsoft_account_id` | Identificador de cuenta en Microsoft |
| `token_encrypted` | Cifrado con clave externa a la BD |
| `refresh_token_encrypted` | Cifrado con clave externa a la BD |
| `expires_at` | Timestamp |
| `connected_at` | Timestamp |
| `revoked_at` | Timestamp nullable |

### `job_search`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `title` | 3–120 caracteres |
| `description` | 20–5.000 caracteres |
| `status` | `BORRADOR`, `LISTA`, `PROCESANDO`, `COMPLETADA`, `COMPLETADA_CON_ERRORES`, `ARCHIVADA` |
| `current_version` | FK lógica a la versión activa |
| `created_at` / `archived_at` | Timestamps |

### `job_search_version`
Inmutable una vez creada; toda edición de criterios crea una fila nueva.

| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `job_search_id` | FK → `job_search` |
| `version` | Entero incremental |
| `experience_min_years` | Nullable, se puede expresar también como requisito libre |
| `scoring_config_json` | Pesos, umbral de confianza baja (0.4 por defecto), configuración de fórmula |
| `created_at` | Timestamp |

### `job_requirement`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `search_version_id` | FK → `job_search_version` |
| `text` | 3–500 caracteres, texto libre |
| `type` | `OBLIGATORIO` / `DESEABLE` |
| `weight` | Entero 1–10 |
| `position` | Orden de despliegue |

### `extraction_run`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK (`runId`) |
| `job_search_id` | FK → `job_search` |
| `search_version_id` | FK → `job_search_version` (versión usada) |
| `date_from` / `date_to` | Rango inclusivo, zona `America/La_Paz` |
| `status` | `PENDIENTE`, `EXTRAYENDO`, `PROCESANDO`, `COMPLETADA`, `COMPLETADA_CON_ERRORES`, `LIMITE_ALCANZADO`, `FALLIDA`, `CANCELADA` |
| `total` / `processed` / `evaluated` / `errors` | Contadores de progreso |
| `limit_reached_at` | Timestamp nullable — cuándo se alcanzó el tope de 150 CVs |
| `remaining_emails_json` | Lista de correos no procesados por corte de límite (id, fecha) |
| `started_at` / `completed_at` | Timestamps |
| `cancel_requested` | Booleano |

### `processed_email`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `run_id` | FK → `extraction_run` |
| `outlook_message_id` | Único junto con `run_id` |
| `sender_email` | Correo del remitente (usado solo como respaldo de deduplicación) |
| `received_at` | Timestamp de Graph (`receivedDateTime`) |
| `subject` | Asunto del correo |
| `status` | Estado de procesamiento del correo |

**Índice:** unicidad `(run_id, outlook_message_id)`.

### `cv_file`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `run_id` | FK → `extraction_run` |
| `processed_email_id` | FK → `processed_email` |
| `attachment_id` | Id del adjunto en Graph |
| `original_name` | Nombre tal como llegó |
| `normalized_name` | Minúsculas, sin tildes, sin caracteres especiales |
| `mime_type` / `size_bytes` / `sha256` | Validación e identificación de archivo |
| `storage_key` | Referencia en almacenamiento privado |
| `text_status` | Resultado de extracción PDFBox |
| `status` | `PENDIENTE`, `PENDIENTE_REVISION`, `DESCARGADO`, `PROCESANDO`, `EVALUADO`, `REEMPLAZADO`, `NO_LEGIBLE`, `IGNORADO_NO_ES_CV`, `ERROR_DESCARGA`, `ERROR_EVALUACION`, `CANCELADO` |
| `ignored_reason` | Motivo cuando aplica (no PDF, tamaño, firma inválida, protegido, corrupto) |
| `received_at` | Copiado de `processed_email` para consultas de orden/límite |
| `expires_at` | Fecha de vencimiento de retención (180 días) |

**Índices:** unicidad `(processed_email_id, attachment_id)`; índice de vencimiento `(expires_at)`; índice `(status)` para la cola de revisión manual.

### `candidate_evaluation`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `cv_file_id` | FK → `cv_file` |
| `search_version_id` | FK → `job_search_version` |
| `candidate_name` | Extraído del CV |
| `candidate_email` | Extraído del CV — **primera prioridad de deduplicación** |
| `candidate_phone` | Extraído del CV — segunda prioridad de deduplicación |
| `sender_email` | Copiado de `processed_email` — solo respaldo |
| `dedup_method` | `EMAIL_EXTRAIDO`, `TELEFONO_NOMBRE` o `REMITENTE_FALLBACK` |
| `score` | Entero 0–100 |
| `confidence_avg` | Promedio de `confidence` de las evidencias; usado solo para el aviso visual de "confianza baja" (< 0.4), nunca para el score |
| `summary` | Máximo 600 caracteres |
| `strengths_json` / `gaps_json` | Listas generadas por el LLM |
| `model_name` / `prompt_version` | Trazabilidad del modelo usado |
| `evaluated_at` | Timestamp |

**Índice de ranking:** `(search_version_id, eligible, score DESC)`.

### `requirement_evidence`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `evaluation_id` | FK → `candidate_evaluation` |
| `requirement_id` | FK → `job_requirement` |
| `level` | `CUMPLE`, `PARCIAL`, `NO_EVIDENCIA`, `NO_APLICA` |
| `evidence_json` | Hasta dos citas de máx. 300 caracteres |
| `reason` | Explicación breve del LLM |
| `confidence` | 0–1, informativo, no participa en el score |
| `awarded_points` | Puntos otorgados por este requisito (peso × valor del nivel) |

### `processing_error`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `run_id` | FK → `extraction_run` |
| `cv_file_id` | FK → `cv_file`, nullable |
| `stage` | Etapa donde ocurrió (descarga, extracción, LLM, scoring) |
| `error_code` | Código estandarizado |
| `retry_count` | Reintentos realizados |
| `technical_detail` | Detalle técnico (sin datos de CV) |
| `created_at` | Timestamp |

### `audit_event`
| Campo | Tipo/Notas |
|---|---|
| `id` | PK |
| `actor_type` | `OPERADOR` / `SISTEMA` |
| `event_type` | Tipo de operación (crear, editar, eliminar, reclasificar, exportar, etc.) |
| `entity_type` / `entity_id` | Entidad afectada |
| `metadata_json` | Sin tokens, claves ni texto de CV |
| `created_at` | Timestamp |

---

## 3. Reglas de deduplicación reflejadas en el modelo

La deduplicación de candidatos (ver PRD sección 4) se apoya en:

1. `candidate_evaluation.candidate_email` (extraído del CV) — prioridad 1.
2. `candidate_evaluation.candidate_phone` + `candidate_name` normalizado — prioridad 2.
3. `processed_email.sender_email` + `cv_file.normalized_name` — prioridad 3, marcado explícitamente en `candidate_evaluation.dedup_method = REMITENTE_FALLBACK` para que el operador identifique fusiones de baja confianza.

Nunca se deduplica usando exclusivamente `sender_email` cuando existe un `candidate_email` extraído que difiere del remitente — esto evita que CVs distintos reenviados desde una misma cuenta (RRHH, agencia) se traten como el mismo candidato.

---

## 4. Retención

- Job diario evalúa `cv_file.expires_at` (180 días desde `received_at`) y elimina el archivo del almacenamiento privado más las filas dependientes permitidas por la política de retención.
- Eliminación manual de una `job_search` en cascada elimina `extraction_run`, `processed_email`, `cv_file`, `candidate_evaluation`, `requirement_evidence` asociados, y deja un `audit_event` de la operación.
