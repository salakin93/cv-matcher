# CV Matcher

Aplicación para automatizar la preselección de candidatos mediante análisis de CVs.

## Documentación

- [Plan de desarrollo y requisitos del producto](docs/PRD-automatizacion-reclutamiento.md)

## Configuración local

1. Copia `.env.example` como `.env` y reemplaza sus placeholders. No subas
   `.env` al repositorio.
2. `JWT_SIGNING_KEY` debe tener al menos 32 bytes aleatorios.
3. `INITIAL_ADMIN_EMAIL` y `INITIAL_ADMIN_PASSWORD` crean el único administrador
   inicial en una base vacía. En producción ambas variables son obligatorias.

### Perfiles

- `local` es el perfil predeterminado, usa `NoopMailGateway` y permite cookies
  sin atributo `Secure` para `http://localhost`.
- `test` usa `NoopMailGateway`, datos aislados de prueba y cookies no seguras.
- `prod` exige variables de entorno para secretos, obliga cookies `Secure` y usa
  un gateway de correo explícitamente no configurado hasta la spec SMTP.
