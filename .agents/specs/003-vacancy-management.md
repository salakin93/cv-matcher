# 003 - Vacancy Management

## Objetivo

Entregar la fundación backend de vacantes para que usuarios autenticados con
rol `RECRUITER` o `ADMIN` creen, consulten, editen, archiven y reactiven
vacantes compartidas con requisitos ponderados. La vacante conserva el rango
de recepción de CVs introducido en `America/La_Paz`, convertido de forma
determinista a UTC para los incrementos posteriores de Outlook y reportes.

## Referencias

- `docs/PRD.md`, secciones 2, 4 y 5.
- `docs/PRODUCT_BACKLOG.md`, Epic 2, Feature 2.1.
- `docs/architecture.md`, secciones 4, 5, 6, 7, 9 y 10.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- `.agents/specs/001-identity-account-foundation.md` y
  `.agents/specs/002-account-administration.md`.

## Alcance

### Incluido

- Módulo backend `vacancy` con API REST JSON bajo `/api/v1/vacancies`.
- Creación, consulta individual y listado paginado de vacantes compartidas.
- Edición total de una vacante `ACTIVE`, incluida la sustitución atómica de sus
  requisitos.
- Archivo y reactivación explícitos, sin eliminación física.
- Requisitos con descripción, peso entero de 1 a 5 y marca `mandatory`.
- Conversión de fechas locales de Bolivia a límites UTC inclusivo/exclusivo.
- Control optimista de concurrencia, auditoría mínima, OpenAPI, métricas y
  pruebas Spring/PostgreSQL Testcontainers.

### Excluido

- UI React, búsqueda UI y filtros de texto avanzados.
- Creación, ejecución, reintento, cancelación o consulta de trabajos.
- Reportes, versiones de reporte, ranking, candidatos, análisis Claude y
  notificaciones.
- Outlook, Microsoft Graph, documentos, almacenamiento de archivos,
  deduplicación y antimalware.
- Exportaciones, directorio histórico, papelera, privacidad y consulta de
  auditoría.
- Parámetros de concurrencia global y cualquier integración externa.

## Decisiones arquitectónicas

1. `vacancy` es dueño de `vacancy` y `vacancy_requirement`. No accede a tablas
   de `identity`, `job`, `reporting`, `candidate` o `document` salvo el
   contrato transversal append-only de auditoría.
2. Las vacantes son recursos compartidos: todo `RECRUITER` o `ADMIN` activo,
   autenticado mediante una sesión persistida vigente, puede operarlas. No hay
   propiedad por creador ni autorización basada en conocer el UUID.
3. Las fechas de entrada son `dateFrom` y `dateTo` ISO-8601 (`YYYY-MM-DD`) en
   `America/La_Paz`. Se persiste `receivedFromUtc` como el inicio local de
   `dateFrom` y `receivedToUtcExclusive` como el inicio local del día siguiente
   a `dateTo`; por tanto el intervalo incluye ambos días completos sin usar un
   final `23:59:59.999`.
4. Las modificaciones exigen la versión actual del recurso. La aplicación
   bloquea la fila objetivo, compara `expectedVersion` y aumenta `version` una
   sola vez junto con el cambio y su auditoría. Una versión desactualizada
   responde `409 VERSION_CONFLICT` y no altera requisitos ni estado.
5. Archivar no elimina datos ni requisitos. Reactivar conserva exactamente la
   configuración existente. Las reglas que impiden generar reportes para una
   vacante archivada se implementarán con la creación de jobs, pero el estado
   `ARCHIVED` queda disponible desde este incremento.

## Modelo y persistencia

Crear exclusivamente la migración Flyway inmutable
`V4__vacancy_management.sql`; no modificar V1, V2 ni V3.

### `vacancy`

| Columna | Regla |
| --- | --- |
| `id` | UUID, PK, generado por servidor. |
| `title` | Texto obligatorio, 1-160 caracteres tras trim. |
| `description` | Texto obligatorio, 1-10.000 caracteres tras trim. |
| `received_from_utc` | `timestamptz`, límite inclusivo calculado. |
| `received_to_utc_exclusive` | `timestamptz`, límite exclusivo calculado. |
| `status` | `ACTIVE` o `ARCHIVED`; inicia `ACTIVE`. |
| `version` | `bigint` no nulo; inicia en 0. |
| `created_at`, `updated_at` | `timestamptz` UTC, no nulos. |

