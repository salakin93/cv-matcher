# Contexto del proyecto: CV Matcher

## 1. Propósito

CV Matcher es una aplicación interna de apoyo al proceso de reclutamiento.

El sistema ayuda a un operador de reclutamiento a:

- obtener CVs recibidos mediante Outlook
- procesar documentos de candidatos
- extraer evidencia relacionada con los requisitos de un puesto
- calcular un score determinista
- generar un ranking explicable
- revisar manualmente los resultados
- exportar información permitida

CV Matcher es una herramienta de apoyo.

NO toma decisiones de contratación.

NO rechaza automáticamente candidatos.

NO descarta candidatos automáticamente.

La decisión final pertenece siempre a una persona autorizada.

---

# 2. Principio central del producto

El sistema debe separar claramente:

EXTRACCIÓN DE EVIDENCIA
↓
REGLAS DETERMINISTAS
↓
SCORE
↓
RANKING
↓
REVISIÓN HUMANA
↓
DECISIÓN HUMANA

La IA puede ayudar a interpretar y estructurar información.

La IA NO es la autoridad final sobre el score ni sobre decisiones de selección.

---

# 3. Fuentes de verdad

Las fuentes de verdad del proyecto son:

## Producto

`docs/PRD.md`

Define:

- alcance
- comportamiento funcional
- reglas de negocio
- actores
- funcionalidades
- criterios de aceptación

Para comportamiento funcional:

`docs/PRD.md` tiene prioridad.

---

## Arquitectura

`docs/architecture.md`

Define:

- arquitectura
- módulos
- responsabilidades
- contratos técnicos
- seguridad
- procesamiento
- observabilidad
- decisiones técnicas

Para decisiones técnicas:

`docs/architecture.md` tiene prioridad.

---

## Base de datos

`docs/database.md`

Define:

- modelo de datos
- relaciones
- constraints
- índices
- estados persistentes
- reglas de retención

---

## Microsoft Graph

`docs/microsoft-graph-setup.md`

Define:

- configuración
- OAuth
- permisos
- scopes
- integración con Outlook

---

## Anthropic

`docs/anthropic-setup.md`

Define:

- integración con Claude
- configuración
- datos enviados
- formato esperado de respuesta
- restricciones de uso

---

## Deployment

`docs/deployment.md`

Define:

- configuración por ambiente
- variables de entorno
- infraestructura
- despliegue
- operación

---

# 4. Resolución de contradicciones

Cuando existan contradicciones:

## Comportamiento funcional

Prevalece:

`docs/PRD.md`

## Decisiones técnicas

Prevalece:

`docs/architecture.md`

## Modelo persistente

Prevalece:

`docs/database.md`

siempre que no contradiga el PRD o una decisión arquitectónica posterior
explícitamente aprobada.

## Integraciones

Los documentos específicos de integración complementan la arquitectura,
pero no pueden contradecir:

- PRD
- architecture.md
- constraints.md

---

# 5. Regla ante ambigüedad

Los agentes NO deben resolver silenciosamente contradicciones importantes.

Cuando una contradicción pueda afectar:

- comportamiento
- contrato
- persistencia
- seguridad
- privacidad
- score
- autorización
- arquitectura

debe reportarse.

Clasificarla como una de:

- `OPEN QUESTION`
- `ASSUMPTION`
- `ARCHITECTURAL DECISION REQUIRED`
- `PRODUCT DECISION REQUIRED`
- `BLOCKER`

No convertir una suposición en requisito confirmado.

---

# 6. Estado técnico actual

## Backend

Tecnología:

- Java 25
- Spring Boot

Ubicación:

`cv-matcher-backend/`

---

## Base de datos

- PostgreSQL
- Flyway para migraciones

---

## Integraciones previstas

- Microsoft Graph
- Anthropic Claude
- Apache PDFBox
- almacenamiento privado de documentos

---

## Frontend

Tecnología prevista:

- React
- TypeScript

La ubicación exacta del proyecto frontend y su herramienta de construcción
deben confirmarse antes de comenzar la implementación frontend.

No asumir automáticamente:

- Vite
- Next.js
- CRA
- npm
- pnpm
- yarn

Revisar primero el repositorio real.

---

# 7. Dominio principal

Los conceptos principales del sistema incluyen, según lo definido por el PRD
y las specs:

- puesto
- requisitos del puesto
- candidato
- CV
- documento
- evidencia
- proceso o lote
- resultado de análisis
- score
- ranking
- revisión humana
- integración Outlook
- auditoría
- exportación

Los nombres concretos de entidades, tablas, endpoints y componentes deben
seguir la arquitectura y specs aprobadas.

Este contexto NO autoriza crear automáticamente todas estas entidades.

---

# 8. Flujo conceptual

El flujo general esperado es:

OUTLOOK
↓
OBTENCIÓN DEL CV
↓
VALIDACIÓN DEL DOCUMENTO
↓
ALMACENAMIENTO PRIVADO
↓
EXTRACCIÓN DE INFORMACIÓN
↓
IDENTIFICACIÓN DE EVIDENCIA
↓
VALIDACIÓN DE RESPUESTA EXTERNA
↓
CÁLCULO DETERMINISTA DEL SCORE
↓
RANKING
↓
REVISIÓN HUMANA
↓
EXPORTACIÓN CUANDO CORRESPONDA

