# 023 - Secure System Configuration

## Objetivo

Permitir que `ADMIN` consulte estado seguro de Outlook/Claude y ajuste sólo
parámetros permitidos para operaciones futuras: modelo Claude, concurrencia y
límites, sin revelar ni editar secretos.

## Alcance

- Configuración versionada/optimista, valores allowlisted, estado de integración
  seguro, auditoría, OpenAPI y métricas.
- Parámetros: modelo permitido, concurrencia global jobs, límites de mensajes,
  documentos y reintentos; aplicación futura de cambios, no jobs activos.

## Excluido

- Leer/escribir client secrets, API keys, refresh tokens, encryption keys, SMTP,
  credenciales Graph, configuración por reclutador, UI o reinicio automático.

## Persistencia/contrato

Crear sólo `V24__secure_system_configuration.sql`: singleton `system_configuration`
con versión y valores no secretos; revisiones append-only sin secretos. `GET
/api/v1/admin/system-configuration`, `PATCH` con `expectedVersion`; `GET
/api/v1/admin/integrations/status`. Sólo ADMIN con sesión vigente.

## Reglas

1. Modelo debe estar en allowlist server-side; concurrencia/límites tienen rangos
   seguros. Campos desconocidos son `422`.
2. PATCH idéntico es `204`; conflicto es `409 VERSION_CONFLICT`.
3. Cambios afectan sólo jobs nuevos y no reconfiguran OAuth/Claude activos.
4. Estado expone conectado/degradado/reauth requerido y código seguro, nunca
   tenant, mailbox, token, secreto, URL o respuesta proveedor.

## Criterios de aceptación

1. Sólo ADMIN lee/cambia configuración; `401`/`403` seguros.
2. Secretos nunca aparecen en API, BD de configuración, logs, auditoría o métricas.
3. Allowlist/rangos/versionado/idempotencia/concurrencia son verificables.
4. Estado de integración es mínimo y no filtra PII/proveedor.
5. V24/Testcontainers cubren seguridad, auditoría y cambios futuros sin UI.

## Definition of Ready

`READY_FOR_DEV`
