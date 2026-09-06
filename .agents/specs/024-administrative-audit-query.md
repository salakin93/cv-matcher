# 024 - Administrative Audit Query

## Objetivo

Permitir consulta paginada, segura e inmutable de auditoría para `ADMIN`, con
filtros técnicos y minimización de datos, sin editar/exportar eventos.

## Alcance

- API ADMIN para leer `audit_event` por período, acción, tipo objetivo y actor
  técnico, con orden estable, retención/configuración y pruebas de integridad.
- Respuesta mínima: id, acción, tipo/UUID técnico de objetivo, actor UUID cuando
  corresponda, timestamp y correlation ID; sin payload/PII/secretos.

## Excluido

- Modificar/borrar/exportar auditoría, búsqueda de texto, acceso de reclutador,
  eventos de CV en claro, UI, SIEM externo o notificaciones.

## Persistencia/contrato

Crear sólo `V25__administrative_audit_query.sql`: índices por timestamp/acción/
tipo/actor, constraint append-only y rol de BD sin UPDATE/DELETE para tabla de
auditoría. `GET /api/v1/admin/audit-events` con filtros exactos y page 0/size
1–100, orden `createdAt desc,id asc`; sólo ADMIN/sesión vigente.

## Reglas y seguridad

1. Filtros se validan/parametrizan; rango máximo 366 días, enums cerrados y UUID
   válidos; error `422` sin SQL.
2. Eventos no muestran PII, hash, tokens, rutas, CV, request/response ni motivo
   de privacidad. Correlation ID es campo, no etiqueta métrica.
3. Repositorio de lectura no ofrece métodos de mutación; integración comprueba
   que consulta no cambia conteo/checksum de eventos.

## Criterios de aceptación

1. Sólo ADMIN activo consulta; `401`/`403` seguros.
2. Página/filtros/rango/orden estable funcionan sin inyección o PII.
3. Auditoría es append-only y lectura no altera eventos.
4. API/logs/OpenAPI/métricas no filtran secretos, CVs, payloads o UUID como tag.
5. V25/Testcontainers cubren autorización, filtros, inmutabilidad e integridad.

## Definition of Ready

`READY_FOR_DEV`
