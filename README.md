# CV Matcher

## Requisitos

- JDK 25.
- Docker y Docker Compose.

## Desarrollo local

1. Copia `.env.example` como `.env` y completa una contraseña local. No subas este archivo al repositorio.
2. Inicia PostgreSQL:

   ```powershell
   docker compose -f compose.yaml -f compose.dev.yaml up db
   ```

3. Exporta la misma contraseña para el backend y ejecútalo:

   ```powershell
   $env:DATABASE_PASSWORD = "tu-contraseña-local"
   .\gradlew.bat bootRun
   ```

   Ejecuta el segundo comando desde `cv-matcher-backend/`.

4. Verifica el servicio en `http://localhost:8080/actuator/health` y la documentación OpenAPI en `http://localhost:8080/swagger-ui.html`.

## Pruebas

Desde `cv-matcher-backend/`:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Las pruebas de integración usan Testcontainers y requieren Docker en ejecución.

## Despliegue integrado

El archivo raíz `compose.yaml` levanta la base de datos y el backend sin publicar PostgreSQL al host:

```powershell
docker compose up --build
```

Cuando exista el módulo frontend, se añadirá al mismo archivo para que el despliegue completo se inicie con este único comando.
