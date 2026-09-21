# 021 - Blocking privacy deletion

## Estado
`DRAFT_FOR_APPROVAL` — backend only; PRD 010; depends on 014, 016, 018, 019 and 020.

## Objetivo
Permitir a ADMIN eliminar inmediatamente todos los datos personales asociados a una selección exacta y anonimizar sus apariciones históricas, bloqueando acceso hasta completar.

## Referencias
`docs/prd-010-trash-and-privacy-deletion.md`, `docs/architecture.md` §§5–6.

## Alcance
### Incluido
- Confirmación fuerte y borrado por `candidateProfileId` exacto o `candidateDocumentId` exacto cuando el documento no tiene perfil; bloqueo inmediato, eliminación y anonimización.
- Estado durable de solicitud, recuperación segura ante fallos y auditoría no identificable.
### Excluido
- Borrado parcial, selección por nombre/correo/búsqueda ambigua, restauración, eliminación por recruiter o modificación de scores/estructura histórica.

## Comportamiento y reglas
- Request contiene exactamente uno de `candidateProfileId` o `candidateDocumentId` y `confirmed: true`. No se aceptan criterios personales. Perfil selecciona todos sus documentos; documento anónimo selecciona sólo ese documento.
- En la primera transacción se crea `privacy_deletion` y se bloquean perfil/documentos/entradas relacionadas. La petición HTTP es **bloqueante**: responde éxito sólo después de borrar archivo, texto, datos procesados, perfil/disponibilidad/correcciones y anonimizar cada entrada histórica.
- Si cualquier paso falla, persiste `BLOCKED_FAILED`, conserva bloqueo y retorna error seguro. Reintentos ADMIN retoman la misma solicitud; nunca reabren acceso. Éxito es `COMPLETED` irreversible.
- Anonimización conserva score y estructura, reemplaza nombre por `Candidato eliminado por privacidad` y elimina correo, ubicación, disponibilidad, evidencias y referencia descargable.

## Contratos
- `POST /api/v1/privacy-deletions` recibe `{ "candidateProfileId": "uuid", "confirmed": true }` **o** `{ "candidateDocumentId": "uuid", "confirmed": true }`; responde `200 { deletionId, status: "COMPLETED" }` sólo al final.
- `GET /api/v1/privacy-deletions/{id}` sólo ADMIN, devuelve estado/código seguro/timestamps, sin identidad. `409 PRIVACY_DELETION_IN_PROGRESS|PRIVACY_DELETION_COMPLETED`; `422 INVALID_PRIVACY_DELETION_TARGET|PRIVACY_DELETION_CONFIRMATION_REQUIRED`.

## Configuración centralizada
`privacy-deletion` en `application.yml` + `PrivacyDeletionProperties`: timeout de operación y tamaño de lote de anonimización; no ADMIN. El endpoint no responde asíncronamente.

## Datos y persistencia
- Flyway: `privacy_deletion` (UUID, target_type, target UUID interno, status, started/completed UTC, failure code, actor); flags `privacy_blocked_at/deletion_id` en perfil/documento; campos de anonimización en `report_candidate` con constraint que elimina PII/referencia al marcarse.
- `candidate` resuelve target exacto; `document` borra archivos; `reporting` anonimiza; coordinación por caso de uso `administration` usando puertos, no SQL cruzado. Cada paso es idempotente y transaccionalmente checkpointed.
- El evento `PRIVACY_DELETION_COMPLETED` no contiene target ni PII; evento de fallo conserva sólo código y actor.

## Integraciones
Almacenamiento privado cifrado únicamente. Los jobs 018 y descargas 014 deben consultar el bloqueo antes de acceder/persistir.

## Errores y estados
- `BLOCKING`, `DELETING`, `BLOCKED_FAILED`, `COMPLETED`; toda lectura/descarga/selección de target bloqueado resulta no disponible seguro.
- Timeout HTTP no cancela trabajo ya iniciado: cliente consulta estado, el bloqueo permanece y el servidor continúa hasta checkpoint seguro.

## Seguridad y privacidad
- Sólo `ADMIN`, sesión válida y confirmación explícita; proteger contra IDOR por resolución exacta. No loguear target, PII, rutas, excepciones de filesystem o contenido.
- Auditoría mínima no identificable: actor, UTC, acción y deletionId; el recurso no debe permitir reidentificación.

## Observabilidad
Métricas por estado/duración/fallo, alertas de `BLOCKED_FAILED`; logs con deletionId/correlationId y código seguro.

## Estrategia de pruebas
### Validación manual
- Eliminar perfil con documentos activos/en papelera y documento anónimo; comprobar respuesta sólo al completar, bloqueo inmediato, archivos/datos ausentes, reportes anonimizados y auditoría mínima.
- Forzar fallo de almacenamiento, verificar `BLOCKED_FAILED`, no acceso y reintento idempotente; probar IDs múltiples/falta confirmación/rol recruiter.
### Backlog de automatización diferida
- Integración transaccional de checkpoints y anonimización; dobles de storage con fallo/retry; API ADMIN/IDOR; carreras contra download/job/purge; escaneo de PII residual.

## Criterios de aceptación
- AC-010-03 se cumple. La selección es por perfil exacto o documento anónimo exacto, la operación bloquea y sólo declara éxito tras eliminar todo lo alcanzado.

## Riesgos y dependencias
- Depende directamente de 014 y 018 para que descargas y acceso/procesamiento de documentos por jobs obedezcan el bloqueo de privacidad. Requiere coordinación de todos los consumidores documentales y es prioritario sobre trash/purge/jobs. Backups requieren política operativa independiente.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: privacidad es síncrona bloqueante desde la perspectiva de éxito HTTP, con estado durable para recuperación; `BLOCKED_FAILED` niega acceso hasta conclusión.

## Definition of Ready
`READY_FOR_DEV`
