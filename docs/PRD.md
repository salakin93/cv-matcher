# PRD — Automatización de Preselección de Candidatos

**Versión:** 1.1
**Estado:** listo para implementación
**Propietario funcional:** Área de Reclutamiento
**Usuarios v1:** un único operador interno
**Documentos relacionados:** [architecture.md](./architecture.md) · [database.md](./database.md) · [microsoft-graph-setup.md](./microsoft-graph-setup.md) · [anthropic-setup.md](./anthropic-setup.md) · [deployment.md](./deployment.md)

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
- **Cola de revisión manual** para adjuntos que no matchean el nombre esperado de CV (ver 4 y RF-08).
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
| Operador interno | Crear/editar búsquedas, conectar Outlook, ejecutar lotes, ver, descargar, reclasificar adjuntos y exportar resultados. |
| Sistema | Procesar trabajos, llamar a Graph y Claude, auditar y aplicar retención. |

En v1 no existe gestión de usuarios ni login propio. El acceso se limita operacionalmente al único operador y la conexión de Outlook requiere OAuth2 de Microsoft. Usuarios, roles y permisos se incorporarán en v2.

---

## 4. Decisiones funcionales cerradas

| Tema | Decisión v1 |
|---|---|
| Correos | Outlook Inbox del usuario conectado; sin subcarpetas. |
| Fechas | `receivedDateTime` de Graph; zona `America/La_Paz`; rango inclusivo. |
| **PDF admitido** | MIME, extensión y firma `%PDF-` válidos; máx. 10 MB y 20 adjuntos por correo. Si el nombre de archivo normalizado contiene `cv`, `curriculum` o `hoja de vida`, se procesa automáticamente. **Si no matchea ninguna palabra pero es un PDF válido, no se descarta: queda en estado `PENDIENTE_REVISION` en una cola de revisión manual** (ver RF-08), en vez de ignorarse silenciosamente. |
| Duplicado técnico | `outlookMessageId + attachmentId`; no se procesa dos veces para la misma búsqueda. |
| **CV repetido para la misma búsqueda** | La deduplicación de candidato **nunca se basa únicamente en el remitente**. Orden de prioridad: (1) correo del candidato extraído del propio CV; (2) si no hay correo, teléfono + nombre normalizado; (3) solo si no hay correo, teléfono ni nombre confiable, se usa remitente + nombre de archivo como último recurso, marcando el resultado con `dedup_method = REMITENTE_FALLBACK` para que el operador lo revise. Esto evita fusionar candidatos distintos reenviados desde una misma cuenta (ej. RRHH o una agencia reenviando varios CVs). Se conserva únicamente el CV más reciente por candidato identificado; los anteriores quedan `REEMPLAZADO` como historial. Un mismo candidato puede aparecer en búsquedas de puestos distintos. |
| PDF sin texto | Estado `NO_LEGIBLE`; queda visible y no consume LLM. |
| **Límite de 150 CVs por ejecución** | Graph se pagina en orden `receivedDateTime` ascendente (los correos más antiguos del rango primero — asunción a confirmar con negocio si se prefiere el orden inverso). En cuanto el conteo de CVs procesables llega a 150, la ejecución se marca `LIMITE_ALCANZADO`, se detiene la paginación y los 150 ya identificados se procesan con normalidad. Los correos restantes del rango quedan listados en el resumen de la ejecución (con su fecha) para que el operador lance una ejecución adicional acotando el rango; la idempotencia evita reprocesar los primeros 150. |
| Reejecución | Solo adjuntos nuevos por defecto; `forceReprocess=true` conserva la evaluación anterior y crea otra. |
| Edición | Si hay resultados, crear nueva versión de criterios; nunca alterar resultados históricos. |
| Retención | CVs, resultados y exportaciones: 180 días desde recepción, salvo eliminación manual anterior. |
| Identidad y contacto | El nombre principal se extrae del CV. El reporte incluye correo y teléfono extraídos del CV cuando existan, más el correo remitente como respaldo. |
| Idioma | UI y salida en español; CVs en español o inglés. |
| **Campo `confidence` del LLM** | Es informativo, no participa en la fórmula de score. Se muestra por evidencia en el detalle del candidato. Si el promedio de `confidence` de un candidato es menor a `0.4` (configurable), el ranking lo marca visualmente como "confianza baja — revisar evidencia", sin alterar el score ni el orden. |

