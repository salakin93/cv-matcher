# 014 - Protected CV Download

## Objetivo

Entregar descarga autenticada, autorizada y auditada del CV original
seleccionado para una entrada de reporte. El backend descifra y transmite el
archivo desde almacenamiento privado sin enlaces públicos, rutas, storage keys
ni PII adicional. No exporta reportes, no altera documentos y no habilita acceso
al directorio fuera de un reporte.

## Referencias

- `docs/PRD.md`, secciones 5 y 7.
- `docs/PRODUCT_BACKLOG.md`, Epic 4, Feature 4.3.
- `docs/architecture.md`, secciones 5, 6, 9 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 007, 012 y 013.

## Alcance

### Incluido

- Endpoint de descarga del documento seleccionado de un candidato de reporte.
- Autorización de `RECRUITER`/`ADMIN` con sesión persistida vigente.
- Descifrado AES-GCM streaming desde storage privado, con verificación de
  integridad y headers seguros de descarga.
- Auditoría obligatoria de cada descarga efectiva y métricas sin PII.
- Límite server-side de descargas, prevención de path traversal y manejo de
  documento ausente/cuarentenado/eliminado de forma segura.
- OpenAPI, errores JSON seguros y pruebas de streaming con storage temporal.

### Excluido

- Enlaces permanentes o prefirmados, acceso público, proxy de archivos externo,
  CDN público, subida, edición o reemplazo de CV.
- Descarga de documentos no seleccionados, acceso por profile/document UUID,
  directorio histórico, papelera, privacidad y exportación PDF/XLSX.
- UI React, envío por correo, notificaciones, análisis, ranking o cambios de
  estado humano.

## Decisiones arquitectónicas

1. La ruta se ancla a `report_version` y `report_candidate`; no hay endpoint
   general por `candidate_document_id` ni `storage_key`. Así la autorización se
   limita a un resultado de reporte accesible al actor.
2. `document` es dueño del cifrado/storage y expone un puerto de streaming
   autorizado. `reporting` verifica pertenencia versión-candidato y solicita el
   stream; nunca construye rutas ni descifra bytes.
3. El archivo se descifra en streaming a memoria/buffer acotado. Nunca se crea
   archivo temporal claro, nunca se carga el CV entero sin límite y se cierra el
   stream ante desconexión del cliente.
4. La descarga no cambia score, ranking, perfil, estado humano ni documento.
   Se registra como acceso sensible independiente de eventos de reporte.
5. El nombre de archivo público se genera de modo seguro y no revela ruta ni
   datos no autorizados: `cv-{reportCandidateId}.{pdf|docx}`.

## Modelo y persistencia

Crear exclusivamente `V15__protected_cv_download.sql`; no modificar V1–V14.

### `document_access_event`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `candidate_document_id` | UUID de documento, no expuesto por API. |
| `report_version_id`, `report_candidate_id` | UUIDs de contexto de autorización. |
| `actor_user_id` | UUID no nulo del solicitante. |
| `action` | Sólo `CV_DOWNLOADED`. |
| `correlation_id` | UUID nullable. |
| `created_at` | `timestamptz` UTC. |

No guardar IP, user agent, nombre, correo, hash, tamaño, ruta, storage key ni
resultado de descifrado. Índice por `report_candidate_id, created_at desc` sólo
para futura auditoría administrativa, no expuesta en esta spec.

## Contrato API

| Método y ruta | Respuesta |
| --- | --- |
| `GET /api/v1/report-versions/{reportVersionId}/candidates/{reportCandidateId}/document` | `200` stream PDF/DOCX con `Content-Disposition: attachment`. |

Requiere bearer JWT válido, cuenta `ACTIVE`, sesión persistida y rol efectivo
`RECRUITER` o `ADMIN`. Respuesta exitosa fija `Content-Type` a
`application/pdf` o al tipo DOCX oficial, `X-Content-Type-Options: nosniff`,
`Cache-Control: no-store, private` y no incluye URL/ruta/tokens. No soporta
range requests, query params, redirecciones ni cookies de autenticación como
único factor.

## Reglas de negocio

1. El candidato debe pertenecer exactamente a la versión indicada y su documento
   seleccionado debe estar `AVAILABLE`. Relación inexistente o no accesible es
   `404 REPORT_CANDIDATE_NOT_FOUND` sin revelar documento.
2. Sólo se descarga el original seleccionado por `job_candidate_selection` y
   snapshot de `report_candidate`. Documentos alternativos del perfil no son
   alcanzables por esta API.
3. Antes de emitir headers, `document` valida formato permitido, storage key
   opaca dentro de raíz privada, ciphertext AES-GCM, tag y hash. Cualquier fallo
   cierra stream y responde `409 DOCUMENT_UNAVAILABLE` si no se entregó byte.
4. Si ya comenzó la respuesta y falla el stream, abortar conexión, registrar
   error técnico sin PII y no intentar escribir JSON dentro de contenido binario.
5. Tras validar stream y antes de emitir headers/cuerpo, insertar `CV_DOWNLOADED`
   de forma transaccional breve. Si la auditoría falla no se transmite el archivo.
   Reintentos HTTP del usuario generan eventos separados porque representan
   solicitudes de acceso distintas.
