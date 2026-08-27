# PRD — Automatización de Preselección de Candidatos

**Versión:** 1.0  
**Estado:** listo para implementación  
**Propietario funcional:** Área de Reclutamiento  
**Usuarios v1:** un único operador interno

---

## 1. Propósito

El sistema permite crear una búsqueda de puesto, obtener CVs PDF recibidos en Outlook, extraer su contenido y generar una preselección explicable. El resultado prioriza la revisión humana; el sistema no toma decisiones de contratación.

### Objetivos medibles

- Procesar hasta 150 CVs por ejecución sin bloquear la interfaz.
- Mostrar requisitos detectados, evidencia, faltantes y score reproducible para cada CV.
- Mantener trazabilidad de cada ejecución, correo, archivo y error.

### Principios no negociables

1. La decisión de selección siempre es humana.
2. No se usa ni infiere edad, género, fotografía, nacionalidad, religión, estado civil, discapacidad, embarazo u otros atributos no pertinentes al puesto.
3. El LLM extrae evidencia estructurada; el backend calcula el score mediante reglas versionadas.
4. Los CVs y resultados son datos confidenciales, accesibles solo a usuarios autorizados.

---

## 2. Alcance

### Incluye en v1

- Sin login propio de aplicación en v1; el único inicio de sesión será OAuth2 de Microsoft para conectar Outlook.
- Creación, edición versionada y consulta de búsquedas de puesto.
- Conexión OAuth2 delegada a Outlook mediante Microsoft Graph.
- Lectura de la carpeta **Inbox**, sin subcarpetas, dentro de un rango de fechas.
- Descarga, validación y almacenamiento privado de adjuntos PDF.
- Extracción de texto mediante Apache PDFBox.
- Evaluación con Claude API para identificar evidencia por requisito.
- Cálculo determinista y explicable de score.
- Procesamiento durable en segundo plano, progreso, reintentos e historial.
- Dashboard, detalle de candidato, descarga controlada y exportación XLSX/CSV.
- Despliegue con datos y archivos persistentes.

### Fuera de alcance en v1

- Múltiples reclutadores, roles complejos o colaboración.
- Lectura de otras carpetas, reglas de correo o monitoreo continuo.
- OCR, Word, imágenes, ZIP o PDFs protegidos.
- ATS, entrevistas, contratación, notificación a candidatos o edición de CVs.
- Recomendaciones automáticas de rechazo o contratación.

---

## 3. Roles y permisos

| Rol | Permisos |
|---|---|
| Operador interno | Crear/editar búsquedas, conectar Outlook, ejecutar lotes, ver, descargar y exportar resultados. |
| Sistema | Procesar trabajos, llamar a Graph y Claude, auditar y aplicar retención. |

En v1 no existe gestión de usuarios ni login propio. El acceso se limita operacionalmente al único operador y la conexión de Outlook requiere OAuth2 de Microsoft. Usuarios, roles y permisos se incorporarán en v2.

---

## 4. Decisiones funcionales cerradas

| Tema | Decisión v1 |
|---|---|
| Correos | Outlook Inbox del usuario conectado; sin subcarpetas. |
| Fechas | `receivedDateTime` de Graph; zona `America/La_Paz`; rango inclusivo. |
| PDF admitido | MIME, extensión y firma `%PDF-` válidos; máx. 10 MB y 20 adjuntos por correo. Solo se considera CV si el nombre de archivo normalizado contiene `cv`, `curriculum` o `hoja de vida`. |
| Duplicado técnico | `outlookMessageId + attachmentId`; no se procesa dos veces para la misma búsqueda. |
| CV repetido para la misma búsqueda | Si el mismo candidato envía más de un CV, se usa únicamente el recibido más recientemente; los anteriores quedan `REEMPLAZADO` como historial y no se evalúan. Un mismo candidato puede aparecer en búsquedas de puestos distintos. |
| PDF sin texto | Estado `NO_LEGIBLE`; queda visible y no consume LLM. |
| Reejecución | Solo adjuntos nuevos por defecto; `forceReprocess=true` conserva la evaluación anterior y crea otra. |
| Edición | Si hay resultados, crear nueva versión de criterios; nunca alterar resultados históricos. |
| Retención | CVs, resultados y exportaciones: 180 días desde recepción, salvo eliminación manual anterior. |
| Identidad y contacto | El nombre principal se extrae del CV. El reporte incluye correo y teléfono extraídos del CV cuando existan, más el correo remitente como respaldo. |
| Idioma | UI y salida en español; CVs en español o inglés. |