---

## 5. Flujo principal

1. El operador conecta Outlook con Microsoft OAuth2.
2. Crea una búsqueda, requisitos y versión de criterios.
3. Indica fecha desde/hasta y solicita una ejecución.
4. El backend registra el lote, pagina los correos de Graph (hasta el límite de 150 CVs procesables) y registra cada resultado.
5. Valida, guarda y extrae texto de cada PDF admisible; los adjuntos ambiguos van a la cola de revisión manual.
6. Envía requisitos y texto extraído al LLM para obtener evidencia estructurada, incluyendo `confidence` por evidencia.
7. Valida la respuesta y calcula en backend el score determinista.
8. La UI consulta el progreso hasta finalizar.
9. El operador revisa la cola de adjuntos pendientes de revisión, descarga y exporta resultados.

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

Por cada requisito, el LLM devuelve evidencia, un nivel y un `confidence` (0–1); el backend valida y puntúa usando únicamente el nivel:

| Nivel | Valor | Definición |
|---|---:|---|
| `CUMPLE` | 1.00 | Evidencia clara en el CV. |
| `PARCIAL` | 0.50 | Evidencia relacionada, pero incompleta o ambigua. |
| `NO_EVIDENCIA` | 0.00 | No existe evidencia suficiente. |
| `NO_APLICA` | excluido | Solo opcionales; se registra razón. |

`Score = sumatoria(peso del requisito × valor de cumplimiento) / sumatoria(pesos configurados) × 100`, redondeado a entero. El tipo obligatorio/deseable se muestra claramente en el reporte, pero no produce descarte automático. `confidence` es solo informativo (ver sección 4).

- La fórmula, pesos, versión de prompt y modelo se persisten junto al resultado.
- Todo requisito sin evidencia reduce el score y aparece como gap.
- El ranking muestra todos los candidatos ordenados por score descendente; no existen estados de aprobación, descarte ni recomendación automática.

### 6.3 Reglas de IA

Ver detalle completo del contrato del modelo en [anthropic-setup.md](./anthropic-setup.md). Resumen:

- Proveedor: Anthropic Claude API, modelo inicial `claude-sonnet-5`.
- El LLM recibe únicamente requisitos y texto del CV; no se envían foto ni metadatos personales innecesarios.
- La respuesta debe cumplir un JSON Schema fijo, incluyendo `confidence` por evidencia.
- El modelo no puede afirmar "contratar" o "rechazar".
- Si el JSON es inválido se reintenta una vez; después se registra `ERROR_EVALUACION`.

### 6.4 Estados

| Entidad | Estados |
|---|---|
| Búsqueda | `BORRADOR`, `LISTA`, `PROCESANDO`, `COMPLETADA`, `COMPLETADA_CON_ERRORES`, `ARCHIVADA` |
| Ejecución | `PENDIENTE`, `EXTRAYENDO`, `PROCESANDO`, `COMPLETADA`, `COMPLETADA_CON_ERRORES`, `LIMITE_ALCANZADO`, `FALLIDA`, `CANCELADA` |
| CV | `PENDIENTE`, `PENDIENTE_REVISION`, `DESCARGADO`, `PROCESANDO`, `EVALUADO`, `REEMPLAZADO`, `NO_LEGIBLE`, `IGNORADO_NO_ES_CV`, `ERROR_DESCARGA`, `ERROR_EVALUACION`, `CANCELADO` |

`COMPLETADA_CON_ERRORES` aplica si al menos un CV no llega a `EVALUADO`; `LIMITE_ALCANZADO` si la ejecución se detuvo por el tope de 150 CVs con correos restantes sin procesar; `FALLIDA` si no se pudo iniciar o consultar Graph.

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
- Paginación de Graph en orden `receivedDateTime` ascendente, con corte al alcanzar 150 CVs procesables.
- Registrar ignorados: no PDF, tamaño excedido, firma inválida, protegido o corrupto.
- Registrar en `PENDIENTE_REVISION` los PDF válidos cuyo nombre no contiene una palabra permitida.
- Reintento de red: tres intentos con espera progresiva.

