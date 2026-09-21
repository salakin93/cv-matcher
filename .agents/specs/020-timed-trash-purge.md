# 020 - Timed trash purge

## Estado
`DRAFT_FOR_APPROVAL` — backend only; PRD 010; depends on 019.

## Objetivo
Purgar de forma durable los CVs que llevan 180 días en papelera y limpiar datos de perfil sin CV disponible.

## Referencias
`docs/prd-010-trash-and-privacy-deletion.md`, `docs/architecture.md` §§6, 7, 11.

## Alcance
### Incluido
- Worker programado con claim/lease para documentos `TRASHED` vencidos; borrado de original, texto extraído y datos procesados del documento.
- Limpieza de datos extraídos de perfil que queda sin ningún documento disponible y auditoría de purga efectiva.
### Excluido
- Restauración, privacidad, borrado de reportes/score, cambio de retención o recuperación de datos purgados.

## Comportamiento y reglas
- Es elegible cuando `purge_eligible_at <= now UTC`. Antes del borrado físico, el worker cambia el documento a `PURGING`, bloqueando descarga, restore y selección.
- Tras eliminar de forma verificable almacenamiento y datos procesados, marca `PURGED` y elimina referencias protegidas. Si es el último documento disponible del perfil, elimina atributos extraídos/corregidos del perfil, sin tocar entradas históricas.
- Es reintentable e idempotente: reinicios retoman `PURGING`; nunca declaran éxito mientras siga existiendo dato personal procesado o archivo.

## Contratos
No añade endpoint de usuario. `GET /documents/trash` de 019 no muestra `PURGED`; restore retorna `409 DOCUMENT_RESTORE_EXPIRED`.

## Configuración centralizada
`document.purge` en `application.yml` + `DocumentProperties`: intervalo de scheduling, tamaño de lote y lease. Retención sigue siendo la propiedad de 019; ningún valor es ADMIN.

## Datos y persistencia
- Flyway: añadir `purge_started_at`, `purged_at`, `purge_attempts`, `purge_lease_until`, `purge_failure_code` seguro e índice de reclamación a `candidate_document` o tabla de work-item propiedad de `document`.
- Operaciones DB son breves; borrado de filesystem/cifrado ocurre fuera de transacción y se confirma en transacción posterior. No borrar físicamente antes de bloquear estado.
- `candidate` expone un caso de uso para limpiar datos extraídos cuando no hay documentos disponibles; no elimina perfil si conserva identidad requerida por reportes.

## Integraciones
Usa sólo el adaptador de almacenamiento cifrado. No llama Graph, Claude, SMTP ni APIs externas.

## Errores y estados
- Fallo de archivo/DB deja `PURGING` bloqueado, código seguro y alertable; el worker reintenta con política acotada. No volver a `TRASHED` ni permitir restore.
- Una carrera con restore se resuelve por actualización condicional: la primera transición confirmada prevalece.

## Seguridad y privacidad
- No hay API pública. Logs/auditoría no incluyen nombre, ruta, hash, contenido ni datos de perfil.
- Evento `DOCUMENT_PURGED` sólo al éxito, con actor de sistema, UTC y referencia mínima.

## Observabilidad
Métricas de candidatos, duración, errores, reintentos y backlog vencido; alerta para `PURGING` atascado o fallo de almacenamiento.

## Estrategia de pruebas
### Validación manual
- En entorno de prueba con reloj controlable, vencer documento, ejecutar worker y comprobar archivo/texto/metadata eliminados, sin restore ni cambio de reporte.
- Simular fallo de almacenamiento, confirmar bloqueo y reintento seguro; verificar limpieza de último perfil.
### Backlog de automatización diferida
- Testcontainers de claim/lease/carreras; doble de filesystem para idempotencia; migración/índice; regresión de preservación de reportes y observabilidad.

## Criterios de aceptación
- AC-010-02 se cumple: a 180 días se elimina permanentemente archivo y datos procesados, no existe restauración y reportes conservan su estructura.

## Riesgos y dependencias
- Depende del estado y fecha de 019 y volumen operativo de almacenamiento. La política de backup/retención sigue siendo riesgo de producción de arquitectura.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: `PURGING` es estado de bloqueo duradero para garantizar que un fallo de borrado nunca restaure exposición operacional.

## Definition of Ready
`READY_FOR_DEV`
