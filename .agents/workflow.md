# Flujo de trabajo con agentes

Este documento define el flujo oficial de trabajo de CV Matcher.

Todos los agentes deben respetar:

- `.agents/context/project.md`
- `.agents/context/constraints.md`
- la spec activa
- este workflow

Las restricciones globales no se repiten aquí.

---

## 1. Flujo principal

```text
Solicitud / PRD
      ↓
   Architect
      ↓
 READY_FOR_DEV
      ↓
Backend DEV / Frontend DEV
      ↓
Technical Review
      ↓
 ┌────┴────┐
 ↓         ↓
 QA     Security & Privacy
 └────┬────┘
      ↓
Release Review
      ↓
READY_FOR_RELEASE
      ↓
Integración / Deploy
      ↓
Validación funcional
      ↓
     DONE
```

No todas las funcionalidades requieren backend y frontend. Ejecutar únicamente
los roles aplicables al alcance.

---

## 2. Architect → READY_FOR_DEV

Architect convierte un requisito aprobado en una spec dentro de:

`.agents/specs/`

La spec debe definir lo necesario para implementar sin inventar decisiones
importantes, incluyendo cuando corresponda:

- objetivo y referencia al requisito;
- alcance incluido y excluido;
- comportamiento y reglas de negocio;
- contratos;
- impacto de datos;
- integraciones;
- errores relevantes;
- criterios de aceptación verificables;
- estrategia de pruebas;
- riesgos, dependencias y decisiones pendientes.

Estados:

- `READY_FOR_DEV`
- `BLOCKED`

Una pregunta abierta que afecte comportamiento, arquitectura, contrato,
persistencia, seguridad o privacidad puede bloquear desarrollo.

---

## 3. Desarrollo

Según la spec, ejecutar:

- Backend DEV;
- Frontend DEV;
- ambos, cuando corresponda.

Los DEV implementan únicamente el alcance aprobado, agregan las pruebas
relevantes y ejecutan las verificaciones definidas en `constraints.md`.

Backend y frontend pueden trabajar en paralelo cuando los contratos
compartidos estén suficientemente definidos.

Si necesitan inventar una decisión funcional o arquitectónica importante,
deben detenerse y escalarla.

---

## 4. Technical Review

Después del desarrollo, Technical Reviewer evalúa la calidad técnica del
cambio integrado o de las partes aplicables.

Revisa principalmente:

- coherencia con arquitectura;
- responsabilidades y acoplamiento;
- mantenibilidad y complejidad;
- persistencia y transacciones;
- integraciones;
- testabilidad y calidad de pruebas;
- compatibilidad;
- diff y deuda técnica introducida.

Estados:

- `APROBADO`
- `CAMBIOS_REQUERIDOS`

`CAMBIOS_REQUERIDOS` devuelve el cambio al DEV responsable.

---

## 5. QA y Security & Privacy

Con Technical Review aprobado, QA y Security & Privacy pueden ejecutarse en
paralelo.

### QA

Verifica:

`PRD → SPEC → IMPLEMENTACIÓN → PRUEBA → EVIDENCIA`

Cada criterio relevante debe quedar como:

- `PASSED`
- `FAILED`
- `NOT VERIFIED`
- `NOT APPLICABLE`

Resultado:

- `APROBADO`
- `CAMBIOS_REQUERIDOS`

### Security & Privacy

Verifica los controles aplicables de seguridad, privacidad, PII, OAuth,
archivos, autorización, integraciones e IA.

Resultado:

- `APROBADO`
- `CAMBIOS_REQUERIDOS`

Los hallazgos bloqueantes vuelven al DEV responsable.

---

## 6. Correcciones y re-review

Un reviewer no corrige código de producción.

Flujo de corrección:

```text
Hallazgo
   ↓
DEV responsable
   ↓
Corrección + pruebas
   ↓
Nuevo commit
   ↓
Re-review afectado
```

No es necesario repetir automáticamente todos los reviews.

Repetir aquellos cuyo alcance haya sido afectado por la corrección.

Ejemplos:

- cambio interno de código → Technical Review;
- cambio de comportamiento → QA;
- cambio sobre PII, auth, archivos, OAuth o IA → Security & Privacy;
- cambio transversal → todos los reviews afectados.

Si la solución exige cambiar arquitectura, alcance o contrato principal,
volver a Architect y actualizar la spec cuando corresponda.

---

## 7. Vigencia de aprobaciones

Una aprobación solo es válida para el código revisado.

Si existen nuevos commits después de una aprobación, debe evaluarse si
afectan su superficie de revisión.

Los reviews deberían identificar, cuando sea posible, el commit o rango de
commits revisado.

Release Reviewer no debe asumir que una aprobación antigua sigue siendo
válida después de cambios relevantes.

---

