# Flujo de trabajo con agentes

Este documento define el flujo oficial de trabajo del proyecto CV Matcher.

Todos los agentes deben respetar:

- `.agents/context/project.md`
- `.agents/context/constraints.md`

Ningún agente puede omitir una revisión obligatoria para acelerar una entrega.

---

# 1. Flujo general por funcionalidad

```text
Solicitud
↓
PRD / Requisito
↓
Architect
↓
Spec aprobada
↓
Backend DEV / Frontend DEV
↓
Technical Review
↓
QA + Security & Privacy Review
↓
Release Review
↓
Integración
↓
Deploy cuando corresponda
↓
Validación funcional final
↓
DONE
```

No todas las funcionalidades requieren simultáneamente backend y frontend.

Cuando una funcionalidad afecte únicamente uno de ellos, ejecutar únicamente
el DEV correspondiente.

---

# 2. Etapa 1 - Solicitud

Toda funcionalidad comienza con una necesidad proveniente de:

- PRD
- feature
- historia de usuario
- bug
- mejora aprobada
- requisito técnico autorizado

La solicitud debe tener suficiente contexto para que Architect pueda
determinar:

- problema
- alcance
- restricciones
- dependencias
- impacto esperado

Una solicitud no equivale automáticamente a autorización para implementar.

---

# 3. Etapa 2 - Architect

El Architect transforma la necesidad aprobada en una spec implementable.

Debe:

- revisar PRD
- revisar contexto
- revisar restricciones
- analizar arquitectura
- definir alcance
- definir contratos
- definir reglas
- definir persistencia cuando corresponda
- definir integraciones
- definir criterios de aceptación
- definir estrategia de pruebas
- identificar riesgos
- identificar dependencias
- identificar preguntas abiertas

Salida esperada:

`.agents/specs/NNN-nombre.md`

La spec debe alcanzar Definition of Ready antes de pasar a desarrollo.

---

# 4. Definition of Ready

Una spec está lista para desarrollo cuando contiene como mínimo:

- objetivo
- referencia al requisito
- alcance incluido
- alcance excluido
- comportamiento esperado
- criterios de aceptación verificables
- contratos relevantes
- reglas de negocio
- impacto de datos cuando corresponda
- manejo de errores relevante
- dependencias
- riesgos
- estrategia de pruebas

No debe contener preguntas abiertas bloqueantes.

Si DEV necesita inventar comportamiento funcional o una decisión
arquitectónica importante:

la spec NO está lista.

Resultado:

`READY_FOR_DEV`

o

`BLOCKED`

---

# 5. Etapa 3 - Desarrollo

La implementación puede dividirse entre:

- Backend DEV
- Frontend DEV

según el alcance de la spec.

Los DEV deben:

- implementar únicamente alcance aprobado
- trabajar incrementalmente
- agregar pruebas
- ejecutar verificaciones relevantes
- mantener commits lógicos y atómicos
- respetar contratos
- respetar arquitectura
- respetar privacidad y seguridad
- reportar hallazgos fuera de alcance

No deben ampliar silenciosamente la funcionalidad.

---

# 6. Desarrollo Backend

Backend DEV es responsable de:

- APIs
- reglas de negocio
- persistencia
- migraciones
- integración con servicios externos
- validaciones backend
- manejo de errores
- OpenAPI
- pruebas backend

Cuando corresponda debe ejecutar:

- compilación
- unit tests
- integration tests
- Testcontainers
- validación de migraciones
- build

No declarar implementación terminada sin evidencia.

---

# 7. Desarrollo Frontend

Frontend DEV es responsable de:

- pantallas
- componentes
- estado
- formularios
- integración con API
- accesibilidad
- manejo de loading
- empty
- error
- retry
- tests frontend

Cuando corresponda debe ejecutar:

- typecheck
- lint
- tests
- build

No inventar contratos no definidos por backend/OpenAPI/spec.

---

# 8. Desarrollo paralelo

Backend DEV y Frontend DEV pueden trabajar en paralelo únicamente cuando
los contratos compartidos estén suficientemente definidos.

Debe existir claridad sobre:

- endpoints
- métodos HTTP
- request
- response
- estados
- códigos HTTP
- tipos
- reglas relevantes

Si el contrato todavía está cambiando:

