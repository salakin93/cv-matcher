# FE-011 - Operación administrativa

## Objetivo

Dar a ADMIN una interfaz de configuración allowlisted, estado de integraciones y auditoría paginada sin revelar secretos, payloads de proveedor o documentos.

## Referencias

- `docs/PRD.md`, secciones 2 y 9.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-011.
- `docs/FRONTEND_ROADMAP.md`, FE-011.
- `.agents/specs/023-secure-system-configuration.md` y `024-administrative-audit-query.md`.

## Alcance

### Incluido

- Ruta ADMIN para configuración permitida, estado de Outlook/Claude y auditoría filtrable/paginada.

### Excluido

- Editar secretos, tokens, credenciales, payloads de proveedor, CVs, migraciones y cualquier campo fuera de allowlist backend.

## Comportamiento y reglas

- Formularios se generan/validan contra campos explícitamente allowlisted por API; no muestran campos desconocidos.
- Estado de integración es informativo y seguro. La pantalla no inicia OAuth, que pertenece a FE-005.
- Auditoría se renderiza como registro inmutable; filtros/paginación se envían al backend y no se alteran eventos.

## Contratos

Usar OpenAPI 023-024 para configuración, estado de integraciones y consulta administrativa de auditoría.

## Datos y persistencia

No persistir configuración, eventos de auditoría, IDs o PII fuera de memoria.

## Integraciones

No llama proveedores ni administra secretos; consume sólo API backend administrativa.

## Errores y estados

Loading, vacío, validación, conflicto, error seguro, retry y paginación. `401`/`403` eliminan contenido administrativo previamente cargado.

## Seguridad y privacidad

- Sólo ADMIN accede; guard visual no sustituye backend.
- Nunca renderizar, loguear o almacenar secretos, tokens, correos no autorizados, CVs, rutas o payloads de proveedor.

## Observabilidad

Telemetría técnica sin valores de configuración, eventos de auditoría ni identificadores personales.

## Estrategia de pruebas

Componentes de allowlist/auditoría, contrato OpenAPI y E2E de guard ADMIN, filtros, paginación, validación y ausencia de campos secretos.

## Criterios de aceptación

1. Sólo ADMIN ve configuración, integración y auditoría.
2. La UI permite únicamente campos allowlisted por backend.
3. Auditoría se consulta sin alterar eventos ni exponer datos prohibidos.
4. Secretos, tokens, documentos y payloads nunca se muestran o persisten.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI 023-024 y FE-002 no aprobados. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** Configuración efectiva y auditoría inmutable son autoridad backend; frontend sólo presenta DTOs seguros.

## Definition of Ready

`BLOCKED`