---

## 5. Flujo principal

1. El operador conecta Outlook con Microsoft OAuth2.
2. Crea una búsqueda, requisitos y versión de criterios.
3. Indica fecha desde/hasta y solicita una ejecución.
4. El backend registra el lote, pagina los correos de Graph y registra cada resultado.
5. Valida, guarda y extrae texto de cada PDF admisible.
6. Envía requisitos y texto extraído al LLM para obtener evidencia estructurada.
7. Valida la respuesta y calcula en backend el score.
8. La UI consulta el progreso hasta finalizar.
9. El reclutador revisa, descarga y exporta resultados.

---

## 6. Reglas de negocio

### 6.1 Búsquedas y requisitos

- Título: 3–120 caracteres; descripción: 20–5.000; al menos un requisito.
- Cada requisito: `id`, descripción libre (3–500), tipo, peso y orden.
- Tipos: `OBLIGATORIO` o `DESEABLE`.
- El peso se define individualmente de 1 a 10. Años de experiencia, licencias, tecnologías, disponibilidad, formación o cualquier condición del puesto se expresan como requisitos libres.
- Debe existir mínimo un requisito obligatorio.
- No se descarta automáticamente a un candidato por un requisito faltante: dicho requisito reduce su puntuación y se muestra como gap.
- Todo cambio de criterios crea una versión inmutable.

### 6.2 Rúbrica de scoring

Por cada requisito, el LLM devuelve evidencia y uno de estos niveles; el backend valida y puntúa:

| Nivel | Valor | Definición |
|---|---:|---|
| `CUMPLE` | 1.00 | Evidencia clara en el CV. |
| `PARCIAL` | 0.50 | Evidencia relacionada, pero incompleta o ambigua. |
| `NO_EVIDENCIA` | 0.00 | No existe evidencia suficiente. |
| `NO_APLICA` | excluido | Solo opcionales; se registra razón. |

El score se calcula así: `sumatoria(peso del requisito × valor de cumplimiento) / sumatoria(pesos configurados) × 100`, redondeado a entero. El tipo obligatorio/deseable se muestra claramente en el reporte, pero no produce descarte automático.

- La fórmula, pesos, versión de prompt y modelo se persisten junto al resultado.
- Todo requisito sin evidencia reduce el score y aparece como gap.
- El ranking muestra todos los candidatos ordenados por score descendente; no existen estados de aprobación, descarte ni recomendación automática.

### 6.3 Reglas de IA

- El proveedor es Anthropic Claude API y el modelo inicial aprobado es `claude-sonnet-5`.
- El LLM recibe únicamente requisitos y texto del CV. No se envían foto ni metadatos personales innecesarios.
- La respuesta debe cumplir JSON Schema.
- Por requisito: `requirementId`, `level`, máximo dos evidencias de 300 caracteres, `reason` y `confidence` 0–1.
- Debe extraer nombre, correo, teléfono, fortalezas, gaps y resumen máximo de 600 caracteres. El modelo no puede afirmar “contratar” o “rechazar”.
- Si el JSON es inválido se reintenta una vez; después se registra `ERROR_EVALUACION`.

### 6.4 Estados

