# 018 - Confirmed Historical Candidate Search

## Objetivo

Entregar búsqueda histórica asíncrona, explícitamente confirmada y reproducible
contra la vacante actual. El sistema filtra perfiles/documentos elegibles,
reanáliza sólo los seleccionados con requisitos snapshot de la vacante y publica
un resultado separado; nunca reutiliza score de otra vacante como si fuera
compatible.

## Alcance

### Incluido

- Consumo transaccional de confirmación de spec 017.
- Filtros: período de recepción, disponibilidad, ubicación, términos de habilidad
  normalizados y score mínimo 0–100 respecto de vacante actual.
- Job durable de búsqueda, límite de candidatos y evaluación/scoring aislados.
- Resultado paginado separado, con perfil mínimo y score explicable snapshot.
- Índice HMAC de términos derivados server-side del texto ya extraído; no texto
  ni término claro en consultas, logs o respuestas internas.
- Auditoría, métricas, OpenAPI, Testcontainers y dobles de analysis.

### Excluido

- Modificar reportes históricos, perfil, disponibilidad, documento, ranking de
  reportes existentes, estado humano, descarga, exportación, UI, papelera o
  privacidad.
- Búsqueda sin confirmación, búsqueda global de texto libre, enlaces públicos,
  candidatos nuevos o decisiones automáticas.

## Arquitectura y persistencia

Crear únicamente `V19__confirmed_historical_candidate_search.sql`; no modificar
V1–V18. `candidate` es dueño del índice HMAC; `reporting` es dueño de búsqueda y
resultado; ambos se comunican por puertos públicos.

### Tablas

- `candidate_search_term`: `candidate_document_id`, `term_hmac`, idioma técnico,
  versión de extractor, fecha; constraint única documento/término/versión. Los
  términos se normalizan y HMAC con clave secreta distinta de identidad.
- `historical_search_job`: UUID, confirmación consumida, vacante/version snapshot,
  actor, filtros cifrados/HMAC, estado `QUEUED|MATCHING|COMPLETED|FAILED|CANCELLED`,
  lease, límites, conteos, timestamps y código seguro.
- `historical_search_result`: job, profile, documento, score revision actual,
  rank, nombre/email snapshot cifrado, warnings; único job/profile.

Índices para disponibilidad/período/HMAC, sin PII/termino en claro. Un token de
confirmación se marca consumido en la misma transacción que crea un job.

## Contrato API

JWT, sesión vigente y rol `RECRUITER`/`ADMIN` requeridos.

| Ruta | Operación |
| --- | --- |
| `POST /api/v1/vacancies/{vacancyId}/historical-searches` | consume confirmación y retorna `202`. |
| `GET /api/v1/historical-searches/{searchId}` | estado seguro. |
| `GET /api/v1/historical-searches/{searchId}/results` | página ordenada. |
| `POST /api/v1/historical-searches/{searchId}/cancel` | `204` idempotente. |

Request contiene `confirmationToken`, `reportVersionId`, `threshold`, período
opcional, `availability?`, `location?`, máximo 10 términos (1–80) y `maxResults`
1–200. Campos desconocidos son `422`; token nunca se registra ni aparece en URL.

## Reglas de negocio

1. Confirmación debe ser válida, vigente, no consumida y coincidir exactamente
   con actor, vacante, reporte y threshold. Fallo responde `409
   HISTORICAL_SEARCH_CONFIRMATION_INVALID` sin revelar causa.
2. Sólo documentos `AVAILABLE`, texto `EXTRACTED`, perfil no eliminado y no
   pertenecientes a papelera futura son candidatos. Se aplica período,
   disponibilidad, ubicación y todos los términos HMAC solicitados.
3. Antes de incluir un perfil, usa el CV más reciente por perfil y crea una
   evaluación/scoring nueva con requisitos snapshot actuales. Scores de reportes
   anteriores son sólo filtro técnico y nunca resultado de esta búsqueda.
4. Máximo 200 candidatos y tres reintentos por análisis; cancelación/lease/replay
   son idempotentes. Resultados parciales válidos se preservan como warnings.
5. Ranking usa la misma regla determinista de spec 012. Sólo resultados con score
   total >= threshold son publicados. No cambia reportes ni estados humanos.

## Seguridad y observabilidad

Errores: `401 UNAUTHENTICATED`, `403 FORBIDDEN`, `404 HISTORICAL_SEARCH_NOT_FOUND`,
`409 HISTORICAL_SEARCH_CONFIRMATION_INVALID`, `409 SEARCH_NOT_CANCELLABLE`,
`422 VALIDATION_ERROR`. API muestra sólo PII mínima permitida de resultado;
nunca HMAC, token, texto, sender, CV, ruta, hash o provider payload.

Auditar `HISTORICAL_SEARCH_STARTED`, `HISTORICAL_SEARCH_COMPLETED`,
`HISTORICAL_SEARCH_CANCELLED`, con actor/objetivo/correlation ID y sin filtros o
PII. Métricas: `historical_search.jobs`, `historical_search.candidates`,
`historical_search.duration`, sin UUID, término, ubicación o score como etiqueta.

## Pruebas y criterios de aceptación

1. Sólo actor confirmado `RECRUITER`/`ADMIN` crea búsqueda; `401`/`403` seguros.
2. Token es un uso, actor/vacante/reporte/threshold ligado y se consume al crear
   job; replay/concurrencia no crea dos búsquedas.
3. Período, disponibilidad, ubicación y términos se aplican por índices seguros;
   términos/PII no se guardan ni exponen en claro.
4. Cada perfil usa CV más reciente y se reevalúa contra requisitos actuales; no
   reutiliza score de otra vacante.
5. Ranking y threshold son deterministas, con resultados paginados y snapshots.
6. Cancelación, lease, retry y fallas parciales no duplican ni mutan históricos.
7. Reportes, perfiles, documentos y estados humanos existentes permanecen intactos.
8. Auditoría/logs/OpenAPI/métricas no filtran token, PII, texto, términos, score
   individual o UUID como etiqueta.
9. V19 y Testcontainers cubren confirmación, filtros, concurrencia, reanálisis,
   ranking, cancelación y errores sin UI, exportación, descarga o privacidad.

## Riesgos y dependencias

| Riesgo/dependencia | Tratamiento |
| --- | --- |
| Búsqueda sin consentimiento | Consumir token de spec 017 en transacción. |
| Score histórico engañoso | Reanálisis y score aislado por vacante snapshot. |
| Índice filtra términos/PPI | HMAC con clave separada y DTO mínimo. |
| Costo no acotado | Máximos, lease y reintentos limitados. |
| Privacidad/papelera futura | Excluir por estado cuando esas specs existan. |

## Definition of Ready

`READY_FOR_DEV`