**Aceptación:** un lote con 2 PDF válidos cuyo nombre contiene una palabra permitida, un PDF válido llamado `documento.pdf`, una imagen y un PDF de 12 MB termina con 2 evaluables, 1 en `PENDIENTE_REVISION`, y 2 ignorados con razón visible (imagen y tamaño excedido). Un lote que identifica 150 CVs procesables antes de agotar el rango de fechas termina en `LIMITE_ALCANZADO` y lista los correos restantes no procesados.

### RF-04 — Procesamiento durable

- `POST` de ejecución responde `202 Accepted` y `runId` inmediatamente.
- Los trabajos se persisten en PostgreSQL y se recuperan tras reinicio.
- Concurrencia configurable; predeterminado 3 CVs, máximo 150 CVs por lote y 5 ejecuciones activas.
- Se permite cancelar; lo no iniciado pasa a `CANCELADO`.

**Aceptación:** si el backend se reinicia durante un lote, los ítems pendientes continúan y los interrumpidos se reintentan según política.

### RF-05 — Texto y evaluación

- PDFBox extrae texto. Menos de 100 caracteres alfanuméricos implica `NO_LEGIBLE`.
- Máximo 30.000 caracteres enviados al modelo, priorizando experiencia, educación y habilidades.
- Se valida cada `requirementId`, nivel y `confidence` antes de calcular.

**Aceptación:** un PDF escaneado queda visible como `NO_LEGIBLE`, sin llamada al LLM y sin bloquear otros CVs.

### RF-06 — Resultados

- Ranking ordenado por score descendente; filtros por score, tipo de requisito, estado y confianza baja.
- Detalle: origen, versión de búsqueda, score, evidencia con `confidence`, fortalezas, gaps, resumen y PDF.
- XLSX: búsqueda, versión, ejecución, candidato, correo extraído, teléfono extraído, correo remitente, `dedup_method`, fecha, score, estado, resumen, fortalezas, gaps y resultado por requisito. CSV contiene lo mismo sin enlaces privados.

**Aceptación:** los valores exportados coinciden con el dashboard y cada fila identifica la versión de criterios usada.

### RF-07 — Eliminación y auditoría

- Eliminar una búsqueda requiere confirmación y borra archivos/resultados asociados.
- Proceso diario aplica la retención.
- Auditoría registra actor, fecha, operación y entidad, sin tokens, claves ni texto de CV.

**Aceptación:** tras eliminación, los PDFs no son descargables ni aparecen en listas o exportaciones.

### RF-08 — Revisión manual de adjuntos no clasificados (nuevo)

- Vista "Pendientes de revisión" con lista de adjuntos PDF válidos cuyo nombre no matcheó las palabras permitidas.
- Acción "Procesar como CV" (pasa a `PENDIENTE` y entra al flujo normal de extracción/evaluación) o "Confirmar que no es CV" (pasa a `IGNORADO_NO_ES_CV`).
- Endpoint `POST /api/cv-files/{id}/reclassify` con body `{ "action": "PROCESAR" | "IGNORAR" }`.

**Aceptación:** un adjunto en `PENDIENTE_REVISION` reclasificado como "Procesar como CV" pasa por extracción de texto y evaluación LLM igual que un CV detectado automáticamente. Uno confirmado como "no es CV" nunca vuelve a aparecer en la cola ni consume LLM.

---

## 8. Requisitos no funcionales

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

Detalle de arquitectura y seguridad en [architecture.md](./architecture.md).

---

## 9. Pruebas obligatorias y Definition of Done

Casos obligatorios: creación/edición versionada, OAuth expirado/revocado, rangos y paginación Graph, corte por límite de 150 CVs, adjunto ambiguo → cola de revisión → reclasificación, PDF válido/falso/corrupto/protegido/grande, CV repetido para una misma búsqueda con las 3 prioridades de deduplicación, idempotencia, reproceso, PDF escaneado, JSON/timeout/rate-limit LLM, fórmula para cada nivel y requisito obligatorio/deseable, candidato con confidence promedio bajo marcado visualmente, reinicio/cancelación, descarga/exportación, retención y eliminación.