| Entidad | Estados |
|---|---|
| Búsqueda | `BORRADOR`, `LISTA`, `PROCESANDO`, `COMPLETADA`, `COMPLETADA_CON_ERRORES`, `ARCHIVADA` |
| Ejecución | `PENDIENTE`, `EXTRAYENDO`, `PROCESANDO`, `COMPLETADA`, `COMPLETADA_CON_ERRORES`, `FALLIDA`, `CANCELADA` |
| CV | `PENDIENTE`, `DESCARGADO`, `PROCESANDO`, `EVALUADO`, `REEMPLAZADO`, `NO_LEGIBLE`, `IGNORADO_NO_ES_CV`, `ERROR_DESCARGA`, `ERROR_EVALUACION`, `CANCELADO` |

`COMPLETADA_CON_ERRORES` aplica si al menos un CV no llega a `EVALUADO`; `FALLIDA` si no se pudo iniciar o consultar Graph.

---

## 7. Requisitos funcionales y aceptación

### RF-01 — Búsquedas

- Crear, listar, ver, editar y archivar búsquedas.
- El listado muestra título, versión, fecha, estado y último lote.

**Aceptación:** al modificar criterios de una búsqueda con resultados, los resultados existentes conservan su versión anterior y la siguiente ejecución usa la nueva.

### RF-02 — Outlook

- Authorization Code Flow con PKCE y permisos delegados mínimos: `User.Read`, `Mail.Read`, `offline_access`.
- Mostrar cuenta conectada y permitir desconectar.
- Al desconectar, eliminar tokens locales y bloquear nuevos lotes.

**Aceptación:** ante `401` de Graph, el lote queda en error controlado, no pierde resultados y la UI solicita reconectar.

### RF-03 — Correos y adjuntos

- Fechas obligatorias, desde <= hasta y rango máximo 365 días.
- Paginación completa de Graph y auditoría de cantidad encontrada.
- Registrar ignorados: no PDF, nombre sin las palabras permitidas de CV, tamaño excedido, firma inválida, protegido o corrupto.
- Reintento de red: tres intentos con espera progresiva.

**Aceptación:** un lote con 2 PDF válidos cuyo nombre contiene una palabra permitida, una imagen y un PDF de 12 MB termina con 2 procesables y 2 ignorados con razón visible. Un PDF válido llamado `documento.pdf` queda `IGNORADO_NO_ES_CV`.

### RF-04 — Procesamiento durable

- `POST` de ejecución responde `202 Accepted` y `runId` inmediatamente.
- Los trabajos se persisten en PostgreSQL y se recuperan tras reinicio.
- Concurrencia configurable; predeterminado 3 CVs, máximo 150 CVs por lote y 5 ejecuciones activas.
- Se permite cancelar; lo no iniciado pasa a `CANCELADO`.

**Aceptación:** si el backend se reinicia durante un lote, los ítems pendientes continúan y los interrumpidos se reintentan según política.

### RF-05 — Texto y evaluación

- PDFBox extrae texto. Menos de 100 caracteres alfanuméricos implica `NO_LEGIBLE`.
- Máximo 30.000 caracteres enviados al modelo, priorizando experiencia, educación y habilidades.
- Se valida cada `requirementId` y valor antes de calcular.

**Aceptación:** un PDF escaneado queda visible como `NO_LEGIBLE`, sin llamada al LLM y sin bloquear otros CVs.

### RF-06 — Resultados

- Ranking ordenado por score descendente; filtros por score, tipo de requisito y estado.
- Detalle: origen, versión de búsqueda, score, evidencia, fortalezas, gaps, resumen y PDF.
- XLSX: búsqueda, versión, ejecución, candidato, correo extraído, teléfono extraído, correo remitente, fecha, score, estado, resumen, fortalezas, gaps y resultado por requisito. CSV contiene lo mismo sin enlaces privados.

**Aceptación:** los valores exportados coinciden con el dashboard y cada fila identifica la versión de criterios usada.

### RF-07 — Eliminación y auditoría

- Eliminar una búsqueda requiere confirmación y borra archivos/resultados asociados.
- Proceso diario aplica la retención.
- Auditoría registra actor, fecha, operación y entidad, sin tokens, claves ni texto de CV.

**Aceptación:** tras eliminación, los PDFs no son descargables ni aparecen en listas o exportaciones.

---

## 8. Modelo de datos mínimo

