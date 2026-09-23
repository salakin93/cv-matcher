# 019 - Shared document trash

## Estado
`READY_FOR_DEV` — backend only; PRD 010; depends on 007, 012 and 014.

## Objetivo
Retirar y restaurar CVs de la operación compartida sin alterar versiones terminadas.

## Referencias
`docs/prd-010-trash-and-privacy-deletion.md`, `docs/architecture.md` §§4, 6.

## Alcance
### Incluido
- Envío idempotente a papelera, listado paginado y restauración antes de vencimiento por `RECRUITER`/`ADMIN`.
- Exclusión central de documentos en papelera para descarga y futuras operaciones, más auditoría mínima.
### Excluido
- Purga por tiempo (020), privacidad (021), recuperación posterior a purga, edición o eliminación de reportes.

## Comportamiento y reglas
- Tras enviar a papelera, un documento queda `TRASHED` con `trashedAt` y `purgeEligibleAt=trashedAt+180 días`; no se borra archivo todavía.
- La operación es compartida. Restaurar antes de vencimiento devuelve `AVAILABLE`; restaurar purgado/vencido no es posible.
- Reportes terminados conservan sus entradas/scores/evidencias. El documento no se puede descargar y cualquier selector futuro lo excluye.

## Contratos
- `GET /api/v1/documents/trash?page=&size=` muestra sólo metadatos operativos mínimos de documentos en papelera y vencimiento; no bytes, texto/rutas/hashes.
- `POST /api/v1/documents/{documentId}/trash` y `POST /api/v1/documents/{documentId}/restore` devuelven estado y timestamps. Repetir la transición ya conseguida es idempotente.
- `409 DOCUMENT_NOT_TRASHED|DOCUMENT_RESTORE_EXPIRED`, `404 DOCUMENT_NOT_AVAILABLE`; documento usado en reportes se referencia por ID interno, nunca por ruta.

## Configuración centralizada
`document.trash-retention=PT4320H` en `application.yml` + `DocumentProperties`; valor operacional por entorno, no ADMIN.

## Datos y persistencia
- Flyway: agregar a `candidate_document` `lifecycle_state`, `trashed_at`, `purge_eligible_at`, `lifecycle_version`; índice parcial para `TRASHED` por `purge_eligible_at`.
- `document` es dueño de transición y publica `isOperationallyAvailable`; `reporting`, `candidate`, `job` y descarga usan ese puerto, sin consultar estado directamente.
- Actualización optimista/condicional evita trash y restore concurrentes. Evento audit `DOCUMENT_TRASHED|DOCUMENT_RESTORED` en la transacción efectiva.

## Integraciones
Almacenamiento no se toca al enviar/restaurar; 020 será el único consumidor que borra físicamente por retención.

## Errores y estados
- Archivo ya bloqueado por privacidad o purgado responde `404` seguro. Un fallo de auditoría revierte transición.
- Si expira entre lectura y restore, la actualización condicional falla con `409 DOCUMENT_RESTORE_EXPIRED`.

## Seguridad y privacidad
- Requiere `RECRUITER`/`ADMIN`; no aceptar rutas ni hashes. No listar documentos de privacidad/borrados.
- Auditoría contiene actor, UTC, acción y referencia interna, sin contenido/nombre de CV.

## Observabilidad
Contadores de trash/restore/conflictos y gauge de elegibles para purga; logs con documentId/correlationId sin PII.

## Estrategia de pruebas
### Validación manual
- Enviar a papelera, verificar que desaparece de descarga/selección futura pero permanece igual en reporte terminado; restaurar con otra cuenta.
- Repetir acciones, intentar restore vencido y cruzar solicitudes concurrentes.
### Backlog de automatización diferida
- Integración de transiciones/índice/fecha UTC; contratos de exclusión entre módulos; API de roles/idempotencia; regresión de inmutabilidad.

## Criterios de aceptación
- AC-010-01 se cumple; cualquier CV en papelera queda fuera de descarga, reportes, análisis y búsqueda futura sin mutar el historial.

## Riesgos y dependencias
- Depende de ciclo documental 007/008 y debe preceder a 020/021. Los selectores de 018 revalidan este estado.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: sólo el módulo `document` interpreta lifecycle state; los demás módulos consumen su puerto de disponibilidad.

## Definition of Ready
`READY_FOR_DEV`
