# 015 - Secure Report Export

## Objetivo

Entregar exportaciones PDF y XLSX protegidas de una versión inmutable de reporte.
Un `RECRUITER` o `ADMIN` solicita un artefacto asíncrono con datos minimizados,
lo descarga autenticadamente desde almacenamiento privado y deja trazabilidad
auditable. No exporta CVs, documentos, rutas, datos sensibles ni secretos.

## Referencias

- `docs/PRD.md`, sección 7.
- `docs/PRODUCT_BACKLOG.md`, Epic 4, Feature 4.3.
- `docs/architecture.md`, secciones 5, 6, 7, 9 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 012, 013 y 014.

## Alcance

### Incluido

- Solicitud, generación durable, consulta y descarga de exportación PDF/XLSX.
- Snapshot inmutable de datos permitidos de una `report_version`.
- Cola/claim/lease para generar sin bloquear HTTP; máximo un export activo por
  versión, formato y solicitante.
- PII permitida: nombre, correo, ubicación y disponibilidad sólo cuando esos
  campos existan y estén autorizados; score y evidencias de reporte.
- Exclusión obligatoria de teléfono, dirección, sender, CV, ruta, hash, token,
  storage key, documento no seleccionado y metadatos internos.
- Archivo privado cifrado, descarga autenticada, auditoría y métricas sin PII.
- OpenAPI, errores seguros y pruebas PDF/XLSX con fixtures sintéticos.

### Excluido

- Editar contenido de reporte, plantilla por usuario, branding, exportaciones
  masivas, envío por correo, enlaces públicos, CDN, UI React y notificaciones.
- Descargar CVs mediante exportación, exportar auditoría, directorio histórico,
  privacidad, papelera, candidatos fuera de la versión o estados no snapshot.
- Recálculo de ranking, análisis, vacantes, perfiles, integración Outlook/Claude.

## Decisiones arquitectónicas

1. `reporting` es dueño de `report_export`. La exportación se construye sólo a
   partir de la versión inmutable, no de perfiles/documentos actuales.
2. El request HTTP persiste una intención `QUEUED` y retorna `202`; worker con
   claim/lease genera bytes fuera de transacción. No se usa memoria como garantía
   y los replays no generan archivos duplicados.
3. El conjunto de columnas es fijo y minimizado. PDF/XLSX contienen nombre,
   correo, ubicación/disponibilidad si existen, scores y evidencias snapshot;
   no teléfono, dirección, sender, CV o IDs internos.
4. Los bytes se cifran AES-GCM en storage privado. La descarga se ancla a la
   exportación y su solicitante/reporte, sin storage key, URL permanente o link
   prefirmado.
5. Cada exportación guarda `template_version` y `report_version_id`; cambiar
   datos actuales nunca altera bytes de una exportación completada.

## Modelo y persistencia

Crear exclusivamente `V16__secure_report_export.sql`; no modificar V1–V15.

### `report_export`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `report_version_id` | FK no nula. |
| `requested_by_user_id` | UUID no nulo. |
| `format` | `PDF` o `XLSX`. |
| `status` | `QUEUED`, `GENERATING`, `COMPLETED`, `FAILED`, `CANCELLED`. |
| `template_version` | No vacía, máximo 80. |
| `storage_key` | Referencia opaca nullable, única si completada. |
| `content_sha256`, `size_bytes` | Hash/tamaño técnicos, nunca públicos. |
| `claimed_by`, `lease_until` | Sólo worker. |
| `failure_code` | Código seguro nullable. |
| `created_at`, `completed_at`, `expires_at`, `updated_at` | UTC. |

Índice único parcial por versión, formato y solicitante cuando estado activo.
`COMPLETED` exige storage key/hash/tamaño; artefactos expiran a los 7 días y se
eliminan del storage mediante worker futuro de conservación, sin tocar reporte.

### `report_export_event`

Evento append-only: export UUID, actor nullable, acción, estado anterior/nuevo,
correlation ID y timestamp. No guarda columnas, PII, bytes, nombre ni ruta.

## Contrato API

JWT válido, cuenta activa, sesión vigente y rol `RECRUITER`/`ADMIN` requeridos.

| Método y ruta | Solicitud | Respuesta |
| --- | --- | --- |
| `POST /api/v1/report-versions/{reportVersionId}/exports` | `{ "format": "PDF" }` | `202 ExportAccepted`. |
| `GET /api/v1/report-versions/{reportVersionId}/exports` | `page?`, `size?` | `200 ExportPage`. |
| `GET /api/v1/exports/{exportId}` | — | `200 ExportDetail`. |
| `GET /api/v1/exports/{exportId}/download` | — | `200` stream binario. |
| `POST /api/v1/exports/{exportId}/cancel` | `{}` | `204`. |

`ExportDetail` expone sólo id, versión, formato, estado, fechas, expiración y
`failureCode` seguro. El download usa attachment, tipo PDF/XLSX oficial,
`X-Content-Type-Options: nosniff` y `Cache-Control: no-store, private`.
Campos desconocidos devuelven `422 VALIDATION_ERROR`.

## Reglas de negocio

1. Sólo una versión existente y terminal (`COMPLETED` o con warnings) admite
   exportación. No hay export vacío de job/reporte pendiente.
2. Solicitudes concurrentes iguales dejan un activo; repetición mientras activo
   devuelve el recurso existente `202`, no crea otra fila. Tras completar, una
   nueva solicitud crea un nuevo artefacto y auditoría.
3. Worker toma snapshot transaccional de columnas permitidas y evidencias
   cifradas; descifra sólo server-side, genera PDF/XLSX y cifra/mueve bytes
   atómicamente. Falla no deja archivo claro, key huérfana o `COMPLETED` parcial.
