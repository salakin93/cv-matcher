# Contexto del proyecto: CV Matcher

## Propósito

CV Matcher es una aplicación interna de apoyo al proceso de reclutamiento.

Permite obtener CVs recibidos en Outlook, extraer evidencia relacionada con
los requisitos de un puesto, calcular un score determinista y mostrar un
ranking explicable para revisión humana.

El sistema ayuda al operador de reclutamiento, pero nunca toma decisiones
automáticas de contratación, rechazo o descarte de candidatos.

---

## Fuentes de verdad

- `docs/PRD.md`: alcance, comportamiento funcional, reglas de negocio y
  criterios de aceptación.
- `docs/architecture.md`: arquitectura, módulos, contratos técnicos,
  seguridad y operación.
- `docs/database.md`: modelo de datos y reglas de retención.
- `docs/microsoft-graph-setup.md`: integración con Microsoft Graph y Outlook.
- `docs/anthropic-setup.md`: integración con Anthropic Claude.
- `docs/deployment.md`: configuración, ambientes y despliegue.
- `.agents/context/constraints.md`: restricciones no negociables del proyecto.
- `.agents/context/workflow.md`: flujo de trabajo y gates entre agentes.

### Prioridad ante contradicciones

- Para comportamiento funcional prevalece `docs/PRD.md`.
- Para decisiones técnicas prevalece `docs/architecture.md`.
- `constraints.md` no puede ser contradicho por una spec o implementación.

Las contradicciones importantes no deben resolverse mediante suposiciones
silenciosas. Deben reportarse o escalarse al rol correspondiente.

---

## Estado técnico

### Backend

- Java 25
- Spring Boot
- ubicación: `cv-matcher-backend/`

### Datos

- PostgreSQL
- Flyway

### Integraciones previstas

- Microsoft Graph
- Anthropic Claude
- Apache PDFBox
- almacenamiento privado de documentos

### Frontend

- React
- TypeScript

La ubicación y herramienta de construcción del frontend deben verificarse en
el repositorio antes de implementar. No asumir Vite, Next.js, npm, pnpm,
yarn u otra herramienta sin evidencia.

---

## Principios del producto

1. La decisión final de selección siempre pertenece a una persona autorizada.
2. El LLM extrae o estructura evidencia; no decide contratación.
3. El backend calcula el score mediante reglas deterministas y reproducibles.
4. Las respuestas de servicios externos se consideran datos no confiables y
   deben validarse.
5. Los CVs, contactos, tokens OAuth, evidencias y resultados son
   confidenciales.
6. Los procesos que deban sobrevivir reinicios deben persistir su estado.
7. Los flujos susceptibles a repetición deben considerar idempotencia cuando
   corresponda.
8. Cada cambio debe poder trazarse desde requisito y spec hasta implementación
   y verificación.

---

## Flujo conceptual del producto

```text
Outlook
  ↓
Obtención y validación del CV
  ↓
Almacenamiento privado
  ↓
Extracción de información
  ↓
Evidencia estructurada
  ↓
Validación
  ↓
Score determinista
  ↓
Ranking explicable
  ↓
Revisión humana
```

Las specs determinan qué partes de este flujo se implementan en cada
funcionalidad.

---

## Regla para los agentes

Antes de trabajar sobre una funcionalidad, cada agente debe consultar:

1. este archivo;
2. `.agents/context/constraints.md`;
3. `.agents/context/workflow.md`;
4. la spec activa;
5. únicamente la documentación y código necesarios para su tarea.

No cargar documentación adicional sin una necesidad concreta.