6. Aplicar límite fijo configurable de 20 descargas por actor por ventana de 10
   minutos. Exceder devuelve `429 DOWNLOAD_RATE_LIMITED` sin identificar otros
   accesos; límite se implementa server-side y no es configurable por cliente.
7. Documento `IGNORED`, `QUARANTINED`, eliminado, sin storage o con clave de
   cifrado inválida nunca se transmite, aunque existan reportes históricos.

## Errores y seguridad

| Situación | HTTP / código |
| --- | --- |
| JWT inválido, revocado o cuenta no activa | `401 UNAUTHENTICATED` |
| Rol no autorizado | `403 FORBIDDEN` |
| Reporte/candidato/relación inexistente | `404 REPORT_CANDIDATE_NOT_FOUND` |
| Documento no disponible o integridad inválida | `409 DOCUMENT_UNAVAILABLE` |
| Límite de descarga excedido | `429 DOWNLOAD_RATE_LIMITED` |

- Los errores previos a stream usan JSON común con correlation ID. Nunca incluyen
  ruta, storage key, nombre original, hash, cifrado, PII, token ni stacktrace.
- No interpolar IDs en rutas filesystem; validar UUID y usar referencias opacas
  resueltas por `document` contra una raíz canónica privada.
- Logs no incluyen nombre, correo, URL, tamaño, bytes, hash, documento o actor.
  Métricas no contienen UUID, candidato, reporte, usuario o formato como etiqueta.

## Auditoría y observabilidad

Cada descarga efectiva crea `CV_DOWNLOADED` en `audit_event`, además de
`document_access_event`, con actor, objetivo `CANDIDATE_DOCUMENT`, timestamp y
correlation ID. No registrar PII o referencia de storage.

Métricas sin PII:

- `documents.downloads` con `outcome` (`success`, `unavailable`, `rate_limited`);
- `documents.download_duration` sin etiquetas identificables;
- `documents.download_stream_failures` sin nombre/ruta/UUID como etiqueta.

## OpenAPI y configuración

- Documentar stream binario, bearer, `200`, `401`, `403`, `404`, `409`, `429`,
  headers de seguridad y que no existen enlaces públicos.
- Propiedades server-side: límite/ventana, tamaño máximo de stream, storage root,
  claves/versiones de cifrado y timeouts. Fallar rápido si storage/clave faltan.
- `test` usa archivos sintéticos cifrados y storage temporal privado; no CV real,
  URL externa, key real ni integración Graph.

## Estrategia de pruebas

### Unitarias

- Autorización de relación reporte-candidato y rechazo de documento alternativo.
- Resolución de storage opaco, path traversal, tag/hash inválido y headers seguros.
- Stream acotado, abort por error, nombre público generado y rate limiter.

### Integración Spring/PostgreSQL Testcontainers

- `RECRUITER`/`ADMIN` con sesión vigente descargan bytes sintéticos correctos;
  no bearer `401`, no autorizado `403`, relación incorrecta `404`.
- Respuesta no contiene ruta/ID interno; headers evitan cache/sniff y no redirige.
- Documento cuarentenado/ausente/integridad inválida no transmite bytes y retorna
  `409` seguro; reintentos exitosos generan auditoría por acceso efectivo.
- Límite `429`, concurrencia de descargas y falla de stream no filtran PII ni
  dejan recursos abiertos; V15 desde V1–V14, OpenAPI y regresión completa.

## Criterios de aceptación

1. Sólo `RECRUITER`/`ADMIN` activos con sesión vigente descargan el CV seleccionado;
   sin bearer es `401` y rol no autorizado `403` seguro.
2. Ruta exige relación exacta versión-candidato; nunca permite descargar por
   documento/perfil/storage key ni acceder a CV alternativo.
3. Archivo se transmite desde storage privado cifrado, validando tag/hash/tipo,
   sin archivo temporal claro, enlace público, redirect o ruta expuesta.
4. Headers fuerzan attachment, tipo seguro, `nosniff` y `no-store, private`.
5. Documento no disponible/cuarentenado/integridad inválida no transmite bytes y
   devuelve `409 DOCUMENT_UNAVAILABLE` sin detalles sensibles.
6. Cada acceso efectivo se audita sin PII; reintentos son eventos distintos y no
   cambian reporte, score, perfil, documento o estado humano.
7. Límite server-side devuelve `429` sin filtrar otros accesos ni aceptar control
   de cliente.
8. Logs, OpenAPI, métricas y errores no exponen nombre original, correo, ruta,
   hash, storage key, token, CV ni UUID como etiqueta.
9. V15 y Testcontainers cubren autorización, streaming, cifrado, integridad,
   rate limit, auditoría y errores sin exportación, UI o directorio.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 007 aporta documento privado cifrado. | Usar `document` streaming port. |
| Dependencia | Spec 012 aporta selección/reporte. | Anclar autorización a entrada de reporte. |
| Riesgo | CV se filtra por path o cache. | Storage opaco, headers y no-store. |
| Riesgo | Descargas automatizadas. | Rate limit y auditoría por acceso. |
| Dependencia futura | Exportación/directorio siguen fuera. | No crear rutas o enlaces adyacentes. |

## Definition of Ready

`READY_FOR_DEV`