4. El XLSX escapa valores que empiecen con `=`, `+`, `-` o `@` para evitar formula
   injection. PDF trata todos los textos como contenido y no como HTML/comandos.
5. Cancelar `QUEUED`/`GENERATING` llega a `CANCELLED`; repetir cancelación es
   idempotente sin nuevos eventos. Cancelar terminal no cancelado devuelve
   `409 EXPORT_NOT_CANCELLABLE`.
6. Descarga exige que actor sea solicitante o tenga rol `ADMIN`; `RECRUITER` no
   descarga exportaciones solicitadas por otro reclutador. Caducada/ausente es
   `410 EXPORT_EXPIRED` o `409 EXPORT_UNAVAILABLE` sin filtrar storage.
7. Cada descarga efectiva se audita. Máximo 20 descargas por actor/10 minutos;
   límite devuelve `429 EXPORT_DOWNLOAD_RATE_LIMITED`.

## Errores y seguridad

| Situación | HTTP / código |
| --- | --- |
| JWT inválido/revocado/cuenta no activa | `401 UNAUTHENTICATED` |
| Rol o propietario no autorizado | `403 FORBIDDEN` |
| Reporte/export inexistente | `404 REPORT_VERSION_NOT_FOUND` / `EXPORT_NOT_FOUND` |
| Reporte no terminal/cancelación no permitida | `409 REPORT_NOT_EXPORTABLE` / `EXPORT_NOT_CANCELLABLE` |
| Archivo ausente/integridad inválida | `409 EXPORT_UNAVAILABLE` |
| Expirado | `410 EXPORT_EXPIRED` |
| Límite de descarga | `429 EXPORT_DOWNLOAD_RATE_LIMITED` |
| Formato/JSON/paginación inválidos | `422 VALIDATION_ERROR` |

- Errores JSON seguros no incluyen PII, rutas, bytes, storage key, hash, token,
  template interno ni detalle de librería.
- Logs/auditoría/métricas no incluyen candidato, correo, score, evidencia, UUID o
  formato de archivo como etiqueta identificable.

## Auditoría y observabilidad

Eventos `REPORT_EXPORT_REQUESTED`, `REPORT_EXPORT_COMPLETED`,
`REPORT_EXPORT_FAILED`, `REPORT_EXPORT_CANCELLED` y `REPORT_EXPORT_DOWNLOADED`
se agregan a `audit_event`, con actor, objetivo `REPORT_EXPORT`, timestamp y
correlation ID; sin datos exportados.

Métricas sin PII:

- `reporting.exports` con `action` (`request`, `generate`, `cancel`, `download`)
  y `outcome` cerrado;
- `reporting.export_duration` sin etiquetas identificables;
- `reporting.export_expired` como contador sin IDs.

## OpenAPI y configuración

- Documentar `202`, `200`, `204`, stream, `401`, `403`, `404`, `409`, `410`,
  `422`, `429` y ejemplos sintéticos españoles.
- Configuración server-side: template version, storage/key, expiración, límites,
  page size, download rate limit y máximos de filas. No templates de cliente.
- `test` genera artefactos con datos sintéticos; no usa CV real, correo, Graph,
  Claude, claves reales o archivos públicos.

## Estrategia de pruebas

### Unitarias

- Selección exacta de columnas permitidas y exclusión de PII/documentos internos.
- Formula injection XLSX, escaping PDF, estados, idempotencia y autorización.
- Cifrado/stream/integridad/caducidad y limpieza ante fallo/cancelación.

### Integración Spring/PostgreSQL Testcontainers

- Carrera de solicitudes idénticas, claim/lease/replay y un único activo.
- PDF/XLSX sintéticos contienen sólo datos permitidos y permanecen cifrados fuera
  de DB; cambio de perfil no altera export snapshot.
- Propietario/ADMIN descargan, otro reclutador recibe `403`; expiración, rate
  limit, cancelación y storage fallido son seguros/auditables.
- V16 desde V1–V15, OpenAPI, auditoría/métricas, `./gradlew test`, `git diff --check`.

## Criterios de aceptación

1. Reclutador/admin solicitan PDF/XLSX de reporte terminal y reciben `202` sin
   bloquear generación; un activo idéntico no se duplica.
2. Export usa snapshot inmutable y sólo columnas permitidas; excluye teléfono,
   dirección, sender, CV, hash, ruta, token, IDs internos y documentos no usados.
3. XLSX previene formula injection y PDF no interpreta texto como instrucciones.
4. Worker durable cifra bytes privados y no deja plaintext/key huérfano al fallar.
5. Cancelación es idempotente y no permite descargar artefacto incompleto.
6. Sólo solicitante o ADMIN descarga con headers privados; no existen enlaces
   públicos, redirects, storage keys ni acceso de otro reclutador.
7. Caducidad, integridad y rate limit producen `410`/`409`/`429` seguros sin bytes.
8. Cada request/generación/cancelación/descarga efectiva se audita sin PII.
9. APIs, logs, OpenAPI y métricas no exponen contenido exportado o UUID como tag.
10. V16 y Testcontainers cubren seguridad, concurrencia, snapshot, PDF/XLSX,
    streaming, expiración y errores sin UI, email, directorio o privacidad.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 012 produce reportes inmutables. | Exportar sólo snapshot terminal. |
| Riesgo | Hoja XLSX ejecuta fórmula. | Escape obligatorio de prefijos peligrosos. |
| Riesgo | PII/ruta filtrada en archivo. | Allowlist fija y fixtures de ausencia. |
| Riesgo | Archivo público o retenido indefinidamente. | Storage privado, autenticación y expiración. |
| Dependencia futura | Notificación/email/UI no existen. | API de estado protegida solamente. |

## Definition of Ready

`READY_FOR_DEV`
