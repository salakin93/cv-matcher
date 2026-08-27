# Arquitectura — Automatización de Preselección de Candidatos

Ver contexto funcional completo en [PRD.md](./PRD.md). Este documento cubre cómo se construye el sistema: componentes, flujo de datos, contrato de API y seguridad.

---

## 1. Visión general de componentes

```
┌────────────────┐        ┌──────────────────────────┐        ┌──────────────┐
│   Frontend      │──API──▶│   Backend (Spring Boot)   │──────▶│  PostgreSQL   │
│  React + TS     │◀──────│                            │◀──────│               │
└────────────────┘        │  - REST controllers        │        └──────────────┘
                           │  - Servicio de búsquedas   │
                           │  - Cliente Microsoft Graph │───────▶ Microsoft Graph API
                           │  - Extractor PDF (PDFBox)  │
                           │  - Cliente Anthropic        │───────▶ Anthropic Claude API
                           │  - Worker de procesamiento  │
                           │    durable (cola en BD)     │
                           └──────────────┬─────────────┘
                                          │
                                          ▼
                              ┌───────────────────────┐
                              │ Almacenamiento privado  │
                              │ (S3-compatible / disco  │
                              │  local en desarrollo)   │
                              └───────────────────────┘
```

**Principio de diseño clave:** el worker de procesamiento es durable porque su estado vive en PostgreSQL (tabla `extraction_run` + `cv_file`), no en memoria. `@Async` de Spring puede usarse para paralelizar within-process, pero la recuperación tras un reinicio se basa en releer el estado persistido, no en el hilo en ejecución.

---

## 2. Frontend (React + TypeScript)

- No implementa login propio en v1: la única autenticación visible al usuario es el botón "Conectar Outlook" (OAuth2 Microsoft), gestionado server-side.
- No almacena tokens de Graph ni de Anthropic en el navegador; todos los tokens viven cifrados en el backend.
- Pantallas principales:
  - Gestión de búsquedas (crear, editar, versionar, archivar).
  - Conexión y estado de Outlook.
  - Lanzar ejecución (rango de fechas) y ver progreso (polling a `GET /runs/{runId}`).
  - Dashboard de ranking con filtros (score, tipo de requisito, estado, confianza baja).
  - Cola de revisión manual de adjuntos ambiguos.
  - Detalle de candidato y exportación.
- Mensaje fijo visible en resultados: **"Resultado asistido por IA. Revise la evidencia antes de tomar decisiones de selección."**

---

## 3. Backend (Java 21+, Spring Boot 3.x)

Módulos lógicos:

| Módulo | Responsabilidad |
|---|---|
| `job-search` | CRUD y versionado de búsquedas y requisitos. |
| `outlook-integration` | OAuth2 Authorization Code + PKCE, refresco de tokens, llamadas paginadas a Graph. |
| `cv-ingestion` | Validación de adjuntos, normalización de nombre, enrutado a procesable / `PENDIENTE_REVISION` / ignorado, almacenamiento privado. |
| `pdf-extraction` | Extracción de texto con Apache PDFBox, límites de memoria/tiempo. |
| `ai-matching` | Cliente Anthropic Claude, validación de JSON Schema, control de reintentos. |
| `scoring` | Cálculo determinista del score a partir de la evidencia validada. |
| `processing-worker` | Cola persistida en PostgreSQL, concurrencia configurable, recuperación tras reinicio, cancelación. |
| `reporting` | Ranking, detalle de candidato, exportación XLSX/CSV. |
| `retention-audit` | Job diario de retención (180 días), eliminación manual, auditoría. |

Tecnologías: Spring Security OAuth2 Client, Spring Data JPA, Bean Validation, Flyway (migraciones), OpenAPI/Swagger, Spring Boot Actuator.

---

## 4. Contrato de API

Todos los endpoints se sirven bajo `/api`. La v1 no implementa login propio. Formato estándar de respuesta:

```json
{
  "status": 202,
  "message": "Extraction run accepted",
  "timestamp": "2026-08-27T14:30:00Z",
  "method": "POST",
  "requestUri": "/api/job-searches/123/runs",
  "data": {}
}
```

