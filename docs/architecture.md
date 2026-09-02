# Arquitectura inicial — CV Matcher

**Estado:** `APROBADO_PARA_SPECS`  
**Fecha:** 2026-09-02  
**Fuentes funcionales:** `docs/PRD.md`, `docs/PRODUCT_BACKLOG.md`, `.agents/context/*`

## 1. Objetivo y principios

La plataforma es una aplicación web para reclutadores que ingiere CVs desde un Inbox Outlook compartido, los analiza con Claude y presenta reportes explicables. La decisión de contratación es siempre humana.

Principios obligatorios:

- modular monolith antes de introducir servicios distribuidos;
- API backend como único límite de confianza para navegador, Microsoft Graph y Claude;
- reglas de score y ranking deterministas en backend;
- mínimo privilegio, minimización de datos y secretos fuera del repositorio;
- procesos durables e idempotentes para operaciones largas;
- contratos versionados y pruebas automatizadas antes de integrar.

## 2. Topología

```text
Navegador
  └─ React + TypeScript (SPA)
       └─ HTTPS /api
            └─ Spring Boot modular monolith
                 ├─ PostgreSQL
                 ├─ almacenamiento privado local de CVs
                 ├─ SMTP configurado para correos de producto
                 ├─ Microsoft Entra ID / Microsoft Graph
                 └─ Anthropic Claude API
```

En despliegue, un proxy inverso termina TLS y publica sólo frontend/API. PostgreSQL, el almacenamiento de documentos y servicios auxiliares permanecen en red privada. No se expone la base de datos al host en el Compose de despliegue.

## 3. Plataformas y herramientas

| Área | Decisión |
| --- | --- |
| Backend | Java 25, Spring Boot 3.5.x, Gradle 8.4+ y Spring MVC. La versión patch se actualiza antes de cada incremento. |
| Frontend | React 19, TypeScript estricto y Vite. Diseño responsivo: escritorio prioritario, móvil funcional. |
| Base de datos | PostgreSQL 17. JPA/Hibernate sólo detrás de módulos de aplicación; Flyway es la única vía de cambios de esquema. |
| API | REST JSON bajo `/api/v1`, OpenAPI generado desde backend. |
| Documentos | Sistema de archivos privado montado en el backend, cifrado AES-256-GCM por archivo. |
| Contenedores | Docker Compose. Desarrollo: PostgreSQL en Docker y backend/frontend locales. Despliegue: frontend, backend, PostgreSQL, proxy y servicios requeridos en Compose. |
| Observabilidad | logs JSON estructurados, correlation ID generado por servidor, Spring Boot Actuator protegido, métricas y health checks. |

Spring Boot 3.5 admite Java 25 oficialmente; la aplicación debe mantener esa combinación y no usar una versión de Boot anterior incompatible. [Requisitos de Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)

## 4. Límites de módulos backend

Cada módulo expone casos de uso y DTOs; controllers, repositorios y adaptadores no cruzan módulos directamente.

| Módulo | Responsabilidad |
| --- | --- |
| `identity` | registro, verificación, inicio/cierre de sesión, recuperación, tokens, sesiones, bloqueo y perfil. |
| `administration` | usuarios, roles, parámetros, estado de integraciones y operaciones administrativas. |
| `audit` | eventos inmutables y consulta exclusiva de administradores. |
| `vacancy` | vacantes, requisitos, archivo/reactivación y configuración del reporte. |
| `reporting` | versiones de reporte, ranking, estados humanos, filtros y exportaciones. |
| `job` | cola durable, estados, claim, recuperación, reintentos y notificaciones de trabajo. |
| `outlook` | OAuth server-side y adaptador Microsoft Graph. |
| `document` | descarga, validación, antimalware, cifrado, extracción de texto, hash y ciclo de vida de archivos. |
| `candidate` | perfil compartido, identidad/deduplicación, disponibilidad, correcciones y búsqueda histórica. |
| `analysis` | contrato Claude, validación de respuesta, evidencia y cálculo determinista de score. |
| `notification` | notificaciones in-app y correo; no contiene reglas de negocio del trabajo. |
| `shared` | errores, seguridad, serialización, reloj, identificadores y configuración transversal. |

Los módulos se organizan por caso de uso, no por carpetas globales `controller/service/repository`. Cada módulo puede tener `api`, `application`, `domain` e `infrastructure` cuando aporte claridad.