| Tabla | Campos principales |
|---|---|
| `outlook_connection` | id, microsoft_account_id, token_encrypted, refresh_token_encrypted, expires_at, connected_at, revoked_at |
| `job_search` | id, title, description, status, current_version, created_at, archived_at |
| `job_search_version` | id, job_search_id, version, experience_min_years, scoring_config_json, created_at |
| `job_requirement` | id, search_version_id, text, type, weight, position |
| `extraction_run` | id, job_search_id, search_version_id, date_from, date_to, status, total, processed, evaluated, errors, started_at, completed_at, cancel_requested |
| `processed_email` | id, run_id, outlook_message_id, sender_email, received_at, subject, status |
| `cv_file` | id, run_id, processed_email_id, attachment_id, original_name, mime_type, size_bytes, sha256, storage_key, text_status, status, received_at, expires_at |
| `candidate_evaluation` | id, cv_file_id, search_version_id, candidate_name, candidate_email, candidate_phone, sender_email, score, summary, strengths_json, gaps_json, model_name, prompt_version, evaluated_at |
| `requirement_evidence` | id, evaluation_id, requirement_id, level, evidence_json, reason, confidence, awarded_points |
| `processing_error` | id, run_id, cv_file_id, stage, error_code, retry_count, technical_detail, created_at |
| `audit_event` | id, actor_type, event_type, entity_type, entity_id, metadata_json, created_at |

Índices: unicidad `processed_email(run_id, outlook_message_id)`, unicidad `cv_file(processed_email_id, attachment_id)`, ranking por `candidate_evaluation(search_version_id, eligible, score DESC)` e índice de vencimiento `cv_file(expires_at)`.

---

## 9. Contrato API

Todos los endpoints se sirven bajo `/api`. La v1 no implementa login propio y responde:

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
| GET | `/runs/{runId}` | Progreso y resumen. |
| POST | `/runs/{runId}/cancel` | Solicitar cancelación. |
| GET | `/job-searches/{id}/candidates` | Ranking paginado y filtrable. |
| GET | `/candidates/{candidateId}` | Detalle explicable. |
| GET | `/candidates/{candidateId}/cv` | Descarga autorizada. |
| GET | `/job-searches/{id}/export?format=xlsx|csv` | Exportación. |

Solicitud de ejecución:

```json
{ "dateFrom": "2026-08-01", "dateTo": "2026-08-27", "forceReprocess": false }
```

Devuelve `202` con `{ "runId": "uuid", "status": "PENDIENTE" }`. Tamaño máximo de página: 100; predeterminado: 20.

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

## 10. Arquitectura y seguridad

- **Frontend:** React + TypeScript; no almacena tokens Graph/Claude ni implementa login propio en v1.
- **Backend:** Java 21+, Spring Boot 3.x, Spring Security OAuth2 Client, JPA, Bean Validation y OpenAPI.
- **Base:** PostgreSQL con migraciones Flyway.
- **Procesamiento:** trabajos persistidos en PostgreSQL y worker recuperable. `@Async` puede apoyar concurrencia, pero no es el mecanismo de recuperación.
- **Archivos:** almacenamiento privado compatible con S3 en producción; disco local solo en desarrollo.
- **PDF:** PDFBox con límites de memoria/tiempo; validar antes de abrir.
- **LLM:** Anthropic Claude API con `claude-sonnet-5`, timeouts, reintentos ante 429/5xx y JSON Schema; persistir modelo y versión de prompt. Tras validar calidad con CVs reales se podrá evaluar `claude-haiku-4-5` para procesamiento masivo, sin cambiar el contrato de salida ni la fórmula de score.
- **Secretos:** variables de entorno/gestor de secretos; tokens Microsoft cifrados con clave externa a la BD; no registrar secretos ni CVs en logs.
- **Observabilidad:** logs JSON, correlation ID, Actuator y métricas de duración, error, reintentos, volumen y costo estimado.
- **Web:** TLS; cookies `HttpOnly`, `Secure`, `SameSite=Lax`; CSRF si hay cookie de sesión y CORS limitado al dominio del frontend.
- **Descarga:** endpoint autenticado o URL firmada de máximo 5 minutos.
- **Archivos:** escaneo antimalware antes de almacenar/abrir en producción.