Constraint: `received_from_utc < received_to_utc_exclusive`. Crear índice para
el listado estable por `status`, `updated_at desc`, `id asc`. No se registra
correo, nombre de actor ni datos de candidatos en estas tablas.

### `vacancy_requirement`

| Columna | Regla |
| --- | --- |
| `id` | UUID, PK, generado por servidor. |
| `vacancy_id` | FK no nula a `vacancy`, `ON DELETE RESTRICT`. |
| `description` | Texto obligatorio, 1-1.000 caracteres tras trim. |
| `weight` | `smallint`, constraint entre 1 y 5. |
| `mandatory` | Booleano no nulo. |
| `position` | Entero no negativo, único por vacante; preserva el orden enviado. |

El límite es 30 requisitos por vacante. El reemplazo de requisitos borra e
inserta sólo las filas de la vacante dentro de la misma transacción. Este
incremento no tiene versiones de reporte que referencien requisitos; cuando
existan, su snapshot será responsabilidad de `reporting`.

## Contrato API

Todas las rutas requieren bearer JWT válido, sesión persistida activa y rol
efectivo `RECRUITER` o `ADMIN`. Los roles se leen de cuenta/sesión, no sólo del
claim del JWT.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| `POST /api/v1/vacancies` | `VacancyWriteRequest` | `201` y `VacancyDetail`. |
| `GET /api/v1/vacancies` | `status?`, `page?`, `size?` | `200` y `VacancyPage`. |
| `GET /api/v1/vacancies/{vacancyId}` | — | `200` y `VacancyDetail`. |
| `PUT /api/v1/vacancies/{vacancyId}` | `VacancyWriteRequest` | `200` y `VacancyDetail`. |
| `POST /api/v1/vacancies/{vacancyId}/archive` | `{ "expectedVersion": 0 }` | `204`. |
| `POST /api/v1/vacancies/{vacancyId}/reactivate` | `{ "expectedVersion": 1 }` | `204`. |

`page` inicia en 0; `size` está entre 1 y 100 y por defecto es 20. El listado
ordena por `updatedAt` descendente y luego por UUID ascendente. `status` es un
filtro exacto opcional. Por defecto devuelve sólo `ACTIVE`; para incluir
archivadas el cliente debe solicitar `status=ARCHIVED` explícitamente.

### `VacancyWriteRequest`

```json
{
  "title": "Backend Java Senior",
  "description": "Construir y mantener servicios Java.",
  "dateFrom": "2026-09-01",
  "dateTo": "2026-09-15",
  "expectedVersion": 0,
  "requirements": [
    {
      "description": "Java y Spring Boot",
      "weight": 5,
      "mandatory": true
    },
    {
      "description": "PostgreSQL",
      "weight": 3,
      "mandatory": false
    }
  ]
}
```

En creación, `expectedVersion` debe ser `0`; en edición debe coincidir con la
versión devuelta por la última lectura. `requirements` contiene entre 1 y 30
elementos y su orden es significativo. Campos desconocidos, ausentes o fuera
de rango se rechazan con `422 VALIDATION_ERROR`; nunca se ignoran.

### `VacancyDetail`

```json
{
  "id": "uuid",
  "title": "Backend Java Senior",
  "description": "Construir y mantener servicios Java.",
  "dateFrom": "2026-09-01",
  "dateTo": "2026-09-15",
  "status": "ACTIVE",
  "version": 0,
  "requirements": [
    {
      "id": "uuid",
      "description": "Java y Spring Boot",
      "weight": 5,
      "mandatory": true,
      "position": 0
    }
  ],
  "createdAt": "2026-09-05T12:00:00Z",
  "updatedAt": "2026-09-05T12:00:00Z"
}
```

`VacancyPage` devuelve `items`, `page`, `size`, `totalItems` y `totalPages`.
El listado puede omitir la descripción y los requisitos para minimizar carga;
la consulta individual siempre devuelve el detalle completo. Ninguna respuesta
incluye JWT, refresh tokens, sesiones, correo de usuarios, datos de CVs ni
metadatos de integraciones.

