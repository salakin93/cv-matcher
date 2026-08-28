# 001 — Fundaciones y calidad operativa

## Problema

El backend inicial existe, pero el proyecto necesita una base local, repetible y segura antes de añadir reglas de negocio e integraciones que manejarán CVs y credenciales OAuth.

## Objetivo

Dejar una base ejecutable del backend con PostgreSQL, Flyway, configuración por entorno, salud, errores estandarizados, OpenAPI y observabilidad sin datos sensibles.

## Alcance

### Incluye

- Perfiles de configuración local, staging y producción sin secretos en Git.
- PostgreSQL para desarrollo local y migración Flyway inicial.
- Endpoint de salud mediante Actuator.
- Formato estándar de respuestas y manejador global de errores.
- Validación Bean Validation, paginación base y `correlationId`.
- OpenAPI/Swagger para endpoints implementados.
- Logs estructurados que excluyan CVs, PII, tokens y claves.
- Pruebas de contexto y de integración con PostgreSQL mediante Testcontainers cuando aplique.
- Documentación de arranque, pruebas y variables necesarias en `README.md`.

### No incluye

- OAuth de Microsoft Graph.
- Lógica de búsquedas, carga de CVs, LLM, worker o frontend.
- Secretos reales, servicios cloud o despliegue productivo.

## Diseño y decisiones

- El código vive en `cv-matcher-backend/`.
- Java 25 es el estándar para desarrollo, CI y producción; Spring Boot 4.1.1 se mantiene como la versión base actual.
- La configuración migra de `application.properties` a `application.yml`, con archivos sin secretos por perfil: `application-local.yml`, `application-test.yml` y `application-prod.yml`.
- Desarrollo local: PostgreSQL se ejecuta con Docker Compose y el backend se inicia mediante Gradle. Las credenciales locales se cargan desde un archivo local ignorado por Git o variables de entorno.
- Despliegue: un único `compose.yaml` debe definir los servicios `db`, `backend` y `frontend`, además de cualquier servicio de soporte que se apruebe posteriormente. Mientras no exista el frontend, se implementan y validan `db` y `backend`; el servicio frontend se añade en el mismo archivo al crear dicho módulo.
- El entorno local se inicia con el servicio de base de datos explícitamente (`docker compose up db`); el despliegue integrado usa el mismo archivo para levantar todos los servicios construidos.
- PostgreSQL usa el puerto local `5430`. El nombre de base de datos y las credenciales locales proporcionadas por el equipo no se versionan ni se muestran en documentación pública; se documentan mediante `.env.example` sin valores sensibles.
- La migración inicial será una migración Flyway real para la tabla transversal `audit_event`, conforme a `docs/database.md`. No crea todavía tablas de búsquedas, CVs, candidatos ni integraciones.
- Las migraciones usan Flyway y nunca se edita una migración ya aplicada en un entorno compartido.
- La información de correlación usa el encabezado `X-Correlation-Id`: se reutiliza si es válido o se genera, se devuelve en la respuesta y se incluye en el contexto de logs sin datos sensibles.
- Actuator expone `health` e `info`; en producción no se exponen detalles de salud ni endpoints de administración adicionales sin aprobación.
- Las respuestas exitosas usan DTOs propios de cada endpoint y códigos HTTP acordes a la operación. El manejador global normaliza los errores con `status`, `code`, `message`, `timestamp`, `method`, `requestUri` y `correlationId`. Un error de validación devuelve HTTP 400 y código `VALIDATION_ERROR`.
- La prueba de una validación HTTP real se incorpora junto al primer endpoint funcional. En esta spec se prueban unitariamente las traducciones de excepciones del manejador global.

## Criterios de aceptación

- [ ] Un desarrollador puede iniciar PostgreSQL y el backend usando solo el README.
- [ ] Flyway crea el esquema inicial sin errores en una base vacía.
- [ ] El endpoint de salud responde correctamente.
- [ ] Un error de validación devuelve el formato estándar y no filtra detalles internos.
- [ ] OpenAPI describe los endpoints implementados.
- [ ] Las pruebas relevantes pasan sin credenciales externas.
- [ ] Los logs y archivos versionados no contienen secretos ni contenido de CV.
- [ ] El perfil `test` permite ejecutar pruebas sin credenciales ni llamadas externas.
- [ ] `X-Correlation-Id` se propaga o genera correctamente y no queda en el contexto de logs después de terminar la solicitud.

## Pruebas requeridas

- Unitarias del formato de error y validación.
- Integración de Flyway y persistencia con Testcontainers PostgreSQL.
- Prueba de humo del endpoint de salud.

## Riesgos y decisiones pendientes

- Confirmar la versión final de Java y Spring Boot antes de fijar convenciones de producción.
- Definir la herramienta de Docker Compose y los nombres de variables de entorno antes de documentar el entorno local.
