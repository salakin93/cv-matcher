# Flujo de trabajo con agentes

## Flujo por funcionalidad

```text
Solicitud → Spec → Desarrollo → QA y Privacidad → Integración → Validación funcional
```

1. **Arquitecto:** crea o actualiza una spec en `.agents/specs/`.
2. **Desarrollador:** implementa únicamente el alcance aprobado y ejecuta verificaciones.
3. **QA reviewer:** compara la implementación con criterios de aceptación.
4. **Security & privacy reviewer:** revisa datos sensibles, OAuth, archivos y comportamiento de IA.
5. **Integración:** solo después de ambas revisiones aprobadas; ejecutar pruebas en la rama integrada.
6. **Validación funcional:** confirmar el flujo contra el PRD antes de marcarlo terminado.

## Cuándo usar trabajo paralelo

Solo dividir en tareas paralelas si los archivos y contratos compartidos están definidos y no se solapan. Para cambios concurrentes, usar ramas o Git worktrees; un cambio en migraciones, contratos API o modelos compartidos debe coordinarse primero.

## Definition of Done

Una funcionalidad termina cuando:

- la spec y sus criterios de aceptación se cumplen;
- las pruebas relevantes pasan;
- no se exponen datos sensibles;
- QA y la revisión de privacidad no tienen hallazgos bloqueantes;
- se actualizó OpenAPI, documentación y migraciones cuando corresponde.