Cobertura mínima: unitarias de fórmula y validaciones, integración con Testcontainers PostgreSQL, contratos simulados Graph/Anthropic y E2E del flujo principal.

Una historia está terminada solo si cumple aceptación, posee pruebas relevantes, actualiza OpenAPI, no registra datos sensibles, incluye migración Flyway cuando aplica, tiene revisión de código y fue validada en staging.

---

## 10. Plan de entrega

| Fase | Entregable | Doc de referencia |
|---|---|---|
| 1. Fundaciones | Proyecto, seguridad base, PostgreSQL/Flyway, Docker y CI. | architecture.md, database.md |
| 2. Búsquedas | CRUD versionado de búsquedas/requisitos, UI y OpenAPI. | architecture.md |
| 3. Outlook y archivos | OAuth2, correos, PDFs, cola de revisión manual, almacenamiento y auditoría. | microsoft-graph-setup.md |
| 4. Worker e IA | Lotes durables, PDFBox, contrato LLM, evidencia y score. | anthropic-setup.md |
| 5. Resultados | Dashboard, detalle, filtros, exportación y recuperación. | architecture.md |
| 6. Producción | Retención, backups, monitoreo, E2E, despliegue y aprobación funcional. | deployment.md |

---

## 11. Dependencias previas a producción

1. Administrador Microsoft Entra ID aprueba registro, redirect URI y permisos `Mail.Read`, `User.Read`, `offline_access` (ver microsoft-graph-setup.md).
2. RR. HH./Legal aprueba el uso de IA externa y la retención de 180 días, o define una política diferente.
3. Existe cuenta Anthropic con presupuesto mensual definido y claves separadas para staging/producción (ver anthropic-setup.md).
4. Se seleccionan dominio, hosting y almacenamiento privado (ver deployment.md).

Con estas dependencias resueltas, no quedan decisiones funcionales bloqueantes para comenzar la implementación.

---

## 12. Backlog ejecutable

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
- Un error de validación devuelve 400, código `VALIDATION_ERROR` y formato estándar.
- Cada solicitud y ejecución puede rastrearse mediante `correlationId`.
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

**Objetivo:** obtener desde Inbox solo los CVs válidos, enrutar los ambiguos a revisión manual y mantener trazabilidad.

#### Feature 3.1 — Conectar Microsoft Graph

**Tareas**
- T3.1.1 Registrar aplicación en Entra ID y configurar redirect URI por entorno.
- T3.1.2 Implementar OAuth2 Authorization Code con PKCE, `User.Read`, `Mail.Read` y `offline_access`.
- T3.1.3 Cifrar tokens, refrescarlos automáticamente y permitir desconexión.
- T3.1.4 Crear pantalla de estado y conexión Outlook.

**Criterios de aceptación**
- El operador conecta Outlook y visualiza el estado conectado.
- Token vencido se refresca automáticamente; token revocado exige reconexión sin perder resultados.
- Desconectar borra tokens locales y bloquea nuevas ejecuciones hasta reconectar.

#### Feature 3.2 — Crear ejecución y leer Inbox con límite de lote

**Tareas**
- T3.2.1 Crear entidades de ejecución, correo procesado, archivo CV y error de proceso.
- T3.2.2 Implementar creación de ejecución validando rango inclusivo `America/La_Paz` y máximo 365 días.
- T3.2.3 Consultar Inbox por `receivedDateTime` ascendente, paginar Graph y detener al llegar a 150 CVs procesables.
- T3.2.4 Marcar la ejecución como `LIMITE_ALCANZADO` y listar los correos restantes no procesados en el resumen.
- T3.2.5 Implementar historial y consulta de estado de ejecución.

**Criterios de aceptación**
- La creación responde 202 con `runId` en menos de dos segundos.
- Se procesan todas las páginas de Graph hasta el rango o el límite de 150, lo que ocurra primero.
- Un rango inválido no realiza llamadas a Graph.
- Al superar 150 CVs válidos, la ejecución termina en `LIMITE_ALCANZADO` e informa cuántos correos quedaron fuera y sus fechas.