La UI debe mostrar: **“Resultado asistido por IA. Revise la evidencia antes de tomar decisiones de selección.”**

---

## 11. Requisitos no funcionales

| Área | Requisito |
|---|---|
| Rendimiento | Crear una ejecución responde en menos de 2 s, salvo indisponibilidad externa. |
| Capacidad | 150 CVs/lote, 10 MB/PDF, 3 evaluaciones concurrentes por defecto. |
| Disponibilidad | Reinicios no pierden trabajos ni datos confirmados. |
| Integridad | Lotes idempotentes y migraciones versionadas. |
| Accesibilidad | Teclado, etiquetas y contraste adecuado. |
| Compatibilidad | Últimas dos versiones de Chrome, Edge y Firefox. |
| Auditoría | Eventos operativos retenidos al menos 180 días. |
| Respaldo | Backup diario PostgreSQL y prueba de restauración antes de producción. |

---

## 12. Pruebas obligatorias y Definition of Done

Casos obligatorios: creación/edición versionada, OAuth expirado/revocado, rangos y paginación Graph, archivo que contiene/no contiene palabras de CV, PDF válido/falso/corrupto/protegido/grande, CV repetido para una misma búsqueda, idempotencia, reproceso, PDF escaneado, JSON/timeout/rate-limit LLM, fórmula para cada nivel y requisito obligatorio/deseable, reinicio/cancelación, descarga/exportación, retención y eliminación.

Cobertura mínima: unitarias de fórmula y validaciones, integración con Testcontainers PostgreSQL, contratos simulados Graph/Anthropic y E2E del flujo principal.

Una historia está terminada solo si cumple aceptación, posee pruebas relevantes, actualiza OpenAPI, no registra datos sensibles, incluye migración Flyway cuando aplica, tiene revisión de código y fue validada en staging.

---

## 13. Plan de entrega

| Fase | Entregable |
|---|---|
| 1. Fundaciones | Proyecto, seguridad base, PostgreSQL/Flyway, Docker y CI. |
| 2. Búsquedas | CRUD versionado de búsquedas/requisitos, UI y OpenAPI. |
| 3. Outlook y archivos | OAuth2, correos, PDFs, almacenamiento y auditoría. |
| 4. Worker e IA | Lotes durables, PDFBox, contrato LLM, evidencia y score. |
| 5. Resultados | Dashboard, detalle, filtros, exportación y recuperación. |
| 6. Producción | Retención, backups, monitoreo, E2E, despliegue y aprobación funcional. |

---

## 14. Dependencias previas a producción

1. Administrador Microsoft Entra ID aprueba registro, redirect URI y permisos `Mail.Read`, `User.Read`, `offline_access`.
2. RR. HH./Legal aprueba el uso de IA externa y la retención de 180 días, o define una política diferente.
3. Existe cuenta Anthropic con presupuesto mensual definido y claves separadas para staging/producción.
4. Se seleccionan dominio, hosting y almacenamiento privado.

Con estas dependencias resueltas, no quedan decisiones funcionales bloqueantes para comenzar la implementación.

---

## 15. Plan de desarrollo — Backlog ejecutable

### Criterio común de finalización

Una tarea termina solo si tiene revisión de código, pruebas relevantes, no expone secretos ni contenido de CV en logs, actualiza la documentación necesaria y puede demostrarse localmente o en staging.

### EPIC 1 — Fundaciones y calidad

**Objetivo:** contar con una base ejecutable, repetible y segura.

#### Feature 1.1 — Inicialización y entorno local

**Tareas**

- T1.1.1 Crear los módulos frontend React + TypeScript y backend Java 21 + Spring Boot 3.x.
- T1.1.2 Configurar Docker Compose con PostgreSQL y perfiles local, staging y producción.
- T1.1.3 Configurar Flyway y las variables de entorno requeridas.
- T1.1.4 Documentar prerrequisitos, arranque y pruebas en el README.

