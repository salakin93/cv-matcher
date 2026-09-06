# 017 - Historical Search Confirmation Gate

## Objetivo

Entregar el control explícito que habilita una futura búsqueda histórica de
candidatos. Un `RECRUITER` o `ADMIN` sólo puede confirmar la búsqueda cuando una
versión de reporte de la vacante no tiene candidatos que alcancen el umbral. La
confirmación es breve, vinculada a actor/vacante/reporte y auditable; no consulta
ni reanaliza candidatos en este incremento.

## Referencias

- `docs/PRD.md`, sección 8.
- `docs/PRODUCT_BACKLOG.md`, Epic 5, Feature 5.1.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 003, 012 y 016.

## Alcance

### Incluido

- Consulta de elegibilidad para búsqueda histórica desde una versión de reporte.
- Umbral sugerido 70, configurable sólo en solicitud entre 0 y 100.
- Confirmación explícita, de un solo uso, con vencimiento y actor vinculado.
- Persistencia/auditoría de confirmación sin PII de candidatos.
- Puertos internos para que futura búsqueda exija una confirmación válida.
- Errores seguros, OpenAPI, métricas sin PII y pruebas Testcontainers.

### Excluido

- Buscar, listar, rankear, reanalizar o exportar candidatos históricos.
- Filtrar por período, disponibilidad, habilidades, ubicación o score; esos
  filtros se implementan con el motor de búsqueda posterior.
- Modificar perfil, disponibilidad, reportes, score, ranking, vacante, documento
  o estado humano; tampoco UI, notificaciones, papelera o privacidad.
- Confirmación global, reusable, transferible entre actores o utilizable para otra
  vacante/reporte/umbral.

## Decisiones arquitectónicas

1. La búsqueda histórica es una operación sensible de reclutamiento y requiere
   consentimiento explícito del actor incluso si los datos son compartidos.
2. Elegibilidad se calcula exclusivamente contra la `report_version` inmutable:
   si existe una entrada con `totalScore >= threshold`, no se permite confirmar.
   No usar perfiles/scores actuales ni recalcular ranking.
3. La confirmación se persiste como token opaco aleatorio, almacenado sólo por
   SHA-256, con actor, vacante, versión, threshold y vencimiento de 15 minutos.
4. Consumir la confirmación ocurre transaccionalmente en el futuro módulo de
   búsqueda. Un token vencido, consumido o de otro actor nunca revela su origen.
5. El gate no expone candidatos ni scores individuales, y no constituye una
   autorización para descarga, exportación, corrección o acceso a CVs.

## Modelo y persistencia

Crear exclusivamente `V18__historical_search_confirmation.sql`; no modificar
V1–V17.

### `historical_search_confirmation`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `token_hash` | SHA-256 de token aleatorio 256 bits; único. |
| `requested_by_user_id` | UUID no nulo del actor. |
| `vacancy_id`, `report_version_id` | UUIDs no nulos del contexto snapshot. |
| `threshold` | Decimal 0–100, escala 2. |
| `created_at`, `expires_at`, `consumed_at` | `timestamptz` UTC. |
| `correlation_id` | UUID nullable. |

Índice por actor/vencimiento y constraint de consumo único. No guardar nombres,
correos, candidatos, términos, resultado de búsqueda ni token en claro.

## Contrato API

JWT válido, cuenta activa, sesión vigente y rol `RECRUITER`/`ADMIN` requeridos.

| Método y ruta | Solicitud/respuesta |
| --- | --- |
| `GET /api/v1/vacancies/{vacancyId}/historical-search-eligibility` | `reportVersionId`, `threshold?`; `200 HistoricalSearchEligibility`. |
| `POST /api/v1/vacancies/{vacancyId}/historical-search-confirmations` | `HistoricalSearchConfirmationRequest`; `201 HistoricalSearchConfirmation`. |

Request:

```json
{
  "reportVersionId": "uuid",
  "threshold": 70
}
```

Elegibilidad devuelve `eligible`, `threshold`, `reportVersionId` y un mensaje
seguro. Si elegible, confirmación devuelve sólo token opaco de un uso,
`expiresAt`, vacante/reporte/threshold. Campos desconocidos, threshold inválido,
UUID inválido o cuerpo incompleto devuelven `422 VALIDATION_ERROR`.

## Reglas de negocio

1. La versión debe pertenecer a la vacante de ruta y estar `COMPLETED` o
   `COMPLETED_WITH_WARNINGS`; de otro modo devuelve `404 REPORT_VERSION_NOT_FOUND`
   o `409 REPORT_NOT_ELIGIBLE` seguro.
2. `eligible=true` sólo si no hay `report_candidate.total_score >= threshold`.
   Igualdad al umbral cuenta como candidato que alcanzó el umbral.
3. Sólo cuando elegible, POST crea token aleatorio y hash. Si no elegible retorna
   `409 HISTORICAL_SEARCH_NOT_REQUIRED`; no crea confirmación/auditoría.
4. Una confirmación nueva del mismo actor/contexto/threshold invalida intentos
   pendientes anteriores del mismo actor, sin afectar confirmaciones de otros
   actores. No se devuelve token de intentos previos.
