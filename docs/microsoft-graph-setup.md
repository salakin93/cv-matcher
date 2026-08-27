# Configuración de Microsoft Graph / Outlook

Guía de configuración para conectar el sistema al Inbox del operador vía Microsoft Graph. Contexto funcional en [PRD.md](./PRD.md) sección 4 y RF-02/RF-03.

---

## 1. Requisito previo (dependencia externa)

El dominio de correo es `@ceare.com.bo` (Microsoft 365). El operador **no tiene acceso de administrador** a Entra ID, por lo que se necesita una de estas dos rutas:

- **Ruta A (recomendada):** el registro de la app y el consentimiento del permiso `Mail.Read` los da el propio operador con su cuenta normal, si el tenant permite "consentimiento de usuario" para permisos delegados (configuración por defecto en muchos tenants). No requiere admin.
- **Ruta B (si el tenant bloquea consentimiento de usuario):** un administrador de Entra ID debe: registrar la aplicación, configurar el redirect URI, y otorgar consentimiento administrador para `User.Read`, `Mail.Read`, `offline_access`. Esto es una tarea puntual de unos minutos, no acceso continuo al sistema.

Antes de empezar el desarrollo del módulo de integración, confirmar cuál ruta aplica intentando el registro con la cuenta normal del operador.

---

## 2. Registro de la aplicación en Entra ID

1. Ir a [portal.azure.com](https://portal.azure.com) → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Nombre sugerido: `cv-matcher` (o el nombre final del repositorio).
3. Tipo de cuenta compatible: **Accounts in this organizational directory only** (single-tenant, ya que solo se usa dentro de `@ceare.com.bo`).
4. Redirect URI: tipo **Web**, uno por entorno:
   - Local: `http://localhost:8080/login/oauth2/code/microsoft`
   - Staging: `https://staging.<dominio-elegido>/login/oauth2/code/microsoft`
   - Producción: `https://<dominio-elegido>/login/oauth2/code/microsoft`
5. Guardar el **Application (client) ID** y el **Directory (tenant) ID** — se usan como variables de entorno (ver [deployment.md](./deployment.md)).
6. En **Certificates & secrets**, crear un **Client secret** con expiración (recomendado 12 meses) y guardarlo de forma segura — nunca en el repositorio.

---

## 3. Permisos delegados

En **API permissions** → **Add a permission** → **Microsoft Graph** → **Delegated permissions**, agregar:

| Permiso | Uso |
|---|---|
| `User.Read` | Identificar la cuenta conectada. |
| `Mail.Read` | Leer correos del Inbox del usuario conectado (solo lectura, sin subcarpetas). |
| `offline_access` | Obtener refresh token para no requerir login en cada sesión. |

Si el tenant lo exige, solicitar "Grant admin consent" (requiere Ruta B).

**Nunca solicitar permisos de aplicación (application permissions)** como `Mail.Read` a nivel de aplicación — eso daría acceso a todos los buzones del tenant, muy por encima de lo necesario (principio de mínimo privilegio, ver PRD principio 4).

---

## 4. Flujo OAuth2 (Authorization Code + PKCE)

Implementado con Spring Security OAuth2 Client:

1. El operador presiona "Conectar Outlook" en el frontend.
2. El backend redirige a Microsoft (`https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/authorize`) con PKCE.
3. El operador inicia sesión y consiente los permisos.
4. Microsoft redirige al `redirect_uri` configurado con un `authorization_code`.
5. El backend intercambia el código por `access_token` + `refresh_token`.
6. Ambos tokens se cifran (clave externa a la base de datos, ver architecture.md sección 5) y se guardan en `outlook_connection`.
7. El backend refresca automáticamente el `access_token` usando el `refresh_token` antes de que expire.
8. Si el `refresh_token` es revocado (por el usuario o por IT), la próxima llamada a Graph devuelve `401`; el sistema marca la conexión como inválida y la UI pide reconectar, sin perder resultados ya guardados.

---

## 5. Consultas a Graph API

### Listar correos con adjuntos en un rango de fechas

```
GET https://graph.microsoft.com/v1.0/me/mailFolders/Inbox/messages
    ?$filter=receivedDateTime ge {dateFrom}T00:00:00 and receivedDateTime le {dateTo}T23:59:59 and hasAttachments eq true
    &$orderby=receivedDateTime asc
    &$select=id,subject,receivedDateTime,from
    &$top=50
```

- Orden `asc` para procesar los correos más antiguos del rango primero (ver PRD sección 4, corte de 150 CVs).
- Paginar siguiendo `@odata.nextLink` hasta agotar el rango o alcanzar 150 CVs procesables (lo que ocurra primero).
- Zona horaria: convertir el rango ingresado (`America/La_Paz`) a UTC antes de construir el filtro, ya que Graph trabaja en UTC.

### Descargar adjuntos de un mensaje

```
GET https://graph.microsoft.com/v1.0/me/messages/{message-id}/attachments
```

- Filtrar por `contentType` PDF y validar además extensión y firma `%PDF-` antes de aceptar.
- Descargar el contenido (`contentBytes` en base64 para adjuntos pequeños, o `$value` para adjuntos grandes) y calcular SHA-256 antes de guardar.

---

## 6. Límites y manejo de errores

| Situación | Manejo |
|---|---|
| `429 Too Many Requests` | Respetar el header `Retry-After`; el worker reintenta con backoff. |
| `401 Unauthorized` | Token inválido/revocado → marcar conexión como requerida de reconexión (`OUTLOOK_RECONNECTION_REQUIRED`), sin perder el progreso ya guardado del lote. |
| Timeout de red al descargar adjunto | Reintentar hasta 3 veces con espera progresiva antes de marcar `ERROR_DESCARGA`. |
| Adjunto no PDF o que excede 10 MB | Ignorar con motivo registrado, sin llamar a Graph adicionalmente. |
| Más de 20 adjuntos en un correo | Procesar solo los primeros 20 según el orden devuelto por Graph; registrar el resto como ignorado por límite. |

---

## 7. Variables de entorno relacionadas

Ver catálogo completo en [deployment.md](./deployment.md). Relevantes a este módulo:

```
MICROSOFT_TENANT_ID=
MICROSOFT_CLIENT_ID=
MICROSOFT_CLIENT_SECRET=
MICROSOFT_REDIRECT_URI=
TOKEN_ENCRYPTION_KEY=
```

`TOKEN_ENCRYPTION_KEY` es la clave usada para cifrar `token_encrypted` y `refresh_token_encrypted` en la tabla `outlook_connection` (ver [database.md](./database.md)); debe vivir fuera de la base de datos (gestor de secretos o variable de entorno del backend, nunca en el mismo lugar que el backup de la BD).
