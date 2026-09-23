# 014 - Protected CV download

## Estado
`READY_FOR_DEV` — backend only; PRD 007; depends on 007, 012 and 013.

## Objetivo
Entregar únicamente el archivo original que una entrada de reporte usó, mediante una autorización autenticada y limitada.

## Referencias
`docs/prd-007-protected-cv-download.md`, `docs/architecture.md` §§5–6, 9.

## Alcance
### Incluido
- Descarga autenticada desde la relación exacta reporte-versión/entrada y límite de 20 descargas efectivas por usuario en ventana móvil de 10 minutos.
- Verificación de ciclo de vida documental, streaming seguro y auditoría al completar.
### Excluido
- URLs firmadas o públicas, descarga por perfil/documento, previsualización, reemplazo, exportación o cambios al reporte.

## Comportamiento y reglas
- Sólo se entrega el `candidate_document` fijado por la entrada de esa versión; nunca el CV más reciente del perfil.
- Antes de abrir bytes se comprueba que no esté en papelera, purgado, bloqueado por privacidad, corrupto o no disponible. La cuota se consume sólo tras completar el stream sin error de salida.
- La respuesta conserva el MIME validado PDF/DOCX y usa un nombre de descarga genérico (`cv.pdf`/`cv.docx`), no el nombre original.

## Contratos
- `GET /api/v1/report-versions/{reportVersionId}/candidates/{reportCandidateId}/document` responde `200` como `application/pdf` o MIME DOCX con `Content-Disposition: attachment`; sin JSON envolvente.
- `429 DOWNLOAD_RATE_LIMITED` incluye `Retry-After` en segundos y mensaje seguro. `404 DOCUMENT_NOT_AVAILABLE` cubre ausencia, papelera, purga, bloqueo o fallo de integridad para no distinguir estados sensibles.
- `401`, `403` y `404` usan el contrato uniforme cuando no se puede autorizar el recurso.

## Configuración centralizada
Añadir `document.download-rate-limit` a `application.yml` y `DocumentProperties`: fijo por defecto `20` eventos completados / `PT10M`; perfiles pueden modificar los valores operativos, no ADMIN.

## Datos y persistencia
- Flyway: tabla `document_download_attempt` con UUID, `user_id`, UTC, resultado `RESERVED|COMPLETED`, `reservation_expires_at` y referencias internas de reporte/entrada/documento; índice `(user_id, created_at)` para cuota. No persistir IP, nombre, ruta ni bytes.
- El módulo `reporting` resuelve la entrada y llama al puerto `document.openOriginalForAuthorizedDownload(documentId)`; `document` descifra y verifica fuera de controladores. Ningún módulo consulta tablas ajenas directamente.
- Antes de abrir bytes, reservar transaccionalmente un cupo; la admisión considera `COMPLETED` y reservas vigentes para no superar el límite concurrentemente. Sólo `COMPLETED` consume cuota; tras stream completo convertir reserva y registrar `CV_DOWNLOADED`, y ante fallo liberar la reserva.

## Integraciones
Almacenamiento privado cifrado a través del módulo `document`; no hay enlaces ni proveedor de navegador.

## Errores y estados
- Integridad, descifrado o archivo ausente: no enviar bytes, registrar fallo técnico seguro y devolver `404 DOCUMENT_NOT_AVAILABLE`.
- El límite se calcula transaccionalmente para solicitudes concurrentes; la vigésima permitida y posteriores dentro de la ventana no pueden exceder el máximo.

## Seguridad y privacidad
- Requiere bearer válido y `RECRUITER` o `ADMIN`; validar versión y entrada antes de resolver documento para evitar IDOR.
- Deshabilitar cache compartida (`Cache-Control: no-store, private`), no redirigir y no exponer ruta, hash, nombre original o claves.
- Auditoría mínima sólo para descarga efectiva: actor, UTC, acción y referencias internas.

## Observabilidad
Contadores de completadas, bloqueadas por cuota y no disponibles; logs con correlationId, IDs internos y código, sin encabezados sensibles ni PII.

## Estrategia de pruebas
### Validación manual
- Descargar PDF y DOCX de una entrada válida; comprobar MIME, nombre genérico, auditoría y que no cambia el reporte.
- Intentar con sesión ausente, rol no permitido, UUID cruzados, documento en papelera y 21 solicitudes en 10 minutos.
### Backlog de automatización diferida
- Integración de stream cifrado e integridad; API de autorización/IDOR/cache; concurrencia de rate limit; auditoría sólo tras stream completado.

## Criterios de aceptación
- AC-007-01 a AC-007-06 se cumplen; sólo se sirve el documento usado por la entrada y nunca existe URL reutilizable.

## Riesgos y dependencias
- Depende de los estados de documento de 007/008, snapshot de 012 y auditoría. El comportamiento tras papelera depende de 019.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: el contador de cuota registra sólo streams completados, pero su reserva transaccional evita superar el límite concurrentemente.

## Definition of Ready
`READY_FOR_DEV`