coordinar primero con Architect.

---

# 9. Etapa 4 - Technical Review

Después de la implementación debe realizarse Technical Review.

El Technical Reviewer verifica:

- coherencia arquitectónica
- separación de responsabilidades
- mantenibilidad
- complejidad
- duplicación
- testabilidad
- calidad del diff
- calidad de tests
- compatibilidad
- deuda técnica introducida

Resultados:

`APROBADO`

o

`CAMBIOS_REQUERIDOS`

Si el resultado es:

`CAMBIOS_REQUERIDOS`

el cambio vuelve al DEV correspondiente.

Después de la corrección debe realizarse nuevamente la revisión de los
hallazgos afectados.

No continuar a Release Review con Technical Review pendiente.

---

# 10. Etapa 5 - QA

QA verifica principalmente comportamiento.

Debe comparar:

PRD
↔
SPEC
↔
IMPLEMENTACIÓN
↔
EVIDENCIA

Para cada criterio de aceptación utilizar:

- `PASSED`
- `FAILED`
- `NOT VERIFIED`

QA debe verificar cuando corresponda:

- happy path
- validaciones
- errores
- estados de negocio
- persistencia
- contratos
- frontend
- regresiones
- integraciones simuladas

Resultado:

`APROBADO`

o

`CAMBIOS_REQUERIDOS`

---

# 11. Etapa 6 - Security & Privacy Review

Security & Privacy Reviewer verifica que una implementación funcionalmente
correcta también sea segura y respete privacidad.

Debe revisar según corresponda:

- secretos
- tokens
- OAuth
- Microsoft Graph
- Anthropic
- PII
- CVs
- archivos
- autorización
- minimización de datos
- logs
- auditoría
- exportaciones
- contenido no confiable
- IA
- prompt injection
- score determinista

Resultado:

`APROBADO`

o

`CAMBIOS_REQUERIDOS`

Hallazgos CRITICAL y HIGH bloquean integración.

---

# 12. QA y Security en paralelo

QA y Security & Privacy Review pueden ejecutarse en paralelo cuando
Technical Review ya ha determinado que la implementación es suficientemente
estable para revisión.

```text
              Technical Review
                     │
                     ▼
            ┌────────┴────────┐
            ▼                 ▼
           QA             Security
            │                 │
            └────────┬────────┘
                     ▼
               Release Review
```

No es necesario que QA espere a Security ni que Security espere a QA.

Ambos deben estar aprobados antes de Release Review.

---

# 13. Corrección de hallazgos

Si un reviewer detecta un problema:

Reviewer
↓
Hallazgo
↓
DEV
↓
Corrección
↓
Pruebas
↓
Nuevo commit
↓
Revisión del hallazgo

El reviewer NO corrige código de producción.

Si la corrección requiere cambiar:

- arquitectura
- contrato global
- estrategia de persistencia
- autenticación
- autorización
- infraestructura
- comportamiento del producto

el problema debe escalarse al Architect.

---

# 14. Etapa 7 - Release Review

Release Reviewer es la última compuerta antes de integración o deployment.

Debe consolidar evidencia de:

- Technical Review
- QA
- Security & Privacy Review

y verificar:

- build final
- tests finales
- estado del repositorio
- commits
- OpenAPI
- compatibilidad frontend/backend
- migraciones
- configuración
- variables de entorno
- orden de despliegue
- riesgos
- rollback cuando corresponda

Resultados:

`READY_FOR_RELEASE`

o

`BLOCKED`

Release Reviewer NO corrige código.

---

# 15. Condiciones para READY_FOR_RELEASE

Una funcionalidad puede marcarse:

`READY_FOR_RELEASE`

únicamente cuando:

- Technical Review está APROBADO
- QA está APROBADO
- Security & Privacy Review está APROBADO
- build requerido pasa
- pruebas requeridas pasan
- contratos son consistentes
- migraciones son válidas
- configuración necesaria está identificada
- no existen hallazgos CRITICAL
- no existen hallazgos HIGH pendientes
- no existen bloqueadores conocidos

Una validación requerida marcada:

`NOT VERIFIED`

debe evaluarse explícitamente antes de release.

---

# 16. Integración

La integración ocurre únicamente después de:

`READY_FOR_RELEASE`

Durante integración verificar nuevamente:

- ausencia de conflictos inesperados
- compatibilidad con cambios recientes
- build integrado
- pruebas relevantes
- migraciones
- contratos

Si la integración introduce cambios sustanciales:

la evidencia previa puede dejar de ser válida.

Debe repetirse la revisión necesaria.

---

# 17. Ramas y Git

Para trabajo concurrente utilizar cuando corresponda:

- branches
- Git worktrees

Evitar que múltiples agentes modifiquen simultáneamente los mismos archivos
sin coordinación.

Cambios concurrentes sobre:

- migraciones
- contratos API
- modelos compartidos
- configuración
- arquitectura

deben coordinarse antes.

No depender únicamente de resolver conflictos al final.

---

# 18. Conflictos de integración

Un conflicto Git resuelto no significa que el comportamiento integrado sea
correcto.

Después de resolver conflictos:

- revisar diff resultante
- ejecutar pruebas relevantes
- ejecutar build
- verificar contratos
- verificar criterios afectados

Si el conflicto altera lógica significativa:

volver al reviewer correspondiente.

---

# 19. Migraciones concurrentes

Cuando múltiples funcionalidades agreguen migraciones Flyway:

coordinar:

- versión
- orden
- dependencia
- compatibilidad

No renumerar ni modificar migraciones ya aplicadas.

Un conflicto de versiones debe resolverse antes de release.

---

# 20. Contratos compartidos

Cambios en OpenAPI o modelos utilizados por backend y frontend deben
coordinarse.

Flujo recomendado:

Architect define contrato
↓
Backend implementa
↓
OpenAPI refleja contrato
↓
Frontend consume contrato

Cuando backend y frontend trabajan en paralelo:

la spec debe definir suficientemente el contrato antes de comenzar.

---

# 21. Validación funcional final

Después de integración, ejecutar una validación funcional contra el PRD.

El objetivo es comprobar que:

- el flujo completo funciona
- la integración entre componentes es correcta
- el comportamiento continúa cumpliendo el objetivo del producto

No debe convertirse en una segunda implementación ni en una revisión
arquitectónica completa.

---

# 22. Resultado final de funcionalidad

Una funcionalidad puede marcarse:

`DONE`

únicamente cuando:

```text
SPEC READY
+
IMPLEMENTATION COMPLETE
+
TECHNICAL REVIEW APPROVED
+
QA APPROVED
+
SECURITY APPROVED
+
RELEASE READY
+
INTEGRATION VERIFIED
+
FUNCTIONAL VALIDATION PASSED
=
DONE
```

---

# 23. Definition of Done

Una funcionalidad termina cuando:

- la spec está aprobada
- todos los criterios de aceptación se cumplen
- backend y frontend requeridos están implementados
- las pruebas relevantes pasan
- build requerido pasa
- Technical Review está aprobado
- QA está aprobado
- Security & Privacy Review está aprobado
- Release Review devuelve READY_FOR_RELEASE
- no existen hallazgos bloqueantes
- no se exponen datos sensibles
- OpenAPI está actualizado cuando corresponde
- documentación está actualizada cuando corresponde
- migraciones están incluidas y verificadas cuando corresponde
- integración final fue validada
- no existen cambios accidentales
- riesgos residuales están documentados

---

# 24. Flujo ante fallo

Si un paso falla:

```text
                 REVIEW
                    │
                    ▼
            CAMBIOS_REQUERIDOS
                    │
                    ▼
                   DEV
                    │
                    ▼
                CORRECCIÓN
                    │
                    ▼
                 PRUEBAS
                    │
                    ▼
              NUEVO COMMIT
                    │
                    ▼
            REVIEW AFECTADO
```

No reiniciar necesariamente todas las revisiones.

Repetir las revisiones afectadas por el cambio.

Si la corrección tiene impacto transversal:

repetir también las revisiones relacionadas.

---

# 25. Escalamiento al Architect

Volver al Architect cuando un hallazgo requiera cambiar:

- alcance
- contrato principal
- reglas de negocio
- modelo crítico
- arquitectura
- estrategia de seguridad
- flujo OAuth
- tecnología principal
- infraestructura
- comportamiento especificado

Architect actualiza la spec cuando corresponda.