Las specs concretas determinan qué partes del flujo se implementan.

---

# 9. Principios de producto

## 9.1 Human-in-the-loop

El humano revisa la evidencia y toma toda decisión relacionada con selección.

El sistema:

- ayuda
- organiza
- calcula
- explica

pero no sustituye al responsable de contratación.

---

## 9.2 Score determinista

El LLM puede devolver evidencia estructurada.

El backend calcula el score.

El cálculo debe ser:

- determinista
- reproducible
- testeable
- explicable
- versionable cuando corresponda

Una misma entrada y una misma versión de reglas deben producir el mismo
resultado.

---

## 9.3 IA como integración no confiable

Las respuestas del LLM deben tratarse como datos externos no confiables.

Antes de utilizarse deben validarse.

Una respuesta incorrecta, incompleta o inesperada no debe:

- modificar reglas de negocio
- cambiar autorización
- decidir contratación
- controlar directamente el score
- provocar éxito silencioso

---

# 10. Confidencialidad

Son confidenciales, entre otros:

- CVs
- contenido de CV
- información de candidatos
- contactos
- correos electrónicos
- información obtenida de Outlook
- tokens OAuth
- resultados de análisis
- evidencia
- rankings asociados a candidatos

Su acceso, almacenamiento, logging y transferencia deben respetar
`constraints.md`, el PRD y la arquitectura.

---

# 11. Procesamiento durable

Los procesos que necesiten sobrevivir reinicios deben persistir su estado.

No depender exclusivamente de:

- memoria del proceso
- threads locales
- estado temporal no persistente

Los lotes y procesos relevantes deben poder ser:

- identificados
- auditados
- recuperados
- reanudados o reconciliados
- protegidos frente a duplicados

según lo definido por la spec.

---

# 12. Idempotencia

Las operaciones que puedan repetirse debido a:

- retries
- reinicios
- callbacks
- reenvíos
- fallos temporales

deben analizar explícitamente el riesgo de duplicación.

Cuando corresponda, el diseño debe garantizar idempotencia.

No aplicar idempotencia mecánicamente a todas las operaciones.

---

# 13. Trazabilidad

Debe ser posible relacionar cuando corresponda:

REQUISITO
↓
SPEC
↓
IMPLEMENTACIÓN
↓
PRUEBA
↓
RESULTADO

Los reviewers deben utilizar esta trazabilidad para validar cada cambio.

---

# 14. Auditoría

Las operaciones relevantes deben ser auditables según PRD y arquitectura.

La auditoría debe priorizar:

- quién realizó la operación
- qué operación se realizó
- cuándo ocurrió
- sobre qué recurso técnico
- resultado de la operación

La auditoría no debe convertirse en una copia de información sensible.

---

# 15. Seguridad por defecto

El sistema debe seguir:

- mínimo privilegio
- deny by default
- validación de entradas
- separación de responsabilidades
- minimización de datos
- secretos fuera del repositorio
- autorización en backend
- protección de información sensible

El frontend no constituye una frontera de seguridad.

---

# 16. Integraciones externas

Microsoft Graph y Anthropic son dependencias externas.

El sistema debe asumir que pueden:

- fallar
- responder lentamente
- devolver información inesperada
- devolver respuestas incompletas
- quedar temporalmente indisponibles

Toda integración debe considerar cuando corresponda:

- timeout
- validación
- manejo de errores
- retry limitado
- recuperación
- observabilidad

---

# 17. Testing

Las pruebas automatizadas no deben depender de servicios externos reales.

Microsoft Graph y Anthropic deben simularse mediante mecanismos apropiados.

La persistencia debe probarse contra PostgreSQL real mediante Testcontainers
cuando corresponda.

---

# 18. Flujo de agentes

El flujo de desarrollo esperado es:

PRD
↓
ARCHITECT
↓
SPEC
↓
BACKEND DEV / FRONTEND DEV
↓
TECHNICAL REVIEW
↓
QA + SECURITY & PRIVACY REVIEW
↓
RELEASE REVIEW
↓
READY FOR RELEASE

Cada rol mantiene su responsabilidad.

Un reviewer no corrige el código que está revisando.

Un DEV no aprueba su propia implementación.

---

# 19. Estados de revisión

Los reviewers utilizarán estados explícitos.

Technical Review:

- `APROBADO`
- `CAMBIOS_REQUERIDOS`

QA:

- `APROBADO`
- `CAMBIOS_REQUERIDOS`

Security & Privacy:

- `APROBADO`
- `CAMBIOS_REQUERIDOS`

Release Review:

- `READY_FOR_RELEASE`
- `BLOCKED`

No considerar una tarea lista únicamente porque DEV terminó de implementar.

---

# 20. Principio final

CV Matcher ayuda a personas a tomar decisiones mejor informadas.

No toma decisiones por ellas.

La IA extrae o estructura evidencia.

El backend conserva las reglas deterministas.

Los datos se minimizan y protegen.

Los procesos importantes son recuperables y auditables.

Cada cambio debe ser trazable desde el requisito hasta su verificación.