**Criterios de aceptación**

- Un desarrollador nuevo inicia frontend, backend y base de datos siguiendo únicamente el README.
- Flyway crea el esquema sin errores y el endpoint de salud responde correctamente.
- No existen claves Microsoft, Anthropic o de base de datos en el repositorio.

#### Feature 1.2 — API, errores y observabilidad

**Tareas**

- T1.2.1 Implementar el formato estándar de respuesta y manejador global de errores.
- T1.2.2 Configurar Bean Validation, paginación, correlationId y manejo de excepciones.
- T1.2.3 Exponer OpenAPI/Swagger, Actuator, logs JSON y métricas.

**Criterios de aceptación**

- Un error de validación devuelve 400, código VALIDATION_ERROR y formato estándar.
- Cada solicitud y ejecución puede rastrearse mediante correlationId.
- Swagger describe todos los endpoints implementados y los logs no contienen datos de CV.

**Dependencia:** ninguna.

### EPIC 2 — Búsquedas y requisitos configurables

**Objetivo:** definir perfiles de cualquier área sin cambios de código.

#### Feature 2.1 — Crear y consultar búsquedas

**Tareas**

- T2.1.1 Crear migraciones, entidades y repositorios de búsqueda, versión y requisito.
- T2.1.2 Implementar crear, listar y consultar detalle de búsquedas.
- T2.1.3 Crear formulario con título, descripción, requisito libre, tipo obligatorio/deseable, peso 1–10 y orden.
- T2.1.4 Implementar validaciones de campos y mensajes de error en frontend.

**Criterios de aceptación**

- Se puede crear una búsqueda de chofer, vendedor, desarrollador o gerente usando requisitos libres.
- No se guarda un requisito vacío, sin tipo, sin peso válido o una búsqueda sin requisito obligatorio.
- El detalle devuelve exactamente los requisitos, tipos, pesos y orden guardados.

#### Feature 2.2 — Versionar, editar y archivar

**Tareas**

- T2.2.1 Implementar actualización con creación de nueva versión cuando cambian criterios.
- T2.2.2 Asociar cada ejecución y resultado a la versión usada.
- T2.2.3 Implementar archivado y advertencia de impacto en la UI.

**Criterios de aceptación**

- Resultados anteriores nunca cambian al editar requisitos.
- Una nueva ejecución usa la versión actual y una archivada no permite nuevas ejecuciones.

**Dependencia:** EPIC 1.

### EPIC 3 — Outlook y captura de CVs

**Objetivo:** obtener desde Inbox solo los CVs válidos y mantener trazabilidad.

#### Feature 3.1 — Conectar Microsoft Graph

**Tareas**

- T3.1.1 Registrar aplicación en Entra ID y configurar redirect URI por entorno.
- T3.1.2 Implementar OAuth2 Authorization Code con PKCE, User.Read, Mail.Read y offline_access.
- T3.1.3 Cifrar tokens, refrescarlos automáticamente y permitir desconexión.
- T3.1.4 Crear pantalla de estado y conexión Outlook.

**Criterios de aceptación**

- El operador conecta Outlook y visualiza el estado conectado.
- Token vencido se refresca automáticamente; token revocado exige reconexión sin perder resultados.
- Desconectar borra tokens locales y bloquea nuevas ejecuciones hasta reconectar.

#### Feature 3.2 — Crear ejecución y leer Inbox

**Tareas**

- T3.2.1 Crear entidades de ejecución, correo procesado, archivo CV y error de proceso.
- T3.2.2 Implementar creación de ejecución validando rango inclusivo America/La_Paz y máximo 365 días.
- T3.2.3 Consultar Inbox por receivedDateTime, paginar Graph y registrar correos encontrados.
- T3.2.4 Implementar historial y consulta de estado de ejecución.

**Criterios de aceptación**

