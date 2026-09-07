# FE-008 - Exportaciones y notificaciones

## Objetivo

Permitir solicitar y descargar exportaciones cuando estén listas, y consultar notificaciones in-app sin revelar datos sensibles.

## Referencias

- `docs/PRD.md`, secciones 4 y 7.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-008.
- `docs/FRONTEND_ROADMAP.md`, FE-008.
- `.agents/specs/015-secure-report-export.md` y `022-job-notifications.md`.

## Alcance

### Incluido

- Solicitud de PDF/XLSX, estado, disponibilidad, expiración y descarga autenticada.
- Bandeja in-app, lectura y navegación segura desde notificaciones.

### Excluido

- Envío manual de correo, plantillas, enlaces públicos o generación de contenido de exportación.

## Comportamiento y reglas

- Una acción de exportación queda inhabilitada mientras su solicitud está pendiente para evitar duplicados.
- La UI sólo habilita descarga cuando backend declara disponibilidad; expiración se presenta sin intentar recuperar recurso.
- Notificaciones muestran únicamente el texto/metadata segura del DTO y se marcan leídas por API.

## Contratos

Usar OpenAPI 015 y 022 para solicitudes asíncronas, estado, descarga, listado y lectura de notificaciones.

## Datos y persistencia

No guardar archivos, URLs de descarga ni contenido de notificación con PII en almacenamiento persistente.

## Integraciones

Correo y generación de exportación son server-side.

## Errores y estados

Loading, solicitud pendiente, disponible, expirado, vacío, warning, retry y fallo seguro.

## Seguridad y privacidad

Descargas sólo mediante endpoint autenticado; nunca enlaces públicos. No mostrar teléfono, dirección, rutas o atributos excluidos por exportación.

## Observabilidad

No registrar contenidos de notificación, nombre de archivo o datos exportados.

## Estrategia de pruebas

Componentes de estados, contrato OpenAPI y E2E de prevención de doble solicitud, disponibilidad, expiración, descarga y marcar leído.

## Criterios de aceptación

1. Una exportación no se solicita dos veces por doble interacción.
2. Descargas disponibles usan autorización backend y no persisten URL/archivo.
3. Notificaciones son accesibles, seguras y pueden marcarse leídas.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI 015/022 y FE-006 no aprobados. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** Disponibilidad y expiración de exportación son autoridad backend.

## Definition of Ready

`BLOCKED`