## 5. Seguridad y autorización

### 5.1 Identidad de producto

- Contraseñas con Argon2id; nunca reversibles.
- Access token JWT firmado, de 15 minutos, enviado como `Authorization: Bearer` y mantenido en memoria por la SPA.
- Sesión máxima de ocho horas mediante refresh token opaco, rotativo, almacenado sólo como hash en PostgreSQL y entregado en cookie `HttpOnly`, `Secure`, `SameSite=Lax`.
- El endpoint que usa cookie aplica CSRF. Las APIs de negocio usan bearer token y validación de roles en backend.
- Registro, verificación de correo, restablecimiento y cambio de correo usan tokens aleatorios de un solo uso, con hash, expiración y consumo atómico.
- Cinco intentos fallidos activan el bloqueo temporal definido en el PRD. Desactivar, cambiar rol o cambiar correo invalida sesiones.
- Autorización por recurso en backend evita IDOR: un usuario no obtiene documentos, reportes, exportaciones o administración sólo por conocer un ID.

### 5.2 Roles

- `RECRUITER`: operaciones compartidas de vacantes, reportes, candidatos y papelera.
- `ADMIN`: añade administración, auditoría, configuración e integración Microsoft.
- La primera cuenta administrativa se aprovisiona con `INITIAL_ADMIN_EMAIL` y `INITIAL_ADMIN_PASSWORD` sólo durante bootstrap; se obliga cambio de contraseña y se invalida/elimina el secreto de bootstrap tras uso.

### 5.3 Secretos y datos personales

- Secretos se inyectan por variables de entorno o secret manager del entorno; `.env` sólo local y nunca versionado.
- Logs, errores, auditoría y telemetría no contienen CVs, texto extraído, correos, tokens, claves, prompts o respuestas completas de proveedores.
- El correlation ID se genera en servidor como UUID; un valor externo no se registra como identificador confiable.
- Los documentos y texto extraído se cifran con AES-256-GCM. La clave maestra `CV_DOCUMENT_ENCRYPTION_KEY` se gestiona fuera de DB y se rota mediante proceso documentado de re-cifrado.

## 6. Persistencia y archivos

### 6.1 PostgreSQL y Flyway

Entidades principales iniciales: `user_account`, `user_session`, `email_verification`, `password_reset`, `vacancy`, `vacancy_requirement`, `matching_job`, `matching_job_event`, `report_version`, `candidate_profile`, `candidate_document`, `report_candidate`, `requirement_assessment`, `notification` y `audit_event`.

- Todas las tablas de negocio tienen UUID, timestamps UTC y versionado optimista cuando corresponda.
- Flyway usa migraciones inmutables `V<number>__description.sql`; no se edita una migración aplicada.
- Índices y constraints se definen junto con cada invariante: unicidad de correo normalizado, una ejecución activa por vacante, idempotencia de mensajes/adjuntos Graph y hashes de documento.
- Las eliminaciones de CV son lógicas hasta su purga; la eliminación por privacidad elimina datos personales y archivos de forma inmediata, dejando sólo auditoría mínima no identificable.

### 6.2 Almacenamiento de CV

- Ruta raíz configurable y privada, fuera de rutas estáticas y del directorio público del frontend.
- Nombre físico opaco basado en UUID; nombre original sólo como metadato protegido.
- Se guarda hash SHA-256, tamaño, MIME validado, estado de escaneo, referencia de cifrado y ruta relativa; nunca una ruta absoluta controlada por usuario.
- PDF y DOCX se validan por tamaño, tipo real y parser seguro antes de extraer texto. ClamAV es obligatorio en producción; el servicio no analiza ni persiste como disponible un archivo no escaneado.
- El texto extraído recibe los mismos controles de cifrado y acceso que el original.

## 7. Trabajos asíncronos y transacciones

`matching_job` es la fuente de verdad durable; no se usan tareas en memoria como garantía de ejecución.

Estados mínimos: `QUEUED`, `DISCOVERING`, `INGESTING_DOCUMENTS`, `ANALYZING`, `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`, `REAUTHORIZATION_REQUIRED`, `CANCELLED`.

