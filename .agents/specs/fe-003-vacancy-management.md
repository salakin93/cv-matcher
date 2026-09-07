# FE-003 - Gestión de vacantes

## Objetivo

Ofrecer a reclutadores la interfaz para listar, crear, editar, archivar y reactivar vacantes con requisitos ponderados, respetando la versión y validación backend.

## Referencias

- `docs/PRD.md`, sección 4.
- `docs/FRONTEND_PHASE_SPECS.md`, FE-003.
- `docs/FRONTEND_ROADMAP.md`, FE-003.
- `.agents/specs/003-vacancy-management.md`.

## Alcance

### Incluido

- Lista, detalle, formulario de creación/edición, requisitos ordenables y archivo/reactivación.
- Entrada de fechas en `America/La_Paz` y manejo visual de `VERSION_CONFLICT`.

### Excluido

- Solicitud de reportes, candidatos, resultados, búsqueda histórica e integraciones.

## Comportamiento y reglas

- La UI no calcula pesos, versiones, elegibilidad ni reglas de archivo; envía datos validados por contrato incluido `expectedVersion`.
- Requisitos preservan el orden enviado por usuario y muestran peso 1 a 5, descripción y marca obligatoria.
- Ante conflicto, conserva el borrador local, presenta que la versión cambió y obliga a recargar o cancelar; nunca sobrescribe automáticamente.

## Contratos

Usar exclusivamente OpenAPI aprobado de backend 003 para lista, detalle, mutaciones, archivo/reactivación y errores de versión.

## Datos y persistencia

Los borradores no se persisten automáticamente. No modificar datos compartidos en caché tras conflicto o fallo.

## Integraciones

No inicia jobs ni llama servicios externos.

## Errores y estados

Soporta loading, vacío, validación, conflicto, retry y error seguro. Fechas inválidas se corrigen antes de enviar sin alterar reglas timezone backend.

## Seguridad y privacidad

Ruta autenticada para `RECRUITER`/`ADMIN`; no exponer datos a `401`/`403`. No registrar descripciones de vacante en telemetry sin aprobación.

## Observabilidad

Errores técnicos seguros y `correlationId`; sin contenido de formularios.

## Estrategia de pruebas

Componentes de requisitos/fechas/conflicto, contrato OpenAPI 003 y E2E de CRUD, archivo, reactivación y conflicto.

## Criterios de aceptación

1. Un reclutador administra vacantes y requisitos válidos mediante API.
2. La UI envía `expectedVersion` y nunca sobrescribe un conflicto.
3. Fechas se presentan en Bolivia y el resultado backend es la autoridad.
4. La pantalla es accesible, responsiva y maneja errores seguros.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| BLOCKER | Contrato OpenAPI de 003 aún no está validado para frontend. | No implementar cliente ni rutas hasta aprobarlo. |

## Decisiones / preguntas abiertas

- **ARCHITECTURAL DECISION:** Versionado y validaciones de vacante pertenecen al backend.

## Definition of Ready

`BLOCKED`

Requiere OpenAPI 003 aprobado y disponible.
