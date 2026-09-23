# 016 - Shared profiles and availability extension

## Estado
`READY_FOR_DEV` — backend only; PRD 009; depends on 011, 012 and 015.

## Objetivo
Mantener perfiles compartidos y extender consulta/exportación de reportes con disponibilidad actual, sin reescribir reportes inmutables.

## Referencias
`docs/prd-009-shared-profiles-and-historical-search.md`, `docs/prd-008-report-filters-and-exports.md`, `docs/architecture.md` §4.

## Alcance
### Incluido
- Consulta y actualización optimista de disponibilidad, ubicación y habilidades corregibles del perfil compartido, con historial de correcciones.
- Filtro de una o más disponibilidades, búsqueda textual limitada a entradas del reporte y disponibilidad en nuevas exportaciones.
### Excluido
- Corrección de nombre/correo/identidad, fusión de perfiles, directorio histórico global, búsqueda histórica, descarga y modificación del snapshot.

## Comportamiento y reglas
- La disponibilidad inicial y fallback es `DESCONOCIDO`; valores: `DISPONIBLE`, `NO_DISPONIBLE`, `DESCONOCIDO`.
- Ubicación y habilidades inician vacías; cada corrección conserva el valor anterior y reemplaza sólo la vista de perfil y búsquedas futuras. Cambios concurrentes usan `expectedVersion` y el primero confirmado gana.
- `availability` y texto de perfil son joins de lectura actuales sobre entradas de un reporte. El filtro multi-valor coincide con cualquiera y se combina por `Y` con 015. Las exportaciones solicitadas desde 016 incluyen disponibilidad actual o `DESCONOCIDO` al generarse; artefactos ya generados por 015 no se regeneran.

## Contratos
- `GET /api/v1/candidate-profiles/{id}` y `PUT` con `{ availability, location?, skills?, expectedVersion }`; respuesta incluye valores actuales, último valor anterior permitido, `version` y UTC, no CV/texto extraído.
- Extender candidates de reporte con `candidateProfileId?`, `availability` y query `availability=DISPONIBLE,DESCONOCIDO`, `query=<texto>`; `candidateProfileId` es el identificador de recurso autorizado y es nulo para anonimos. `query` busca nombre, correo o habilidades de las propias entradas, normalizado, máximo 100 caracteres.
- Extender exportación 015 para que PDF/XLSX incluya `availability`. `422 INVALID_PROFILE_UPDATE|INVALID_AVAILABILITY_FILTER|INVALID_REPORT_QUERY`; `409 VERSION_CONFLICT`.

## Configuración centralizada
No introduce configuración externa. Límites de página y query reutilizan propiedades centralizadas de API si existen; no crear constantes duplicadas.

## Datos y persistencia
- Flyway: completar `candidate_profile` con disponibilidad default `DESCONOCIDO`, ubicación/habilidades inicialmente nulas, valores actuales y `version`; crear `candidate_profile_correction` con perfil, campo, valor anterior/corregido protegido, actor y UTC. Índices por disponibilidad y perfil.
- `candidate` posee perfiles y publica DTO/puerto de lectura; `reporting` no accede sus tablas y resuelve disponibilidad por puerto en consulta/exportación.
- Auditar cambios efectivos (`PROFILE_AVAILABILITY_CHANGED`, `PROFILE_LOCATION_CORRECTED`, `PROFILE_SKILLS_CORRECTED`) en la transacción de actualización.

## Integraciones
Sólo puertos `candidate`, `reporting` y `audit`; no proveedores externos.

## Errores y estados
- Perfil inexistente o eliminado por privacidad devuelve `404` seguro. Perfil bloqueado por privacidad no puede leerse ni cambiarse.
- La actualización parcial no permite borrar origen ni modificar campos no autorizados; body con nombre/correo retorna `422`.

## Seguridad y privacidad
- `RECRUITER`/`ADMIN` para perfil y filtros; validar acceso compartido sin exponer perfiles eliminados.
- Historial y auditoría no incluyen CV/texto; el valor original/corregido se muestra sólo donde la autorización de perfil lo permite, nunca en logs.

## Observabilidad
Métricas de actualizaciones/conflictos y uso de filtros; logs con profileId/correlationId y campo, sin valores de perfil.

## Estrategia de pruebas
### Validación manual
- Crear/consultar perfil, cambiar cada campo con dos sesiones para conflicto, y verificar que reporte histórico no cambia.
- Filtrar por múltiples disponibilidades y texto de entradas propias; generar export y comprobar `DESCONOCIDO` y disponibilidad actual.
### Backlog de automatización diferida
- Integración de versión/historial/auditoría; API de campos permitidos; contrato entre módulos; regresión de export dinámico e inmutabilidad del reporte.

## Criterios de aceptación
- AC-009-01 y AC-009-02 se cumplen; los valores de perfil afectan sólo lecturas futuras y nuevas exportaciones, no rankings ni versiones cerradas.

## Riesgos y dependencias
- Depende de la asignación perfil-documento de 011 y de la exportación durable de 015. 017 consume el puerto de elegibilidad de perfil/documento.

## Decisiones / preguntas abiertas
- `ARCHITECTURAL DECISION`: disponibilidad es un overlay actual del módulo `candidate`, default `DESCONOCIDO`; no se snapshottea en `report_version`.

## Definition of Ready
`READY_FOR_DEV`