- La creación responde 202 con runId en menos de dos segundos.
- Se procesan todas las páginas de Graph y el historial muestra rango, fecha, estado y cantidades.
- Un rango inválido no realiza llamadas a Graph.
- Más de 150 CVs válidos termina controladamente e informa que debe reducirse el rango.

#### Feature 3.3 — Filtrar y almacenar adjuntos

**Tareas**

- T3.3.1 Normalizar nombre: minúsculas, sin tildes y sin caracteres especiales.
- T3.3.2 Aceptar solo PDF cuyo nombre contenga cv, curriculum o hoja de vida.
- T3.3.3 Validar extensión, MIME, firma %PDF-, máximo 10 MB y máximo 20 adjuntos por correo.
- T3.3.4 Guardar archivo privado, SHA-256 y razón de ignorado/error.
- T3.3.5 Reintentar descarga tres veces con espera progresiva.

**Criterios de aceptación**

- CV_Juan.pdf, Currículum Maria.pdf y Hoja de vida Pedro.pdf se aceptan.
- documento.pdf, imágenes y PDF mayores a 10 MB quedan ignorados con motivo y no llegan a IA.
- Una descarga fallida no detiene los otros archivos.

#### Feature 3.4 — Deduplicar y reemplazar CVs

**Tareas**

- T3.4.1 Aplicar idempotencia por outlookMessageId + attachmentId.
- T3.4.2 Identificar candidato repetido en una búsqueda por correo extraído/remitente y hash cuando exista.
- T3.4.3 Mantener activo solo el CV más reciente y marcar anteriores REEMPLAZADO.
- T3.4.4 Implementar reproceso forzado conservando resultados históricos.

**Criterios de aceptación**

- Ejecutar dos veces el mismo rango no crea copias de adjuntos.
- Para el mismo puesto, solo el CV más reciente queda evaluable y los anteriores son históricos.
- El mismo candidato puede aparecer en búsquedas de puestos diferentes.

**Dependencia:** EPIC 1 y EPIC 2.

### EPIC 4 — Worker, IA y scoring

**Objetivo:** procesar CVs de manera durable y generar un score explicable.

#### Feature 4.1 — Procesamiento durable y progreso

**Tareas**

- T4.1.1 Implementar cola persistida en PostgreSQL y worker recuperable.
- T4.1.2 Configurar concurrencia por variable, con valor inicial tres CVs.
- T4.1.3 Implementar estados, cancelación, reintentos y recuperación tras reinicio.
- T4.1.4 Exponer estado de ejecución y barra de progreso React.

**Criterios de aceptación**

- Reiniciar el backend no pierde trabajos confirmados; ítems interrumpidos se reintentan con seguridad.
- La UI muestra total, procesados, evaluados, ignorados, errores y estado sin recargar.
- Al cancelar, los ítems no iniciados quedan CANCELADO.

#### Feature 4.2 — Texto y legibilidad

**Tareas**

- T4.2.1 Integrar PDFBox con límites de memoria y tiempo.
- T4.2.2 Persistir texto protegido y estado técnico.
- T4.2.3 Marcar NO_LEGIBLE si existen menos de 100 caracteres alfanuméricos.
- T4.2.4 Limitar a 30.000 caracteres el texto enviado a IA.

**Criterios de aceptación**

- Un PDF digital extrae texto utilizable.
- Un PDF escaneado queda visible como NO_LEGIBLE y no llama a Claude.
- Un error de extracción no detiene el lote.

#### Feature 4.3 — Anthropic Claude Sonnet 5

**Tareas**

- T4.3.1 Implementar cliente Anthropic para claude-sonnet-5 con timeout y reintento en 429/5xx.
- T4.3.2 Definir JSON Schema y prompt versionado para nombre, correo, teléfono, evidencia, fortalezas, gaps y resumen.
- T4.3.3 Enviar únicamente requisitos y texto extraído; excluir atributos protegidos.
- T4.3.4 Registrar modelo, tokens, versión de prompt y manejar JSON inválido.

**Criterios de aceptación**

