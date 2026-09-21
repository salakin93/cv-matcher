# 024 - Admin audit query

## Estado
`DRAFT_FOR_APPROVAL` — backend only; PRD 011; depends on audit producers 002 and 013–023 where applicable.

## Objetivo
Exponer a ADMIN una consulta paginada y filtrable de eventos auditables inmutables, con referencias seguras y minimización de datos.

## Referencias
`docs/prd-011-administration-audit-and-notifications.md`, `docs/PRD.md` §9, `docs/architecture.md` §§4–5, 9.

## Alcance
### Incluido
- Consulta sólo ADMIN por tipo, actor y período UTC, con paginación/orden estable descendente.
- Normalización allowlisted de tipos y proyección segura de actor, UTC, tipo y referencia de recurso.
### Excluido
- Crear/editar/borrar eventos, auditoría recruiter, búsqueda de contenido/PII, exportación, acceso a CVs/payloads o retención configurable.

## Comportamiento y reglas
- Eventos son append-only y se ordenan `occurredAt DESC, id DESC`. Filtros se combinan con `Y`; fechas UTC son inclusivas y `from <= to`.
- Tipos incluyen únicamente acciones ya producidas: usuarios/roles/integraciones, estado humano, descarga, exportación, perfil, papelera/restore/purge, privacidad y configuración. Un productor no soportado no aparece hasta que registre el tipo allowlisted.
- Referencia segura es un código/tipo e ID interno opaco cuando es necesario; para privacidad sólo `privacyDeletionId`, nunca perfil/documento/persona.

## Contratos
- `GET /api/v1/admin/audit-events?actionType=&actorUserId=&from=&to=&page=&size=` devuelve `{ content, page, size, totalElements, totalPages }`; item `{ id, occurredAt, actionType, actor: { id, displayName }, resourceReference? }`.
- `actionType` acepta enum allowlisted; `from/to` ISO-8601 UTC. Máximo `size=100`; `422 INVALID_AUDIT_FILTER`, `401`, `403` y `404` de actor inexistente según contrato seguro.
- No hay endpoint de detalle que devuelva payload original.

## Configuración centralizada
`audit.query.max-page-size=100` en `application.yml` + `AuditProperties`; no configurable por ADMIN. Reutilizar serialización/error/correlationId de `shared`.

## Datos y persistencia
- Flyway: si no existe, crear `audit_event` append-only con UUID, occurred_at UTC, action_type enum/check, actor_user_id nullable para sistema, resource_type/reference segura y metadata JSON allowlisted; índice `(occurred_at DESC, id DESC)`, `(action_type, occurred_at DESC)`, `(actor_user_id, occurred_at DESC)`.
- Revocar UPDATE/DELETE al rol de aplicación de auditoría donde PostgreSQL lo permita y no exponer repositorio de mutación. Productores usan puerto `audit.append`; módulo `audit` posee consulta.

## Integraciones
No hay integraciones externas. Todos los módulos publican eventos mediante puerto, sin acceso directo a tabla.

## Errores y estados
- Filtros mal formados/no UTC/rango invertido no ejecutan consulta y devuelven `422`. Consultas vacías devuelven página vacía `200`.
- Un evento con metadata desconocida se proyecta sin metadata, nunca se serializa sin allowlist.

## Seguridad y privacidad
- Sólo ADMIN y autorización en backend. No incluir correo de candidato, CV, texto extraído, teléfono, dirección, token, secreto, ruta, hash, payload Graph/Claude ni valores de corrección.
- Logs de consulta contienen filtros estructurales, no valores PII; cache privado/no-store.

## Observabilidad
Métricas de latencia/resultado por filtros y rechazos de autorización; logs correlationId, actor administrativo y page size.

## Estrategia de pruebas
### Validación manual
- Generar acciones de cada familia, consultar con cada filtro y combinaciones, verificar orden/paginación/referencia segura y página vacía.
- Intentar como recruiter, rangos inválidos, size excesivo y revisar ausencia de PII/secretos, incluida privacidad.
### Backlog de automatización diferida
- Testcontainers de índices/paginación/append-only; API RBAC y filtros; pruebas de proyección/redacción por cada tipo; regresión de integridad de productores.

## Criterios de aceptación
- AC-011-02 se cumple: ADMIN ve eventos mínimos, inmutables y filtrables; RECRUITER no puede consultarlos.

## Riesgos y dependencias
- La cobertura visible depende de que cada incremento emita sus eventos. La inmutabilidad definitiva también requiere permisos DB de despliegue.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: la API de auditoría ofrece una proyección allowlisted, no el payload persistido; privacidad jamás expone referencia reidentificable.

## Definition of Ready
`READY_FOR_DEV`
