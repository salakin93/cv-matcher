# 007 - CV Document Ingestion

## Objetivo

Entregar la ingesta segura y durable de adjuntos descubiertos por la spec 006:
un worker descarga únicamente adjuntos elegibles de Inbox, valida el tipo real
PDF/DOCX, los analiza con antimalware, los cifra en almacenamiento privado y
registra resultados idempotentes. No extrae texto, no crea candidatos y no
calcula rankings.

## Referencias

- `docs/PRD.md`, secciones 4 y 5.
- `docs/PRODUCT_BACKLOG.md`, Epic 3, Feature 3.2.
- `docs/architecture.md`, secciones 6, 7, 8, 10 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 004, 005 y 006.

## Alcance

### Incluido

- Worker durable para jobs `INGESTING_DOCUMENTS` y mensajes descubiertos.
- Consulta Graph de adjuntos sólo para los IDs internos ya descubiertos.
- Clasificación segura de adjuntos candidatos a CV por tipo real PDF/DOCX,
  tamaño, integridad y límites de cantidad/bytes.
- Escaneo antimalware obligatorio por puerto `AntivirusPort` antes de persistir
  un documento disponible.
- Almacenamiento local privado cifrado AES-GCM y metadatos de documento.
- Idempotencia por `graph_message_id` y `graph_attachment_id`, con hash
  criptográfico del contenido para detección técnica de repetición.
- Resultados y advertencias seguras de ingesta, auditoría, métricas, OpenAPI de
  estado de job y pruebas con dobles Graph/antimalware/almacenamiento.

### Excluido

- Extracción de texto PDF/DOCX, OCR, clasificación semántica de CV, nombres,
  correo, teléfono u otros datos de candidato.
- Candidate profiles, deduplicación de personas, disponibilidad, papelera,
  privacidad, descarga por usuarios y enlaces de archivo.
- Claude, análisis, ranking, versiones de reporte, exportaciones, decisiones
  humanas, notificaciones y UI React.
- Lectura de cuerpo/asunto/remitente de correo y archivos distintos de PDF/DOCX.
- Almacenamiento público, blobs de CV en PostgreSQL, rutas de archivo expuestas
  o claves de cifrado administrables por API/UI.

## Decisiones arquitectónicas

1. `document` es dueño de metadatos, almacenamiento privado y validación. Sólo
   consume un puerto público de `job` para reclamar mensajes; no accede a tablas
   internas de `outlook` ni descifra tokens.
2. Cada descarga Graph se ejecuta fuera de transacciones. La persistencia de
   estado, archivo temporal y metadatos ocurre en transacciones breves y es
   recuperable ante reinicio.
3. Un documento sólo llega a estado `AVAILABLE` después de validar tamaño, tipo
   real y escaneo antivirus exitoso. MIME declarado, extensión y nombre no son
   prueba suficiente.
4. Los originales se almacenan fuera del web root, cifrados AES-GCM con clave
   de entorno `CV_DOCUMENT_ENCRYPTION_KEY`. PostgreSQL guarda sólo referencia
   opaca, hash, tamaño y metadatos técnicos mínimos.
5. Archivos rechazados no se conservan. Sólo se persiste una razón segura y
   mínima de ignorado; nunca nombre de archivo, contenido, URL Graph o payload
   de antimalware en logs, API, auditoría o métricas.

## Modelo y persistencia

Crear exclusivamente `V8__cv_document_ingestion.sql`; no modificar V1–V7.

### `candidate_document`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK generado por servidor. |
| `graph_message_id_hash` | SHA-256 del ID interno de mensaje; nunca el ID en claro. |
| `graph_attachment_id_hash` | SHA-256 del ID de adjunto; único con mensaje. |
| `content_sha256` | Hash SHA-256 del archivo original, no público. |
| `storage_key` | Referencia opaca al archivo cifrado, única y no expuesta. |
| `format` | `PDF` o `DOCX`. |
| `size_bytes` | Entero positivo limitado. |
| `encryption_key_version` | Entero no nulo para rotación futura. |
| `status` | `AVAILABLE`, `IGNORED` o `QUARANTINED`. |
| `ignored_reason_code` | Código seguro nullable. |
| `received_at`, `created_at`, `updated_at` | `timestamptz` UTC. |

Constraint: `AVAILABLE` exige `storage_key`, `content_sha256`, formato y tamaño;
`IGNORED` no puede contener `storage_key`. Índice único por hashes de mensaje y
adjunto; índice por `content_sha256` sólo para deduplicación técnica posterior.

