# FE-004 - Trabajos de reporte

## Objetivo

Permitir solicitar y seguir trabajos asíncronos de reporte sin bloquear la interfaz ni exponer metadatos de correo o documentos.

## Referencias

- `docs/PRD.md`, secciones 4 y 5.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-004.
- `docs/FRONTEND_ROADMAP.md`, FE-004.
- `.agents/specs/004-report-job-queue.md` a `006-outlook-inbox-discovery.md`.

## Alcance

### Incluido

- Solicitud de job desde una vacante, tarjeta de estado, polling con backoff, cancelación, retry y navegación al reporte.
- Estados y advertencias seguros entregados por API.

### Excluido

- Inbox, mensajes, adjuntos, logs Graph, análisis Claude y renderizado de ranking.

## Comportamiento y reglas

- La solicitud responde de forma asíncrona; la UI nunca espera ingestión o análisis.
- Sólo se consulta el estado de job entregado por backend y se detiene polling en estados terminales, salida de ruta, `401` o `403`.
- Doble clic queda bloqueado mientras exista solicitud en curso. Retry/cancelación se habilitan sólo si OpenAPI lo declara.

## Contratos

Consumir OpenAPI aprobado de 004-006 para crear, consultar, cancelar o reintentar jobs y navegar con IDs entregados por API.

## Datos y persistencia

Estado de polling en memoria. No almacenar `jobId`, detalles de documentos ni warnings persistentemente.

## Integraciones

El navegador no llama Outlook ni Claude.

## Errores y estados

Mostrar estados backend, loading, retry, fallo seguro y `REAUTHORIZATION_REQUIRED` sin detalles de proveedor.

## Seguridad y privacidad

Rutas autenticadas; warnings no renderizan correo, remitente, adjuntos, rutas o payloads externos.

## Observabilidad

Sin logs de identificadores de mensajes/documentos ni contenido de warnings.

## Estrategia de pruebas

Pruebas de scheduler/polling, cancelación/retry y E2E de solicitud a estados terminales con servidor simulado.

## Criterios de aceptación

1. Solicitar reporte devuelve control inmediato a la UI.
2. Polling tiene backoff, se detiene correctamente y no duplica solicitudes.
3. Fallos y reautorización son seguros y accionables sólo cuando API lo permite.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI conjunto 004-006 y FE-003 no aprobados. | Bloquear implementación. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** El backend durable es fuente de verdad de estado; frontend no infiere transiciones.

## Definition of Ready

`BLOCKED`