| Método | Endpoint | Descripción |
|---|---|---|
| GET/POST/DELETE | `/outlook/connection` | Estado, conexión y desconexión Outlook. |
| POST/GET | `/job-searches` | Crear y listar búsquedas. |
| GET/PUT/DELETE | `/job-searches/{id}` | Detalle, actualización versionada y eliminación. |
| POST | `/job-searches/{id}/runs` | Crear ejecución. |
| GET | `/job-searches/{id}/runs` | Historial de ejecuciones. |
| GET | `/runs/{runId}` | Progreso y resumen (incluye correos restantes si `LIMITE_ALCANZADO`). |
| POST | `/runs/{runId}/cancel` | Solicitar cancelación. |
| GET | `/job-searches/{id}/candidates` | Ranking paginado y filtrable. |
| GET | `/candidates/{candidateId}` | Detalle explicable. |
| GET | `/candidates/{candidateId}/cv` | Descarga autorizada. |
| GET | `/job-searches/{id}/export?format=xlsx\|csv` | Exportación. |
| GET | `/cv-files/pending-review` | Cola de adjuntos en `PENDIENTE_REVISION`. |
| POST | `/cv-files/{id}/reclassify` | `{ "action": "PROCESAR" \| "IGNORAR" }`. |

Solicitud de ejecución:

```json
{ "dateFrom": "2026-08-01", "dateTo": "2026-08-27", "forceReprocess": false }
```

Devuelve `202` con `{ "runId": "uuid", "status": "PENDIENTE" }`. Tamaño máximo de página: 100; predeterminado: 20.

### Códigos de error

| HTTP | Código | Uso |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | Datos inválidos o rango incorrecto. |
| 401 | `OUTLOOK_RECONNECTION_REQUIRED` | Token de Microsoft vencido, revocado o ausente. |
| 404 | `NOT_FOUND` | Recurso inexistente. |
| 409 | `RUN_ALREADY_ACTIVE` | Ejecución no permitida por regla de concurrencia. |
| 413 | `FILE_TOO_LARGE` | Adjunto excede límite. |
| 429 | `RATE_LIMITED` | Límite externo; el worker reintenta. |
| 502 | `EXTERNAL_SERVICE_ERROR` | Fallo no recuperable de Graph/Claude. |

---

## 5. Seguridad

- **Transporte:** TLS obligatorio en todos los entornos salvo `localhost`.
- **Sesión/cookies:** `HttpOnly`, `Secure`, `SameSite=Lax`; CSRF activo si hay cookie de sesión; CORS limitado al dominio del frontend.
- **Tokens Microsoft:** cifrados en reposo con clave externa a la base de datos (ver [microsoft-graph-setup.md](./microsoft-graph-setup.md)).
- **Secretos:** variables de entorno / gestor de secretos (ver [deployment.md](./deployment.md)); nunca en el repositorio ni en logs.
- **Descarga de CV:** endpoint autenticado o URL firmada de máximo 5 minutos.
- **Archivos:** escaneo antimalware antes de almacenar/abrir en producción.
- **PDF:** validar firma `%PDF-`, extensión y MIME antes de abrir con PDFBox; límites de memoria/tiempo para evitar archivos maliciosos o corruptos.
- **Datos sensibles:** el LLM recibe solo requisitos y texto del CV; nunca se envían atributos protegidos ni se registran en logs.

---

## 6. Observabilidad

- Logs estructurados en JSON con `correlationId` por solicitud y por ejecución.
- Spring Boot Actuator para health checks.
- Métricas de duración, tasa de error, reintentos, volumen procesado y costo estimado de LLM.
- Alertas de salud, errores de Graph/Claude, trabajos pendientes acumulados y costo estimado (detalle operativo en [deployment.md](./deployment.md)).

---

## 7. Requisitos no funcionales relevantes a la arquitectura

| Área | Requisito |
|---|---|
| Rendimiento | Crear una ejecución responde en menos de 2 s, salvo indisponibilidad externa. |
| Capacidad | 150 CVs/lote, 10 MB/PDF, 3 evaluaciones concurrentes por defecto. |
| Disponibilidad | Reinicios no pierden trabajos ni datos confirmados (worker durable en PostgreSQL). |
| Integridad | Lotes idempotentes (`outlookMessageId + attachmentId`) y migraciones versionadas con Flyway. |
| Compatibilidad | Últimas dos versiones de Chrome, Edge y Firefox. |