#### Feature 3.3 — Filtrar, almacenar y enrutar adjuntos ambiguos

**Tareas**
- T3.3.1 Normalizar nombre: minúsculas, sin tildes y sin caracteres especiales.
- T3.3.2 Marcar como procesable automático todo PDF cuyo nombre contenga `cv`, `curriculum` o `hoja de vida`.
- T3.3.3 Marcar como `PENDIENTE_REVISION` todo PDF válido que no matchee ninguna palabra permitida, en vez de ignorarlo.
- T3.3.4 Validar extensión, MIME, firma `%PDF-`, máximo 10 MB y máximo 20 adjuntos por correo.
- T3.3.5 Guardar archivo privado, SHA-256 y razón de ignorado/error/revisión.
- T3.3.6 Reintentar descarga tres veces con espera progresiva.

**Criterios de aceptación**
- `CV_Juan.pdf`, `Currículum Maria.pdf` y `Hoja de vida Pedro.pdf` se aceptan automáticamente.
- `documento.pdf` válido queda en `PENDIENTE_REVISION`, visible en la cola de revisión, no ignorado silenciosamente.
- Imágenes y PDF mayores a 10 MB quedan ignorados con motivo y no llegan a IA ni a la cola de revisión.
- Una descarga fallida no detiene los otros archivos.

#### Feature 3.4 — Deduplicar y reemplazar CVs sin fusionar candidatos distintos

**Tareas**
- T3.4.1 Aplicar idempotencia por `outlookMessageId + attachmentId`.
- T3.4.2 Implementar prioridad de deduplicación: correo extraído del CV → teléfono + nombre normalizado → remitente + nombre de archivo como último recurso.
- T3.4.3 Marcar `dedup_method = REMITENTE_FALLBACK` cuando se use el último recurso, para revisión del operador.
- T3.4.4 Mantener activo solo el CV más reciente por candidato identificado y marcar anteriores `REEMPLAZADO`.
- T3.4.5 Implementar reproceso forzado conservando resultados históricos.

**Criterios de aceptación**
- Ejecutar dos veces el mismo rango no crea copias de adjuntos.
- Dos CVs distintos reenviados por el mismo remitente, cada uno con correo de candidato distinto extraído, se tratan como dos candidatos diferentes (no se fusionan).
- Solo cuando no hay correo, teléfono ni nombre confiable, el sistema recurre al remitente y lo marca como `REMITENTE_FALLBACK`.
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
- La UI muestra total, procesados, evaluados, en revisión manual, ignorados, errores y estado sin recargar.
- Al cancelar, los ítems no iniciados quedan `CANCELADO`.

#### Feature 4.2 — Texto y legibilidad

**Tareas**
- T4.2.1 Integrar PDFBox con límites de memoria y tiempo.
- T4.2.2 Persistir texto protegido y estado técnico.
- T4.2.3 Marcar `NO_LEGIBLE` si existen menos de 100 caracteres alfanuméricos.
- T4.2.4 Limitar a 30.000 caracteres el texto enviado a IA.

**Criterios de aceptación**
- Un PDF digital extrae texto utilizable.
- Un PDF escaneado queda visible como `NO_LEGIBLE` y no llama a Claude.
- Un error de extracción no detiene el lote.

#### Feature 4.3 — Integración Anthropic Claude Sonnet 5

**Tareas**
- T4.3.1 Implementar cliente Anthropic para `claude-sonnet-5` con timeout y reintento en 429/5xx.
- T4.3.2 Definir JSON Schema y prompt versionado para nombre, correo, teléfono, evidencia (con `confidence`), fortalezas, gaps y resumen.
- T4.3.3 Enviar únicamente requisitos y texto extraído; excluir atributos protegidos.
- T4.3.4 Registrar modelo, tokens, versión de prompt y manejar JSON inválido.