- Crear o reintentar un reporte persiste el job en una transacción breve y lo despacha sólo después de commit.
- Un worker reclama un job mediante claim/lease transaccional en PostgreSQL. El lease vence y permite recuperación segura tras reinicio; dos instancias no procesan el mismo job.
- Las llamadas Graph, ClamAV, extracción y Claude se realizan fuera de transacciones de base de datos. Persistencia de estados, contadores, checkpoint y resultado se hace en transacciones breves.
- Checkpoints no contienen CV, texto, tokens ni PII innecesaria. Reintentos/replay no duplican mensajes, adjuntos, documentos, candidatos ni counters.
- Los retries son acotados y respetan `Retry-After`; errores de autorización Microsoft llevan a `REAUTHORIZATION_REQUIRED` sin reintentos automáticos.
- Cada transición importante genera evento mínimo y notificación final al solicitante por aplicación y correo.

## 8. Integraciones externas

### 8.1 Microsoft Graph

- Una sola conexión Outlook compartida, administrada por `ADMIN`.
- OAuth 2.0 Authorization Code + PKCE en backend confidencial, con callback backend, `offline_access`, y refresh token cifrado en DB. El refresh token rotado se reemplaza atómicamente y nunca llega al navegador.
- Permiso delegado mínimo `Mail.Read`; no se solicitan asunto, cuerpo, remitente ni propiedades ajenas al propósito salvo que una spec aprobada lo justifique. Graph permite listar mensajes de una carpeta con permisos `Mail.ReadBasic` o mayores; la descarga de adjuntos requiere validar el permiso mínimo exacto en la spec de integración. [Microsoft Graph: listar mensajes](https://learn.microsoft.com/en-us/graph/api/mailfolder-list-messages?view=graph-rest-1.0)
- Se consulta sólo Inbox, por rango UTC inclusivo, paginado, con campos mínimos y el header `Prefer: IdType="ImmutableId"` en cada petición relevante. Microsoft exige el header en cada solicitud para usar IDs inmutables de forma consistente. [IDs inmutables de Outlook](https://learn.microsoft.com/en-us/graph/outlook-immutable-id)
- Timeouts explícitos, máximo tres reintentos para errores transitorios, respeto de `429 Retry-After`, límites de mensajes, adjuntos y bytes definidos por spec.
- La expiración, revocación o falta de consentimiento exige reconexión por administrador. Los refresh tokens deben protegerse y el anterior debe descartarse al obtener uno nuevo. [Refresh tokens de Microsoft](https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens)

### 8.2 Claude

- El backend es el único cliente de Anthropic. La clave se obtiene desde `ANTHROPIC_API_KEY` del entorno.
- Modelo inicial configurable: `claude-sonnet-5`; cada reporte guarda el identificador exacto del modelo utilizado.
- Se envía únicamente texto extraído necesario y requisitos de la vacante. No se incluyen tokens, secretos, rutas, headers, contenido del email ni metadatos no necesarios.
- La respuesta debe ajustarse a un esquema JSON estricto por requisito. Se valida tamaño, campos, rangos 0–100, identificadores de requisito y evidencia antes de persistirla.
- El texto del CV es contenido no confiable: no puede modificar instrucciones del sistema, ejecutar acciones, cambiar requisitos ni decidir contratación, score total o ranking.

### 8.3 Correo de producto

- Se usa un adaptador `MailGateway` vía SMTP con TLS. Proveedor, dominio remitente y credenciales se configuran por entorno.
- Correos de verificación, recuperación y finalización de job no llevan CV, score detallado, enlaces públicos ni información sensible; dirigen al usuario autenticado a la aplicación.

## 9. API, errores y frontend

- OpenAPI es el contrato público de la SPA; el frontend no inventa endpoints ni tipos.
- Éxitos devuelven el DTO del recurso u operación. Creación de trabajos asíncronos responde `202 Accepted` con `jobId`, `reportId/version` cuando exista y URL de estado.
- Errores usan JSON uniforme:

```json
{
  "status": 403,
  "message": "No tiene permisos para realizar esta operación.",
  "timestamp": "2026-09-02T12:00:00Z",
  "path": "/api/v1/...",
  "correlationId": "uuid"
}
```

- Mensajes públicos son españoles y seguros; no incluyen stack traces, SQL, tokens, rutas de archivos, cuerpos Graph/Claude ni PII de terceros.
- `401` y `403` se normalizan también en Spring Security, no sólo en el manejador global.
- La SPA muestra estados loading, empty, warning, retry y error definidos por API. No recalcula scores ni envía solicitudes a proveedores.

## 10. Configuración y ambientes

Perfiles permitidos: `local`, `test`, `prod`. La configuración usa `application.yml` y variables de entorno.

| Grupo | Variables representativas |
| --- | --- |
| Base de datos | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| Seguridad | `JWT_SIGNING_KEY`, `APP_BASE_URL`, `INITIAL_ADMIN_EMAIL`, `INITIAL_ADMIN_PASSWORD` |
| Documentos | `CV_STORAGE_ROOT`, `CV_DOCUMENT_ENCRYPTION_KEY`, límites de tamaño |
| Microsoft | `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET`, `MICROSOFT_TENANT_ID`, `MICROSOFT_REDIRECT_URI` |
| Claude | `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` |
| Correo | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` |

- `local`: PostgreSQL en Docker; backend y frontend se ejecutan localmente. Se puede usar SMTP de prueba y dobles para Graph/Claude.
- `test`: base aislada, Testcontainers para PostgreSQL y dobles de toda integración externa; no secretos reales.
- `prod`: todas las variables obligatorias se inyectan desde secret manager/CI; TLS, almacenamiento persistente, backup PostgreSQL y ClamAV son requisitos operativos.

`.env.example` documenta sólo nombres y valores ficticios. El Compose de despliegue no publica PostgreSQL; un override de desarrollo puede publicar `5430:5432` para acceso local.

## 11. Observabilidad y recuperación

- Logs estructurados con nivel, evento técnico, jobId cuando exista y correlationId; sin PII ni secretos.
- Actuator ofrece health/liveness/readiness y métricas sólo en red privada o con autorización administrativa.
- Métricas iniciales: jobs por estado/duración, reintentos, documentos ignorados, fallos Graph/Claude/antimalware, backlog y purgas de papelera.
- Alertas operativas: conexión Microsoft reautorización requerida, jobs fallidos, backlog anómalo, fallos de almacenamiento, clave de cifrado inválida y purga fallida.
- Al iniciar, workers recuperan únicamente jobs con lease vencido o `QUEUED`; nunca liberan claims activos de otra instancia.

## 12. Estrategia de despliegue y recuperación

1. Validar configuración y secretos en el entorno de destino.
2. Crear backup verificable de PostgreSQL y comprobar volumen de documentos.
3. Aplicar migraciones Flyway al iniciar backend de forma controlada.
4. Desplegar backend, frontend y workers con la misma versión de API compatible.
5. Ejecutar health checks y confirmar workers, Graph/Claude y correo configurados.
6. Observar métricas y logs seguros; usar forward-fix o recuperación de backup para migraciones no reversibles.

No se declara rollback automático de datos. Cada spec con migraciones debe documentar compatibilidad y estrategia de recuperación.

## 13. Decisiones y riesgos

| Tipo | Tema | Decisión / tratamiento |
| --- | --- | --- |
| ARCHITECTURAL DECISION | Estilo | Modular monolith; PostgreSQL es la cola durable inicial. |
| ARCHITECTURAL DECISION | Auth web | JWT corto + refresh token opaco rotativo en cookie segura. |
| ARCHITECTURAL DECISION | Archivos | Almacenamiento privado cifrado AES-GCM, no blobs de CV en PostgreSQL. |
| ARCHITECTURAL DECISION | Integraciones | Adaptadores server-side, dobles en pruebas y sin llamadas reales. |
| RISK | Outlook | Registro de Entra, redirect URI, consentimiento y vigencia del secret deben estar configurados antes del incremento Graph. |
| RISK | Correo | SMTP/dominio remitente debe estar disponible antes de activar flujos reales de verificación, recuperación o notificación. |
| RISK | Privacidad | Antes de producción, el responsable debe confirmar base legal, aviso de privacidad, acceso de Anthropic a texto de CV y política de backup/purga. |
| RISK | Capacidad | Retención indefinida exige monitorear volumen de archivos, backups y crecimiento de PostgreSQL. |

## 14. Próximo incremento recomendado

Crear una spec pequeña para **fundación de identidad y administración de cuentas**: esquema Flyway inicial, registro/verificación, login, refresh/logout, recuperación, roles, primer administrador, errores seguros y auditoría mínima. Outlook, documentos, trabajos y Claude quedan explícitamente fuera de esa primera spec.
