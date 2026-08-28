# 002 - Perímetro seguro de producción

## Decisión

El despliegue integrado definido en `compose.yaml` expone únicamente el proxy Caddy en los puertos 80 y 443. El backend y PostgreSQL permanecen accesibles solo en la red interna de Docker.

Caddy termina TLS y reenvía las solicitudes al servicio `backend`. La variable `APP_DOMAIN` debe contener el dominio público que resuelve a la máquina de despliegue. Caddy obtiene y renueva los certificados automáticamente.

## Desarrollo local

Para desarrollo, PostgreSQL se publica mediante `compose.dev.yaml` en `localhost:5430`; el backend se ejecuta localmente con Gradle. No se debe usar `compose.dev.yaml` como configuración de despliegue.

## Consecuencias

- Los contenedores de aplicación se ejecutan con un usuario sin privilegios.
- OpenAPI y Swagger se deshabilitan en el perfil `prod`.
- La publicación pública del backend sin TLS no está permitida.