### `matching_job_document`

Relación UUID entre job y documento, con `position` estable, `disposition`
(`ACCEPTED`, `IGNORED`, `QUARANTINED`) y `reason_code` seguro. Un adjunto se
vincula una sola vez por job. No almacenar nombre, sender, subject, ruta local
ni contenido textual.

Agregar a `matching_job` los conteos derivados `accepted_document_count`,
`ignored_document_count`, `quarantined_document_count` y `ingestion_completed_at`.
Crear eventos `DOCUMENT_INGESTION_STARTED`, `DOCUMENT_ACCEPTED`,
`DOCUMENT_IGNORED`, `DOCUMENT_QUARANTINED`, `DOCUMENT_INGESTION_COMPLETED` y
`DOCUMENT_INGESTION_FAILED` en `matching_job_event`.

## Reglas de negocio

1. Sólo el worker puede reclamar `INGESTING_DOCUMENTS`; usa claim/lease de spec
   004 y valida cancelación antes de cada descarga y cada persistencia.
2. Para cada mensaje descubierto con `has_attachments=true`, solicita por Graph
   sólo metadatos/bytes de adjuntos. Nunca lee cuerpo, asunto o remitente.
3. Son candidatos sólo adjuntos de hasta `maxDocumentBytes` y formato real PDF o
   DOCX. Rechazar con código seguro: `UNSUPPORTED_FORMAT`, `FILE_TOO_LARGE`,
   `CORRUPT_DOCUMENT`, `PASSWORD_PROTECTED` o `EMPTY_DOCUMENT` cuando aplique.
4. Máximos iniciales: 20 adjuntos por mensaje, 200 documentos por job y 250 MiB
   acumulados por job. Al superar un límite, registrar warning seguro y seguir
   con elementos ya validados, sin exceder bytes configurados.
5. El worker descarga a una ubicación temporal privada, calcula hash y ejecuta
   antimalware. Sólo un resultado limpio cifra, mueve atómicamente al storage y
   crea `AVAILABLE`. Resultado infectado crea `QUARANTINED` sin storage key.
6. La criptografía usa nonce aleatorio AES-GCM por archivo y autenticación de
   tag. Si cifrado/movimiento/persistencia falla, borrar temporal y no dejar
   documento disponible ni referencia huérfana.
7. Replays, lease vencido o reintentos no vuelven a descargar ni duplican un
   adjunto ya resuelto para el job. Una respuesta parcial se retoma desde los
   vínculos persistidos.
8. Tras procesar todos los adjuntos elegibles, job pasa a `ANALYZING` sólo como
   estado de espera para la futura extracción/análisis. Si no hay documento
   `AVAILABLE`, queda `FAILED` con `NO_VALID_CV_DOCUMENTS`; no se crea candidato.
9. `429` y errores transitorios respetan `Retry-After` y tienen máximo tres
   intentos. Revocación OAuth lleva a `REAUTHORIZATION_REQUIRED`; falla de
   antivirus o storage no segura lleva a `FAILED` sin reintento automático.

## Errores y seguridad

El cliente no recibe endpoints de archivos. Los endpoints de job conservan JSON
seguro y pueden exponer sólo conteos y códigos agregados de advertencia.

| Situación worker | Estado/código seguro |
| --- | --- |
| Tipo no admitido | `IGNORED` / `UNSUPPORTED_FORMAT` |
| Tamaño/límite excedido | `IGNORED` / `FILE_TOO_LARGE` o `JOB_DOCUMENT_LIMIT_REACHED` |
| Documento corrupto/protegido/sin contenido | `IGNORED` / código correspondiente |
| Antimalware detecta amenaza | `QUARANTINED` / `MALWARE_DETECTED` |
| Token revocado | `REAUTHORIZATION_REQUIRED` / `OUTLOOK_REAUTH_REQUIRED` |
| Falla de storage o antivirus | `FAILED` / `DOCUMENT_INGESTION_UNAVAILABLE` |

- No registrar bytes, hash, nombre, MIME declarado, ruta, URL Graph, archivo
  temporal, token, secreto, resultado AV detallado ni contenido de CV.
- Storage root se valida al arranque: directorio privado, no web-accessible y
  fuera de rutas estáticas. No aceptar rutas de cliente.
- La descarga futura por reclutador requiere una spec separada y autorización
  autenticada; `storage_key` no es un enlace ni identificador público.

## Auditoría y observabilidad