**Criterios de aceptación**
- La respuesta cumple JSON Schema (incluyendo `confidence` 0–1 por evidencia) o termina `ERROR_EVALUACION` tras un reintento.
- Cada evidencia referencia un requisito existente e incluye nivel, razón, `confidence` y máximo dos citas.
- Ni prompt ni resultado puntúan atributos protegidos, y los logs no guardan CVs.

#### Feature 4.4 — Fórmula y ranking determinista

**Tareas**
- T4.4.1 Implementar `CUMPLE=1`, `PARCIAL=0.5`, `NO_EVIDENCIA=0` y `NO_APLICA` excluido.
- T4.4.2 Implementar suma ponderada con pesos 1–10 y guardar puntos por requisito.
- T4.4.3 Generar gaps y fortalezas sin aprobar/rechazar candidatos.
- T4.4.4 Calcular el promedio de `confidence` del candidato y marcar "confianza baja" si es menor a 0.4, sin alterar el score.
- T4.4.5 Crear pruebas unitarias de fórmula y valores límite.

**Criterios de aceptación**
- La misma evidencia genera siempre el mismo score entero de 0 a 100.
- Un requisito faltante reduce score, pero no descarta ni oculta al candidato.
- El detalle explica peso, nivel, evidencia, `confidence` y puntos de cada requisito.
- Un candidato con `confidence` promedio menor a 0.4 aparece marcado en el ranking sin cambiar su posición por score.

**Dependencia:** EPIC 3.

### EPIC 5 — Ranking, revisión manual, exportación y retención

**Objetivo:** entregar resultados útiles y auditables para revisión humana.

#### Feature 5.1 — Dashboard y detalle de candidato

**Tareas**
- T5.1.1 Implementar ranking paginado con filtros por score, tipo de requisito, estado y confianza baja.
- T5.1.2 Crear tabla ordenada por score descendente.
- T5.1.3 Crear detalle con nombre/contacto CV, remitente, `dedup_method`, evidencia con `confidence`, resumen, gaps y versión.
- T5.1.4 Implementar descarga del PDF desde almacenamiento privado.

**Criterios de aceptación**
- El lote muestra evaluados, no legibles, en revisión manual, ignorados y con error con su estado real.
- El ranking no muestra aprobación/rechazo y advierte que la decisión final es humana.
- La descarga corresponde al PDF original del candidato.

#### Feature 5.2 — Cola de revisión manual de adjuntos ambiguos

**Tareas**
- T5.2.1 Crear vista de adjuntos en `PENDIENTE_REVISION` con nombre original, correo y fecha de recepción.
- T5.2.2 Implementar acciones "Procesar como CV" e "Ignorar" con el endpoint `POST /api/cv-files/{id}/reclassify`.
- T5.2.3 Reencolar automáticamente para extracción/evaluación al reclasificar como CV.

**Criterios de aceptación**
- Un adjunto ambiguo reclasificado como CV pasa por extracción y evaluación normalmente y aparece en el ranking.
- Un adjunto confirmado como "no es CV" no vuelve a aparecer en la cola ni se reprocesa.

#### Feature 5.3 — Exportación y eliminación

**Tareas**
- T5.3.1 Generar XLSX y CSV con contacto, score, estado, `dedup_method`, requisitos, evidencia y versión.
- T5.3.2 Añadir fecha de generación y aviso de confidencialidad.
- T5.3.3 Implementar eliminación manual de búsqueda y archivos asociados.
- T5.3.4 Ejecutar retención diaria de 180 días.

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
- T6.2.1 Ejecutar E2E con vacante de prueba y CVs no productivos, incluyendo un caso de límite de 150 y uno de reclasificación manual.
- T6.2.2 Comparar ranking contra revisión manual del operador.
- T6.2.3 Medir tokens/costo por CV y por lote de 150.
- T6.2.4 Documentar operación diaria, reconexión Outlook y recuperación de errores.

**Criterios de aceptación**
- El flujo Inbox → ranking → XLSX funciona en staging y producción.
- El operador identifica y resuelve CV ignorado, en revisión o con error sin apoyo de desarrollo.
- Modelo, costo estimado y resultado de aceptación quedan registrados.

**Dependencia:** EPIC 5.
