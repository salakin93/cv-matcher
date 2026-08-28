# Decisión 001 — Runtime, entornos y contrato HTTP

**Estado:** Aprobada  
**Alcance:** Fundaciones del backend

Esta decisión complementa `architecture.md` y prevalece sobre su ejemplo anterior de envoltorio universal de respuesta.

## Runtime y configuración

- Java 25 es el estándar de desarrollo, CI y producción.
- La aplicación usa `application.yml` con perfiles `local`, `test` y `prod`.
- Los valores reales de conexión y demás secretos se inyectan mediante variables de entorno o archivos ignorados por Git.
- PostgreSQL local expone el puerto `5430`.

## Docker Compose

- El repositorio tendrá un único `compose.yaml` como contrato de despliegue integrado.
- El archivo contendrá `db`, `backend` y `frontend` cuando exista el módulo frontend, y podrá añadir servicios de soporte aprobados.
- Durante desarrollo, se inicia solo PostgreSQL con `docker compose -f compose.yaml -f compose.dev.yaml up db`; el backend se ejecuta localmente mediante Gradle.
- El servicio `db` no publica puertos en el Compose de despliegue. `compose.dev.yaml` agrega el puerto `5430` exclusivamente para desarrollo local.

## Base de datos y observabilidad

- La primera migración Flyway crea la tabla transversal `audit_event` definida en `database.md`; las entidades de negocio se incorporan en sus propias specs.
- Las solicitudes reutilizan o generan `X-Correlation-Id`, lo devuelven en la respuesta y lo incluyen de forma segura en los logs.
- Actuator expone únicamente `health` e `info` por defecto; producción no revela detalles de salud.
- CSRF permanece activo por defecto para proteger futuras sesiones OAuth basadas en cookies. Cualquier exclusión debe limitarse a rutas concretas y documentarse junto con su mecanismo de autenticación.

## Respuestas HTTP

- Las respuestas exitosas usan DTOs propios de cada endpoint y códigos HTTP acordes a la operación.
- Los errores se manejan con `GlobalExceptionHandler` y tienen esta forma:

```json
{
  "status": 502,
  "code": "EXTERNAL_SERVICE_ERROR",
  "message": "External service is unavailable",
  "timestamp": "2026-08-10T15:00:00Z",
  "method": "POST",
  "requestUri": "/api/...",
  "correlationId": "..."
}
```

- Nunca se incluyen secretos, tokens, stack traces ni respuestas completas de sistemas externos.
- Las tareas programadas capturan y registran sus errores para que un fallo no impida ejecuciones posteriores.