Después:

DEV
↓
Reviews
↓
Release

---

# 26. Hallazgos fuera del alcance

Los agentes pueden detectar problemas preexistentes.

Clasificarlos como:

- CRITICAL
- HIGH
- MEDIUM
- LOW

y como:

- introducido por el cambio
- preexistente
- agravado por el cambio

No introducir automáticamente mejoras fuera del alcance.

CRITICAL debe reportarse inmediatamente.

---

# 27. Trabajo paralelo permitido

Puede paralelizarse cuando existe independencia real.

Ejemplos:

```text
Backend DEV ─────────┐
                     ├──► integración
Frontend DEV ────────┘
```

y:

```text
QA ──────────────────┐
                     ├──► Release Review
Security Review ─────┘
```

No paralelizar tareas que necesiten tomar simultáneamente decisiones
incompatibles sobre el mismo contrato.

---

# 28. Trabajo paralelo no recomendado

Evitar paralelizar sin coordinación:

- dos cambios sobre la misma migración
- modificaciones incompatibles de OpenAPI
- cambios simultáneos del mismo modelo central
- cambios diferentes sobre autenticación
- refactors grandes sobre archivos compartidos
- cambios arquitectónicos relacionados

Primero definir contrato o decisión.

Después dividir implementación.

---

# 29. Evidencia

Todos los pasos de validación deben basarse en evidencia.

Estados permitidos:

`PASSED`

La validación fue ejecutada exitosamente.

`FAILED`

La validación fue ejecutada y falló.

`NOT VERIFIED`

La validación debería realizarse pero no pudo comprobarse.

`NOT APPLICABLE`

La validación no corresponde a esa funcionalidad.

No utilizar:

`PASSED`

cuando una validación no fue ejecutada.

---

# 30. Severidades

Todos los reviewers utilizan:

`CRITICAL`

`HIGH`

`MEDIUM`

`LOW`

En general:

CRITICAL
→ bloquea inmediatamente

HIGH
→ bloquea

MEDIUM
→ corregir o justificar según impacto

LOW
→ recomendación o riesgo residual

---

# 31. Responsabilidades resumidas

| Rol | Responsabilidad |
|---|---|
| Architect | Convertir requisito en spec implementable |
| Backend DEV | Implementar backend |
| Frontend DEV | Implementar frontend |
| Technical Reviewer | Revisar calidad técnica |
| QA | Verificar comportamiento y criterios |
| Security & Privacy Reviewer | Verificar seguridad, privacidad e IA |
| Release Reviewer | Verificar preparación para integración/deploy |

---

# 32. Matriz de autoridad

| Decisión | Responsable principal |
|---|---|
| Alcance funcional | PRD / Architect |
| Arquitectura | Architect |
| Implementación backend | Backend DEV |
| Implementación frontend | Frontend DEV |
| Calidad interna | Technical Reviewer |
| Cumplimiento funcional | QA |
| Seguridad y privacidad | Security Reviewer |
| Release readiness | Release Reviewer |
| Decisión de contratación | Humano autorizado |

---

# 33. Principio de independencia

Ningún agente debe ser juez final de su propio trabajo.

DEV implementa.

Reviewer revisa.

Reviewer no corrige producción.

Architect decide cambios estructurales.

Release consolida evidencia.

---

# 34. Principio de mínimo proceso

El workflow debe ser proporcional al cambio.

Una modificación pequeña no necesita burocracia innecesaria.

Sin embargo, ninguna tarea puede omitir controles obligatorios relacionados
con:

- seguridad
- privacidad
- persistencia
- contratos
- comportamiento crítico

El objetivo del proceso es reducir riesgo, no aumentar documentación.

---

# 35. Principio final

Cada funcionalidad debe poder responder:

1. ¿Qué requisito estamos implementando?
2. ¿Qué spec lo define?
3. ¿Qué cambió?
4. ¿Qué pruebas lo demuestran?
5. ¿Quién revisó la calidad técnica?
6. ¿Quién verificó el comportamiento?
7. ¿Quién verificó seguridad y privacidad?
8. ¿Está preparado para release?
9. ¿Qué riesgos permanecen?

Si estas preguntas no pueden responderse con evidencia:

la funcionalidad todavía no está terminada.