- La respuesta cumple JSON Schema o termina ERROR_EVALUACION tras un reintento.
- Cada evidencia referencia un requisito existente e incluye nivel, razón y máximo dos citas.
- Ni prompt ni resultado puntúan atributos protegidos, y los logs no guardan CVs.

#### Feature 4.4 — Fórmula y ranking determinista

**Tareas**

- T4.4.1 Implementar CUMPLE=1, PARCIAL=0.5, NO_EVIDENCIA=0 y NO_APLICA excluido.
- T4.4.2 Implementar suma ponderada con pesos 1–10 y guardar puntos por requisito.
- T4.4.3 Generar gaps y fortalezas sin aprobar/rechazar candidatos.
- T4.4.4 Crear pruebas unitarias de fórmula y valores límite.

**Criterios de aceptación**

- La misma evidencia genera siempre el mismo score entero de 0 a 100.
- Un requisito faltante reduce score, pero no descarta ni oculta al candidato.
- El detalle explica peso, nivel, evidencia y puntos de cada requisito.

**Dependencia:** EPIC 3.

### EPIC 5 — Ranking, exportación y retención

**Objetivo:** entregar resultados útiles y auditable para revisión humana.

#### Feature 5.1 — Dashboard y detalle de candidato

**Tareas**

- T5.1.1 Implementar ranking paginado con filtros por score, tipo de requisito y estado.
- T5.1.2 Crear tabla ordenada por score descendente.
- T5.1.3 Crear detalle con nombre/contacto CV, remitente, evidencia, resumen, gaps y versión.
- T5.1.4 Implementar descarga del PDF desde almacenamiento privado.

**Criterios de aceptación**

- El lote muestra evaluados, no legibles, ignorados y con error con su estado real.
- El ranking no muestra aprobación/rechazo y advierte que la decisión final es humana.
- La descarga corresponde al PDF original del candidato.

#### Feature 5.2 — Exportación y eliminación

**Tareas**

- T5.2.1 Generar XLSX y CSV con contacto, score, estado, requisitos, evidencia y versión.
- T5.2.2 Añadir fecha de generación y aviso de confidencialidad.
- T5.2.3 Implementar eliminación manual de búsqueda y archivos asociados.
- T5.2.4 Ejecutar retención diaria de 180 días.

**Criterios de aceptación**

- Los archivos exportados coinciden con dashboard.
- Eliminar una búsqueda o vencer 180 días elimina PDFs y evita nuevas descargas/exportaciones.
- Las operaciones quedan auditadas sin texto de CV.

**Dependencia:** EPIC 4.

### EPIC 6 — Producción y aceptación

**Objetivo:** desplegar, operar y validar el sistema de extremo a extremo.

#### Feature 6.1 — Infraestructura y monitoreo

**Tareas**

- T6.1.1 Provisionar frontend, backend, PostgreSQL administrado y almacenamiento privado.
- T6.1.2 Configurar dominio, TLS, secretos, backups diarios y restauración de prueba.
- T6.1.3 Configurar acceso operacional limitado al único operador, sin login/roles de aplicación v1.
- T6.1.4 Configurar alertas de salud, errores Graph/Claude, trabajos pendientes y costo estimado.

**Criterios de aceptación**

- Reiniciar backend no pierde datos ni trabajos.
- Credenciales existen únicamente como secretos del entorno.
- Un backup de PostgreSQL se restaura correctamente en un entorno de prueba.

#### Feature 6.2 — Prueba final y salida

**Tareas**

- T6.2.1 Ejecutar E2E con vacante de prueba y CVs no productivos.
- T6.2.2 Comparar ranking contra revisión manual del operador.
- T6.2.3 Medir tokens/costo por CV y por lote de 150.
- T6.2.4 Documentar operación diaria, reconexión Outlook y recuperación de errores.

**Criterios de aceptación**

- El flujo Inbox → ranking → XLSX funciona en staging y producción.
- El operador identifica y resuelve CV ignorado, no legible o con error sin apoyo de desarrollo.
- Modelo, costo estimado y resultado de aceptación quedan registrados.

**Dependencia:** EPIC 5.