## Reglas de negocio

1. Sólo usuarios `ACTIVE` con sesión vigente pueden gestionar vacantes; una
   cuenta `DISABLED` o una sesión revocada recibe `401`.
2. Todos los `RECRUITER` y `ADMIN` ven y administran el mismo conjunto de
   vacantes. No existe endpoint administrativo separado para este recurso.
3. `dateFrom` no puede ser posterior a `dateTo`; ambas se interpretan siempre
   en `America/La_Paz`, sin aceptar offset o instante en el payload.
4. Una vacante tiene al menos un requisito; cada requisito usa peso 1–5 y una
   descripción no vacía. No se deduplican descripciones: requisitos parecidos
   pueden representar criterios deliberadamente distintos.
5. Sólo una vacante `ACTIVE` se puede editar. Intentar editar una `ARCHIVED`
   devuelve `409 VACANCY_ARCHIVED`.
6. Archivar sólo transiciona `ACTIVE -> ARCHIVED`; reactivar sólo
   `ARCHIVED -> ACTIVE`. Solicitar el estado ya presente es idempotente: `204`,
   sin aumentar versión, auditoría ni métrica de mutación.
7. Toda creación, edición efectiva, archivo y reactivación es transaccional. Si
   falla la persistencia de requisitos, no se persiste un cambio parcial de la
   vacante.

## Errores y seguridad

Los errores usan el JSON seguro común, con `status`, `code`, `message`,
`timestamp`, `path` y `correlationId`; mensajes públicos en español y sin SQL,
trazas ni datos de terceros.

| Situación | HTTP / código |
| --- | --- |
| Bearer ausente, inválido, revocado o cuenta no activa | `401 UNAUTHENTICATED` |
| Rol distinto de `RECRUITER`/`ADMIN` | `403 FORBIDDEN` |
| UUID inexistente | `404 VACANCY_NOT_FOUND` |
| Payload, enum, fecha, peso, límite o paginación inválidos | `422 VALIDATION_ERROR` |
| Edición de archivada | `409 VACANCY_ARCHIVED` |
| Versión desactualizada | `409 VERSION_CONFLICT` |

- Las consultas SQL son parametrizadas y los UUID de ruta se validan antes de
  invocar casos de uso.
- No se aceptan campos de propiedad, usuario, trabajo, reporte, candidato,
  modelo IA ni parámetros internos dentro de los DTOs de vacante.
- Logs, auditoría y métricas no incluyen título, descripción, requisitos,
  correo, UUID de usuario, tokens ni correlation ID como etiqueta.
- El título, descripción y requisitos son contenido no confiable. Este
  incremento los almacena y devuelve a usuarios autorizados; no los interpola
  en SQL, logs estructurados ni llamadas a proveedores.

## Auditoría y observabilidad

Para mutaciones efectivas, añadir eventos append-only a `audit_event`:

- `VACANCY_CREATED`
- `VACANCY_UPDATED`
- `VACANCY_ARCHIVED`
- `VACANCY_REACTIVATED`

Cada evento registra UUID de actor, UUID de objetivo, tipo `VACANCY`, acción,
timestamp y correlation ID. No persiste la descripción, requisitos, rango de
fechas ni versiones anteriores/nuevas como metadata.

Exponer métricas sin PII:

- `vacancy.mutations` con etiquetas `action` (`create`, `update`, `archive`,
  `reactivate`) y `outcome` (`success`, `conflict`, `validation_error`);
- `vacancy.list_requests` con etiqueta `status_filter` (`active`, `archived`).

Los logs JSON registran sólo evento técnico, resultado, UUID de vacante y
correlation ID como campo de log, nunca como etiqueta de métrica.

## OpenAPI y configuración

- Documentar todos los endpoints, seguridad bearer, filtros, paginación,
  contratos de fecha Bolivia y respuestas `401`, `403`, `404`, `409`, `422`.
- Usar ejemplos españoles seguros. OpenAPI no publica rutas internas,
  columnas, SQL ni información de usuarios.
