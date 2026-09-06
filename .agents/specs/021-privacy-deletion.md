# 021 - Candidate Privacy Deletion

## Objetivo

Permitir que sólo `ADMIN` ejecute eliminación inmediata por privacidad: borrar
PII, CVs y datos procesados de un perfil, anonimizar entradas históricas y
preservar únicamente auditoría mínima no identificable.

## Alcance

- Endpoint administrativo con confirmación explícita y motivo de categoría
  cerrada; workflow durable de eliminación y resultado consultable seguro.
- Borrado de storage, ciphertext, texto, HMAC, índices, perfil y enlaces
  procesados; anonimización de `report_candidate` como
  `Candidato eliminado por privacidad`.
- Exclusión permanente de búsqueda, descargas, exportación y restauración.

## Excluido

- Eliminación de auditoría, recuperación posterior, UI, notificación, borrado de
  otros perfiles, cambios de score/ranking histórico o exportación retrospectiva.

## Persistencia y contrato

Crear sólo `V22__candidate_privacy_deletion.sql`; tabla durable
`privacy_deletion_request` con UUID, profile, actor, estado, categoría de motivo,
claim/lease, timestamps y código seguro; nunca PII/motivo libre. `POST
/api/v1/admin/candidates/{candidateId}/privacy-deletions` con `{ "confirm":true,
"reason":"DATA_SUBJECT_REQUEST" }` devuelve `202`; GET de estado requiere ADMIN.

## Reglas

1. ADMIN activo/sesión vigente únicamente; confirmación faltante es `422`.
2. Un perfil tiene una eliminación activa; concurrencia retorna recurso existente,
   no duplica job.
3. Worker borra primero datos personales/documentos/derivados, luego anonimiza
   snapshots históricos y marca `COMPLETED`; fallo deja reintento seguro.
4. Auditoría conserva sólo actor, acción, UUID técnico no recuperable y tiempo;
   reportes nunca vuelven a mostrar nombre/email/CV del perfil eliminado.

## Criterios de aceptación

1. Sólo ADMIN inicia/consulta eliminación; `401`/`403` seguros.
2. PII, CV, texto, HMAC, índices y acceso futuro se eliminan inmediatamente.
3. Entradas históricas quedan anonimizada sin alterar score/ranking de otros.
4. Reintentos/concurrencia son idempotentes y no recuperan datos.
5. V22/Testcontainers prueban borrado, anonimización, fallos, auditoría mínima y
   ausencia de PII sin UI, restauración o exportación.

## Definition of Ready

`READY_FOR_DEV`