Los eventos técnicos por archivo viven en `matching_job_event`; auditoría
`DOCUMENT_INGESTION_COMPLETED` o `DOCUMENT_INGESTION_FAILED` se agrega una vez
por resultado efectivo del job, sin metadatos de CV. Métricas sin PII:

- `documents.ingestion` con `outcome` (`accepted`, `ignored`, `quarantined`,
  `failed`);
- `documents.ignored` con `reason` del conjunto cerrado de códigos seguros;
- `documents.antivirus` con `outcome` (`clean`, `detected`, `unavailable`);
- `documents.storage` con `outcome` (`stored`, `failed`).

No usar UUID, hash, formato de nombre, tamaño exacto, job o vacante como etiqueta.

## OpenAPI y configuración

- Documentar en `JobDetail` conteos de documentos y códigos agregados seguros;
  no añadir contratos para archivo, ID Graph, hash ni storage key.
- Propiedades server-side: `CV_STORAGE_ROOT`, clave AES-GCM, versión de clave,
  límites de bytes/documentos, timeout Graph, reintentos y configuración AV.
- `prod` falla rápido si storage privado, clave o antivirus no están disponibles.
  `test` usa directorio temporal y dobles; nunca CVs reales, claves reales ni
  Graph/antimalware externos.

## Estrategia de pruebas

### Unitarias

- Detección real de PDF/DOCX frente a extensión/MIME engañosos.
- Límites, clasificación segura, idempotencia y máquina de estados.
- AES-GCM, nonce, tag, limpieza de temporales y ausencia de plaintext.
- Validación de raíz privada y redacción de errores de Graph/AV/storage.

### Integración Spring/PostgreSQL Testcontainers

- Worker claim/lease, cancelación y replay sin duplicar documento, vínculo o
  conteos.
- Dobles Graph entregan PDF, DOCX, tipos inválidos, corruptos y protegidos; sólo
  válidos/limpios crean `AVAILABLE` cifrado fuera de PostgreSQL.
- Doble AV detecta/cae; storage falla/move falla sin registros disponibles u
  archivos temporales huérfanos.
- Límites, `429`, token revocado, cero válidos y transición de job correcta.
- V8 desde V1–V7, detalle seguro, OpenAPI, auditoría/métricas sin PII,
  `./gradlew test` y `git diff --check`.

## Criterios de aceptación

1. Sólo un worker reclama `INGESTING_DOCUMENTS`; lease/replay/cancelación no
   duplican ni continúan después de cancelar.
2. Sólo se consultan adjuntos de mensajes previamente descubiertos; no se lee
   cuerpo, asunto, remitente o destinatarios.
3. PDF/DOCX se valida por contenido real, límites y corrupción; extensión/MIME
   no determinan aceptación y los ignorados tienen motivo seguro.
4. Un documento sólo está `AVAILABLE` tras antimalware limpio, cifrado AES-GCM y
   almacenamiento privado; no hay blobs de CV en PostgreSQL ni enlaces públicos.
5. Adjuntos infectados se cuarentenan sin archivo disponible; fallas de cifrado,
   storage o AV no dejan datos disponibles o temporales huérfanos.
6. Reintentos, lease vencido y respuestas Graph repetidas son idempotentes por
   mensaje/adjunto y conservan conteos correctos.
7. Límites de adjuntos, documentos y bytes son aplicados sin procesar fuera de
   los máximos configurados.
8. Sin documentos válidos el job falla seguro; con procesamiento completo pasa
   a `ANALYZING` sin extraer texto, crear candidatos ni llamar Claude.
9. Token revocado, `429`, errores transitorios y errores de infraestructura
   generan estados/códigos seguros y no exponen datos de terceros.
10. OpenAPI, auditoría, logs y métricas no exponen CV, nombre, ruta, hash, token,
    secreto, URL Graph ni UUID como etiqueta de métrica.
11. V8 y pruebas Testcontainers con dobles validan seguridad, cifrado,
    idempotencia, límites y errores sin habilitar candidatos, ranking, descarga,
    notificaciones, exportaciones o UI.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Specs 004–006 proveen job, token y mensajes descubiertos. | Usar puertos públicos. |
| Riesgo | Archivo malicioso o tipo engañoso. | Magic bytes/parser seguro y AV obligatorio. |
| Riesgo | CV expuesto por ruta o log. | Storage privado cifrado y redacción probada. |
| Riesgo | Retry duplica archivo. | Constraints por adjunto y move atómico. |
| Dependencia futura | Falta extracción y candidatos. | `ANALYZING` espera spec de texto/análisis. |

## Definition of Ready

`READY_FOR_DEV`
