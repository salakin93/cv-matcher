# Despliegue — Automatización de Preselección de Candidatos

Plan de infraestructura, entornos, variables de entorno y operación. Contexto funcional en [PRD.md](./PRD.md) sección 8 (NFR) y EPIC 6.

---

## 1. Entornos

| Entorno | Propósito |
|---|---|
| Local | Desarrollo diario, Docker Compose. |
| Staging | Validación E2E antes de producción, con credenciales separadas de Microsoft y Anthropic. |
| Producción | Uso real por el operador. |

Cada entorno tiene su propio redirect URI de Microsoft y su propia API key de Anthropic (ver [microsoft-graph-setup.md](./microsoft-graph-setup.md) y [anthropic-setup.md](./anthropic-setup.md)), para que un error en staging no afecte datos ni presupuesto de producción.

---

## 2. Infraestructura recomendada (bajo costo)

| Componente | Opción recomendada | Motivo |
|---|---|---|
| Frontend (React) | Vercel o Cloudflare Pages | Nivel gratuito suficiente para un solo operador; despliegue automático desde el repo. |
| Backend (Spring Boot) | Railway o Render (~$5–7/mes) | Evitar serverless puro: Spring Boot tiene arranque en frío lento, mejor un servicio "siempre encendido" de bajo costo. |
| Base de datos | Postgres administrado en Neon, Supabase o el mismo Railway | Backups automáticos y sin mantenimiento manual de servidor. |
| Almacenamiento privado de CVs | Bucket S3-compatible (ej. el propio de Railway/Render, o Backblaze B2/Cloudflare R2 por costo) | Los PDFs no deben vivir en disco efímero del backend en producción. |
| Dominio | Cualquier registrador; subdominio tipo `reclutamiento.ceare.com.bo` o dominio propio del proyecto | Redirect URI de Microsoft depende de esta URL final. |

---

## 3. Variables de entorno

### Backend

```
# Base de datos
DATABASE_URL=
DATABASE_USERNAME=
DATABASE_PASSWORD=

# Microsoft Graph / OAuth2
MICROSOFT_TENANT_ID=
MICROSOFT_CLIENT_ID=
MICROSOFT_CLIENT_SECRET=
MICROSOFT_REDIRECT_URI=
TOKEN_ENCRYPTION_KEY=

# Anthropic
ANTHROPIC_API_KEY=
ANTHROPIC_MODEL=claude-sonnet-5

# Almacenamiento privado
STORAGE_PROVIDER=s3
STORAGE_BUCKET=
STORAGE_ACCESS_KEY=
STORAGE_SECRET_KEY=
STORAGE_REGION=

# Aplicación
APP_BASE_URL=
CORS_ALLOWED_ORIGIN=
TIMEZONE=America/La_Paz
MAX_CVS_PER_RUN=150
DEFAULT_CONCURRENCY=3
CONFIDENCE_LOW_THRESHOLD=0.4
RETENTION_DAYS=180
```

### Frontend

```
VITE_API_BASE_URL=
```

**Nunca** commitear estos valores al repositorio: usar el gestor de secretos del proveedor elegido (Railway/Render Secrets, GitHub Actions Secrets para CI, etc.) y un `.env.example` sin valores reales como referencia para el equipo.

---

## 4. Secretos y cifrado

- `TOKEN_ENCRYPTION_KEY` cifra los tokens de Microsoft en la tabla `outlook_connection` (ver [database.md](./database.md)); debe rotar de forma controlada si se sospecha compromiso, re-cifrando los tokens existentes o forzando reconexión.
- `MICROSOFT_CLIENT_SECRET` y `ANTHROPIC_API_KEY` deben rotar según la política de expiración configurada al crearlos (ver microsoft-graph-setup.md sección 2).
- Los logs de aplicación nunca deben imprimir estas variables ni contenido de CVs (validar en revisión de código, ver PRD sección 9 — Definition of Done).

---

## 5. Backups y recuperación

- Backup diario automático de PostgreSQL (la mayoría de proveedores administrados lo incluyen; confirmar retención mínima de backups acorde a los 180 días de retención funcional).
- Prueba de restauración obligatoria antes de ir a producción (Feature 6.1, T6.1.2 del PRD): restaurar un backup en un entorno de prueba y verificar integridad de los datos.
- El almacenamiento privado de CVs (bucket S3-compatible) debe tener versionado o al menos protección contra borrado accidental, ya que los PDFs no se pueden recuperar de Outlook automáticamente tras eliminarse localmente.

---

## 6. Monitoreo y alertas

Configurar sobre el backend desplegado (Spring Boot Actuator + logs JSON, ver [architecture.md](./architecture.md) sección 6):

- **Salud del servicio:** healthcheck del proveedor de hosting apuntando a `/actuator/health`.
- **Errores externos:** alertar ante una tasa elevada de `401`/`429`/`5xx` hacia Graph o Anthropic en una ventana de tiempo corta.
- **Trabajos pendientes:** alertar si `extraction_run` queda en `PROCESANDO` más tiempo del esperado según el tamaño del lote (posible worker atascado).
- **Costo estimado:** alertar si el gasto acumulado de Anthropic en el mes supera un porcentaje del presupuesto definido con RR.HH./Legal (ver [anthropic-setup.md](./anthropic-setup.md) sección 7).

---

## 7. Job de retención

- Tarea programada diaria (cron del propio backend o del proveedor de hosting) que:
  1. Busca `cv_file` con `expires_at` vencido.
  2. Elimina el archivo del almacenamiento privado.
  3. Elimina o anonimiza las filas dependientes según la política de retención.
  4. Registra un `audit_event` por cada eliminación, sin contenido de CV.

---

## 8. Checklist de salida a producción

- [ ] Registro de app en Entra ID aprobado (Ruta A o B, ver microsoft-graph-setup.md) y redirect URI de producción configurado.
- [ ] Aprobación de RR.HH./Legal para uso de IA externa y retención de 180 días.
- [ ] Cuenta Anthropic con presupuesto y API key de producción separada de staging.
- [ ] Dominio, TLS y hosting elegidos y configurados.
- [ ] Variables de entorno cargadas como secretos, ninguna en el repositorio.
- [ ] Backup diario de PostgreSQL activo y restauración probada.
- [ ] Alertas de salud, errores externos, trabajos pendientes y costo configuradas.
- [ ] E2E completo ejecutado en staging: conectar Outlook → crear búsqueda → ejecutar lote (incluyendo un caso de límite de 150 y uno de reclasificación manual) → ver ranking → exportar → eliminar/retención.
- [ ] Documentación de operación diaria y reconexión de Outlook entregada al operador.
