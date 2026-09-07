# FE-005 - Integración Outlook

## Objetivo

Ofrecer a ADMIN un panel mínimo para conocer estado de Outlook e iniciar/reautorizar OAuth sin que secretos, tokens, state o code lleguen a almacenamiento cliente.

## Referencias

- `docs/PRD.md`, sección 9.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-005.
- `docs/FRONTEND_ROADMAP.md`, FE-005.
- `.agents/specs/005-outlook-connection.md`.

## Alcance

### Incluido

- Ruta ADMIN de estado de conexión y acciones iniciar/reautorizar.
- Navegación a URL proporcionada por backend y retorno seguro definido por contrato.

### Excluido

- Mostrar/editar tenant, mailbox, secretos, tokens, scopes sin aprobación, Inbox o mensajes.

## Comportamiento y reglas

- El navegador abre sólo la URL de autorización emitida por backend y no fabrica `state`, PKCE ni callback.
- La pantalla de retorno elimina parámetros OAuth de la URL al procesar el resultado backend y recarga estado.
- Estado `REAUTHORIZATION_REQUIRED` se presenta sin razón sensible.

## Contratos

Usar sólo OpenAPI 005 para estado, inicio/reautorización y resultado de callback backend.

## Datos y persistencia

No guardar URL, `state`, `code`, tokens ni información de proveedor después de navegar.

## Integraciones

La integración Microsoft es server-side; frontend sólo realiza navegación autorizada.

## Errores y estados

Estados loading, conectado, no conectado, reautorización, error seguro y retry cuando contrato lo admita.

## Seguridad y privacidad

Sólo ADMIN. No renderizar ni loguear secretos, tokens, IDs de tenant/mailbox, parámetros OAuth o payloads Graph.

## Observabilidad

Errores técnicos sin URL de OAuth ni parámetros.

## Estrategia de pruebas

Componentes de estados, contrato OpenAPI, E2E de navegación simulada y limpieza de parámetros de retorno.

## Criterios de aceptación

1. Sólo ADMIN accede al panel.
2. Iniciar/reautorizar sólo abre una URL backend.
3. No quedan secretos ni parámetros OAuth en UI, URL, logs o persistencia cliente.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | OpenAPI/callback de 005 no aprobado. | No implementar flujo OAuth. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** OAuth confidencial y sus tokens permanecen en backend.

## Definition of Ready

`BLOCKED`
