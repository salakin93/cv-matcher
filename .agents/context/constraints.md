# Restricciones no negociables

## Privacidad y uso responsable

- No usar, inferir ni puntuar edad, género, fotografía, nacionalidad, religión, estado civil, discapacidad, embarazo u otros atributos protegidos.
- No registrar en logs texto de CV, evidencias completas, correos, teléfonos, tokens, claves ni datos sensibles.
- No incluir secretos en el repositorio. Usar variables de entorno o un gestor de secretos.
- El LLM recibe solamente requisitos del puesto y texto estrictamente necesario del CV.
- No implementar decisiones automáticas de contratar, rechazar o descartar candidatos.

## Arquitectura y calidad

- Mantener la separación de módulos definida en `docs/architecture.md`.
- Todo cambio de esquema requiere migración Flyway versionada y pruebas relevantes.
- Los endpoints usan `/api`, validación de entrada y el formato estándar de respuesta/error descrito en la arquitectura.
- Documentar endpoints implementados mediante OpenAPI.
- Las operaciones de procesamiento deben persistir su estado en PostgreSQL; no depender solamente de tareas en memoria.
- Agregar dependencias solo cuando estén justificadas por la spec o aprobadas explícitamente.

## Verificación mínima

- Ejecutar las pruebas relevantes y la compilación antes de declarar una tarea terminada.
- Usar Testcontainers para integración con PostgreSQL cuando la funcionalidad toque persistencia.
- Simular Graph y Anthropic en pruebas: nunca usar credenciales reales para pruebas automatizadas.
- Revisar que errores y auditoría no filtren información sensible.