5. El token sólo sirve para un futuro request de búsqueda con mismo actor,
   vacante, reporte y threshold. El consumidor bloquea fila, verifica expiración
   y marca `consumed_at` en la misma transacción que crea búsqueda futura.
6. Expiración/consumo no cambian ranking, reportes, perfil, score, documentos o
   estados humanos. Limpiar vencidos es operación interna segura, sin scheduler
   obligatorio en esta spec.

## Errores y seguridad

| Situación | HTTP / código |
| --- | --- |
| JWT inválido/revocado/cuenta no activa | `401 UNAUTHENTICATED` |
| Rol no autorizado | `403 FORBIDDEN` |
| Vacante/reporte inexistente o relación ajena | `404 REPORT_VERSION_NOT_FOUND` |
| Reporte no terminal/no elegible | `409 REPORT_NOT_ELIGIBLE` |
| Ya existe candidato al umbral | `409 HISTORICAL_SEARCH_NOT_REQUIRED` |
| Payload/threshold inválido | `422 VALIDATION_ERROR` |

- Token se transmite una sola vez por TLS; nunca aparece en log, auditoría,
  métricas, URL, query string, error, OpenAPI example ni storage en claro.
- Elegibilidad no devuelve conteo, identidad, score individual o explicación de
  candidatos existentes. Errores JSON comunes incluyen correlation ID seguro.

## Auditoría y observabilidad

Registrar `HISTORICAL_SEARCH_CONFIRMED` en `audit_event` sólo cuando POST crea
confirmación, con actor, objetivo `REPORT_VERSION`, timestamp y correlation ID.
No registrar token, threshold, candidato, score o PII.

Métricas sin PII:

- `historical_search.eligibility_checks` con `result` (`eligible`, `not_required`,
  `report_not_eligible`);
- `historical_search.confirmations` con `outcome` (`created`, `not_required`,
  `validation_error`);
- `historical_search.confirmation_expired` como contador sin IDs.

## OpenAPI y configuración

- Documentar elegibilidad/confirmación, bearer, `200`, `201`, `401`, `403`,
  `404`, `409`, `422` y ejemplos sintéticos; marcar token como secreto efímero.
- Propiedades server-side: TTL fijo de confirmación, fuente aleatoria criptográfica
  y límites de limpieza. No permitir TTL/umbral predeterminado por UI fuera de
  la solicitud validada.
- `test` usa reportes sintéticos, tokens ficticios y PostgreSQL Testcontainers;
  no candidatos reales, búsqueda, documentos, Graph, Claude ni secretos.

## Estrategia de pruebas

### Unitarias

- Elegibilidad debajo/en/encima de threshold, versiones no terminales y relación
  vacante-reporte.
- Token aleatorio/hash, TTL, consumo único, invalidación previa y enlace actor.
- Validación de DTO y ausencia de token/score en serialización/logs.

### Integración Spring/PostgreSQL Testcontainers

- Ambos roles autorizados consultan y confirman; `401`/`403`/`404`/`409` seguros.
- Reporte con score igual/mayor rechaza confirmación y no crea fila/auditoría.
- Dos POST concurrentes del mismo actor dejan una confirmación pendiente usable;
  consumo concurrente futuro sólo gana una transacción.
- Token no se persiste claro ni se filtra en API posterior/auditoría/métrica;
  V18 desde V1–V17, OpenAPI, `./gradlew test`, `git diff --check`.

## Criterios de aceptación

1. Sólo reclutador/admin activo con sesión vigente consulta/crea confirmación;
   ausencia de bearer es `401` y rol no autorizado `403` seguro.
2. Elegibilidad usa versión de reporte inmutable que pertenece a vacante y
   threshold validado; score igual al threshold bloquea búsqueda histórica.
3. Confirmación sólo se crea si ningún candidato alcanza threshold; de otro modo
   retorna `409` y no persiste token/auditoría.
4. Token opaco es aleatorio, un uso, expira en 15 minutos y queda ligado a actor,
   vacante, reporte y threshold; BD guarda sólo hash.
5. Reconfirmación invalida intento pendiente del mismo actor sin revelar ni
   afectar confirmaciones de otros actores.
6. Gate no busca/lista/reanaliza candidato ni modifica perfil, reporte, score,
   documento, estado humano o vacante.
7. APIs, logs, auditoría, OpenAPI y métricas no exponen token, PII, candidato,
   score individual o UUID como etiqueta.
8. V18 y Testcontainers cubren threshold, seguridad, idempotencia/consumo,
   concurrencia, expiración y errores sin búsqueda, UI, exportación o privacidad.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 012 entrega reportes y scores snapshot. | Calcular sólo sobre versión inmutable. |
| Riesgo | Búsqueda histórica sin consentimiento. | Token efímero vinculado y consumo único. |
| Riesgo | Token filtrado. | Hash, TLS, redacción y no query string. |
| Riesgo | Score actual altera elegibilidad. | No consultar perfiles/scores actuales. |
| Dependencia futura | Falta motor de búsqueda histórico. | Puerto consume confirmación sin ampliarla. |

## Definition of Ready

`READY_FOR_DEV`