- No se agregan secretos, credenciales, perfiles ni integraciones externas.
  Las pruebas usan el perfil `test`, PostgreSQL Testcontainers y datos
  sintéticos.

## Estrategia de pruebas

### Unitarias

- Conversión exacta `America/La_Paz` a los límites UTC, incluidos bordes de día
  y orden inválido de fechas.
- Validación de título, descripción, requisitos, peso, límite de 30 y campos
  JSON desconocidos.
- Transiciones de archivo/reactivación e idempotencia.
- Clasificación de errores y conflicto de versión.

### Integración Spring/PostgreSQL Testcontainers

- `401` sin bearer, `403` para rol no permitido y acceso compartido para
  `RECRUITER` y `ADMIN` con sesión persistida.
- Creación, detalle, paginación, filtro `ACTIVE`/`ARCHIVED`, orden estable y
  ausencia de campos sensibles.
- Reemplazo atómico y ordenado de requisitos; fallo de validación sin cambios.
- Archivo, reactivación, prohibición de editar archivada e idempotencia sin
  auditoría/métrica adicional.
- Dos actualizaciones concurrentes con la misma versión: exactamente una tiene
  éxito y la otra recibe `409 VERSION_CONFLICT`; requisitos y versión finales
  corresponden íntegramente a una sola solicitud.
- Migración V4 desde V1–V3, auditoría con actor/objetivo/correlation ID y
  métricas sin etiquetas PII.
- Regresión obligatoria: `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. Un `RECRUITER` y un `ADMIN` activos con sesión vigente pueden crear, listar,
   leer y gestionar la misma vacante; sin bearer es `401` y un rol no permitido
   recibe `403` JSON seguro.
2. Crear una vacante requiere título, descripción, rango Bolivia válido y al
   menos un requisito ponderado; el rango se persiste como límites UTC
   inclusivo/exclusivo correctos.
3. Los pesos admiten sólo enteros 1–5, los requisitos preservan el orden, y un
   payload con campos desconocidos o valores inválidos retorna `422` sin
   persistir una vacante parcial.
4. El listado es paginado, filtrable por estado, estable y compartido; por
   defecto devuelve sólo `ACTIVE` y no expone datos de sesiones, identidad de
   otros usuarios ni información de documentos/reportes.
5. Una lectura individual devuelve requisitos, versión, estado y timestamps;
   un UUID inexistente devuelve `404 VACANCY_NOT_FOUND`.
6. Editar una vacante activa reemplaza todos sus requisitos de forma atómica y
   aumenta su versión exactamente una vez.
7. Dos ediciones concurrentes con la misma versión no se pierden: una tiene
   éxito y la otra devuelve `409 VERSION_CONFLICT`, conservando una
   configuración coherente.
8. Archivar impide editar; reactivar conserva requisitos y rango. Solicitar una
   transición ya satisfecha devuelve `204` sin cambiar versión ni duplicar
   auditoría o métricas.
9. Cada mutación efectiva genera auditoría mínima con actor, objetivo, acción y
   correlation ID, y métricas sin texto de vacante, datos personales ni UUID
   como etiquetas.
10. Flyway V4, OpenAPI, pruebas unitarias e integración Testcontainers cubren
    contratos, UTC, autorización, concurrencia, transacciones y errores.
11. No se implementan ni habilitan trabajos, Outlook, documentos, candidatos,
    Claude, reportes, notificaciones, exportaciones o UI como efecto colateral.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | 001/002 deben mantener autenticación por `sid` persistido, roles efectivos y JSON seguro. | Pruebas de autorización con sesiones reales. |
| Riesgo | Confundir fechas locales con UTC al consultar Outlook en el futuro. | Persistir límites UTC explícitos y probar los bordes en La Paz. |
| Riesgo | Actualizaciones concurrentes pierden requisitos. | `expectedVersion`, bloqueo de fila y prueba de carrera PostgreSQL. |
| Riesgo | Archivo cambia o borra configuración histórica. | No hay delete; transiciones explícitas e idempotentes. |
| Dependencia futura | Un job activo por vacante aún no existe. | Esta spec sólo modela estado; spec de jobs impone la restricción. |

## Definition of Ready

`READY_FOR_DEV`