## 8. Release Review

Release Reviewer es el último gate antes de integración o deployment.

Consolida las revisiones requeridas y verifica el estado final, incluyendo
cuando corresponda:

- build y tests;
- commits y estado del repositorio;
- compatibilidad frontend/backend;
- OpenAPI;
- migraciones;
- configuración requerida;
- variables de entorno;
- orden de despliegue;
- riesgos de compatibilidad;
- estrategia de rollback o recuperación.

Estados:

- `READY_FOR_RELEASE`
- `BLOCKED`

Release Reviewer no implementa correcciones ni realiza automáticamente merge,
push o deployment.

---

## 9. READY_FOR_RELEASE

Una funcionalidad puede alcanzar `READY_FOR_RELEASE` cuando:

- los reviews requeridos están aprobados y vigentes;
- build y pruebas obligatorias pasan;
- contratos son consistentes;
- migraciones requeridas son válidas;
- configuración necesaria está identificada;
- no existen bloqueadores conocidos;
- riesgos residuales relevantes están documentados.

Una validación obligatoria `NOT VERIFIED` debe evaluarse explícitamente antes
de aprobar release.

---

## 10. Integración

Después de `READY_FOR_RELEASE` puede realizarse integración.

Si merge, resolución de conflictos o cambios posteriores alteran
significativamente el código revisado:

- ejecutar nuevamente build y pruebas relevantes;
- revisar el diff integrado;
- repetir los reviews afectados.

Resolver un conflicto Git no demuestra por sí solo que la integración sea
correcta.

---

## 11. Validación funcional y DONE

Después de integrar, validar el flujo funcional relevante contra el PRD y la
spec.

Una funcionalidad alcanza `DONE` cuando:

```text
READY_FOR_DEV
+
IMPLEMENTATION COMPLETE
+
TECHNICAL REVIEW APPROVED
+
QA APPROVED
+
SECURITY APPROVED cuando corresponda
+
READY_FOR_RELEASE
+
INTEGRATION VERIFIED
+
FUNCTIONAL VALIDATION PASSED
=
DONE
```

Los roles no aplicables deben registrarse como `NOT APPLICABLE`, no como
`PASSED`.

---

## 12. Trabajo paralelo

Puede paralelizarse cuando existe independencia real.

Ejemplos:

```text
Backend DEV ───────┐
                   ├── Technical Review
Frontend DEV ──────┘
```

```text
QA ────────────────┐
                   ├── Release Review
Security ──────────┘
```

Coordinar antes de paralelizar cambios sobre:

- contratos API;
- modelos compartidos;
- migraciones;
- autenticación;
- configuración común;
- arquitectura.

Usar branches o Git worktrees cuando sea útil para evitar interferencias.

---

## 13. Escalamiento

### Volver a DEV

Cuando el problema sea de implementación.

### Volver a Architect

Cuando sea necesario cambiar:

- alcance;
- comportamiento especificado;
- contrato principal;
- arquitectura;
- modelo crítico;
- estrategia de persistencia;
- autenticación o autorización;
- infraestructura o tecnología principal.

### Volver a reviewer

Después de corregir un hallazgo dentro de su superficie de revisión.

---

## 14. Responsabilidades

| Rol | Responsabilidad principal |
|---|---|
| Architect | Convertir requisitos en specs implementables |
| Backend DEV | Implementar backend |
| Frontend DEV | Implementar frontend |
| Technical Reviewer | Validar calidad técnica |
| QA | Validar comportamiento y criterios de aceptación |
| Security & Privacy Reviewer | Validar seguridad, privacidad e IA |
| Release Reviewer | Determinar readiness para integración/deploy |

Ningún agente es juez final de su propio trabajo.

---

## 15. Definition of Done

Una funcionalidad termina cuando:

- la spec cumple Definition of Ready;
- el alcance requerido está implementado;
- criterios de aceptación están verificados;
- pruebas y build relevantes pasan;
- reviews requeridos están aprobados;
- no existen hallazgos bloqueantes;
- OpenAPI, migraciones, configuración y documentación están actualizados cuando
  corresponda;
- la integración fue verificada;
- la validación funcional final pasó;
- los riesgos residuales relevantes están documentados.

---

## Regla final

El workflow debe ser proporcional al cambio: evitar burocracia innecesaria,
pero nunca omitir controles aplicables.

Cada funcionalidad debe poder responder con evidencia:

1. ¿Qué requisito implementa?
2. ¿Qué spec lo define?
3. ¿Qué cambió?
4. ¿Qué pruebas lo demuestran?
5. ¿Qué reviews aplicaron y cuál fue su resultado?
6. ¿Está lista para release?
7. ¿Qué riesgos permanecen?

Si no existe evidencia suficiente, la funcionalidad todavía no está
terminada.
