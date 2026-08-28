# Contexto del proyecto: CV Matcher

## Propósito

CV Matcher ayuda a un operador interno de reclutamiento a preseleccionar CVs recibidos en Outlook. El sistema extrae evidencia por requisito y muestra un ranking explicable; nunca toma decisiones de contratación.

## Fuentes de verdad

- `docs/PRD.md`: alcance, reglas de negocio y criterios de aceptación.
- `docs/architecture.md`: componentes, API, seguridad y operación.
- `docs/database.md`: modelo de datos y reglas de retención.
- `docs/microsoft-graph-setup.md`, `docs/anthropic-setup.md` y `docs/deployment.md`: integraciones y despliegue.

Si hay contradicción, el PRD prevalece para comportamiento funcional y `architecture.md` para decisiones técnicas.

## Estado técnico actual

- Backend: Java 25 y Spring Boot, dentro de `cv-matcher-backend/`.
- Datos: PostgreSQL con Flyway.
- Integraciones previstas: Microsoft Graph, Anthropic Claude, PDFBox y almacenamiento privado.
- Frontend previsto: React + TypeScript; aún debe confirmarse su ubicación y herramienta de construcción antes de implementarlo.

## Principios de producto

1. El humano revisa la evidencia y toma toda decisión de selección.
2. El LLM devuelve evidencia estructurada; el backend calcula el score de forma determinista y versionada.
3. Los CVs, contactos, tokens OAuth y resultados son confidenciales.
4. Los lotes deben ser idempotentes, recuperables tras reinicio y auditables.
