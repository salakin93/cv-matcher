# 020 - Trash Retention Purge

## Objetivo

Eliminar físicamente y de forma segura los CVs que permanecen `TRASHED` durante
180 días, sin modificar reportes históricos ni borrar auditoría mínima.

## Alcance

- Worker durable diario con claim/lease para documentos cuyo `purge_eligible_at`
  venció, storage privado y metadatos derivados.
- Borrado de ciphertext/archivo, texto extraído, índices de términos y datos
  procesados; estado terminal `PURGED` con evidencia técnica mínima.
- Idempotencia, retries, alerta/métrica segura, auditoría y Testcontainers.

## Excluido

- Eliminación por solicitud de privacidad, restauración tras vencimiento, cambios
  de reportes históricos, UI, exportación, descarga y notificaciones de usuario.

## Persistencia

Crear sólo `V21__trash_retention_purge.sql`. Añadir `PURGED`, `purged_at`,
`purge_attempts`, `last_purge_error_code` a lifecycle; tabla append-only
`document_purge_event` sin PII, rutas, hashes o storage keys. Un documento
`PURGED` no tiene ciphertext, texto, índice ni storage key accesible.

## Reglas

1. Worker reclama sólo `TRASHED` con vencimiento UTC alcanzado; nunca purga
   `AVAILABLE`, `QUARANTINED` o documento restaurado concurrentemente.
2. Borra storage privado y derivados antes de marcar `PURGED`; si falla, conserva
   `TRASHED` y reintenta hasta tres veces con backoff, sin estado parcial.
3. Replays/lease vencido son idempotentes: archivo ausente se considera borrado;
   evento/auditoría terminal se emite una vez.
4. Reportes históricos mantienen snapshot; descarga/búsqueda/restauración de
   `PURGED` devuelven ausencia segura.

## Seguridad y observabilidad

Auditar `CANDIDATE_DOCUMENT_PURGED` con objetivo técnico, actor `null` y
correlation ID. Métricas `documents.purge` por outcome cerrado, sin UUID/PII.
Logs/errores no incluyen rutas, nombres, hashes, CVs o claves.

## Criterios de aceptación

1. Sólo documentos trash vencidos se purgan; restaurados/concurrentes no.
2. Purga borra original, texto e índices privados y deja estado terminal mínimo.
3. Recovery/retry no duplica borrado, auditoría o eventos.
4. Reportes históricos no cambian; todo uso futuro del documento es bloqueado.
5. V21/Testcontainers prueban retención UTC, concurrencia, fallos storage,
   idempotencia y ausencia de PII sin privacidad, UI o exportación.

## Definition of Ready

`READY_FOR_DEV`
