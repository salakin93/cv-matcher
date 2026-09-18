# Informe de Alcance Funcional

## Resumen

El producto se organiza en seis capacidades principales. El flujo de valor
completo es:

```text
usuario autorizado -> vacante -> job -> Outlook -> CV valido -> texto -> analisis IA -> score/ranking -> revision humana -> descarga/exportacion/privacidad
```

Actualmente el backend llega de forma parcial hasta la ingesta de archivos. El
ranking utilizable por reclutadores aun no existe.

| Capacidad | Specs | Estado estimado |
| --- | --- | --- |
| Acceso y administracion de cuentas | 001-002 | Alto |
| Vacantes y jobs asincronos | 003-004 | Alto |
| Outlook y descubrimiento Inbox | 005-006 | Alto |
| Ingesta documental segura | 007 | Parcial |
| Extraccion, IA, scoring y ranking | 008-012 | Pendiente |
| Operacion sobre candidatos, privacidad y administracion | 013-024 | Pendiente |

## 1. Acceso, Usuarios y Administracion

**Valor:** permite que reclutadores y administradores accedan al sistema de
forma segura.

### Alcance funcional

- Registro de reclutadores.
- Verificacion de correo.
- Inicio y cierre de sesion.
- Recuperacion y cambio de contrasena.
- Sesiones y tokens con expiracion.
- Bloqueo temporal ante intentos fallidos.
- Administracion de usuarios, roles, activacion y desactivacion.
- Primer administrador con cambio obligatorio de contrasena.
- Auditoria de acciones sensibles de cuentas.

### Estado

- Los modulos `identity` y `administration` existen y corresponden a Specs 001
  y 002.
- Debe confirmarse mediante sus gates de QA/release antes de declararlo
  entregado definitivamente.

### Para cerrar scope

- Verificar pruebas de autorizacion, revocacion de sesiones y flujos de correo.
- Confirmar UI de autenticacion y administracion con Specs FE-001 y FE-002.
- Confirmar auditoria de todos los cambios administrativos.

## 2. Vacantes y Ejecucion Asincrona

**Valor:** permite definir una necesidad de contratacion e iniciar un
procesamiento durable sin bloquear una solicitud HTTP.

### Alcance funcional

- Crear, editar, archivar y reactivar vacantes.
- Definir rango de recepcion y requisitos obligatorios/opcionales con peso.
- Convertir fechas `America/La_Paz` a UTC.
- Crear un job de reporte por vacante.
- Garantizar un solo job activo por vacante.
- Consultar estado, cancelar y reintentar jobs.
- Gestionar leases, recuperacion tras caida e idempotencia.
- Exponer estado y conteos seguros por API/OpenAPI.

### Estado

- `vacancy` y `job` estan implementados.
- El modelo incluye `QUEUED`, `DISCOVERING`, `INGESTING_DOCUMENTS`,
  `ANALYZING`, `FAILED`, `REAUTHORIZATION_REQUIRED` y `CANCELLED`.
- Los conteos de documentos estan previstos en el detalle de job.

### Para cerrar scope

- Completar notificaciones al finalizar o fallar, Spec 022.
- Completar el reporte final: aun no hay extraccion, analisis ni ranking.
- Completar UI de jobs, Spec FE-004.

## 3. Outlook e Identificacion de Mensajes

**Valor:** conecta una cuenta Outlook compartida y encuentra mensajes
elegibles del Inbox segun el rango de la vacante.

### Alcance funcional

- OAuth server-side administrado exclusivamente por `ADMIN`.
- Cifrado de tokens y secretos solo en servidor.
- Estado de conexion y reautorizacion.
- Consulta limitada a Inbox y rango UTC persistido.
- Uso de IDs inmutables.
- Paginacion, lease, reintentos y `Retry-After`.
- Registro de referencias internas de mensajes descubiertos.
- Sin lectura de cuerpo, asunto, remitente o destinatarios cuando no corresponda.

### Estado

- Implementado en Specs 005 y 006.
- El permiso actual es `Mail.Read`, necesario para descargar adjuntos.
- El worker de documentos recibe referencias internas, no tokens ni URLs Graph.

### Para cerrar scope

- Anadir pruebas HTTP del cliente Graph de adjuntos para `429`, 5xx, `401/403`,
  lease, ruta Inbox, header `Prefer` y scope ausente.
- Confirmar reautorizacion administrativa de conexiones con permisos antiguos.

## 4. Ingesta Segura de CVs

**Valor:** descarga y conserva adjuntos PDF/DOCX para procesamiento posterior.

### Alcance funcional

- Reclamar jobs en `INGESTING_DOCUMENTS`.
- Listar adjuntos de mensajes descubiertos.
- Aceptar solo `fileAttachment`; ignorar inline, item y reference attachments.
- Aplicar limites por adjunto, mensaje, job y bytes acumulados.
- Detectar PDF y DOCX por contenido real, no por nombre o MIME.
- Ignorar formatos invalidos, corruptos, protegidos o vacios con codigos seguros.
- Analizar archivos con antivirus obligatorio.
- Cifrar originales con AES-GCM fuera del web root.
- Persistir hashes y metadatos tecnicos minimos, sin bytes en PostgreSQL.
- Evitar duplicados por mensaje y adjunto.
- Registrar conteos y eventos seguros.

### Estado

- Spec 007 esta parcialmente implementada.
- Existe V10, worker, almacenamiento AES-GCM, puerto antivirus, cliente Graph
  de adjuntos y persistencia de documentos.
- La separacion `job -> document` fue iniciada; el worker ya no depende
  directamente de `JobService`.

### Bloqueadores para completar 007

1. Obtener build y tests verdes verificables.
2. Validar DOCX real con parser OOXML; el ZIP minimo actual acepta archivos
   corruptos.
3. Persistir o recalcular bytes acumulados de documentos ya resueltos al
   reanudar un job.
4. Verificar conectividad de ClamAV al arrancar en `prod`, no solo el modo
   configurado.
5. Fortalecer la comprobacion de que el storage root no sea publico.
6. Cubrir Graph, AV, limites, DOCX, revocacion OAuth, fallos de storage y
   limpieza de temporales con pruebas.
7. Pasar Technical Review, QA Review, Security/Privacy Review y Release Review.

## 5. Extraccion, IA, Scoring y Ranking

**Valor:** transforma CVs en un reporte explicable y reproducible para el
reclutador.

### Alcance funcional

- Extraer texto de PDF/DOCX en espanol e ingles.
- Detectar documentos sin texto util.
- Crear candidatos y deduplicarlos por correo extraido, correo remitente o
  nombre normalizado.
- Enviar solo texto necesario y requisitos a Claude.
- Validar estrictamente la respuesta estructurada de Claude.
- Calcular en backend `mandatoryScore`, bono opcional y `totalScore`.
- Aplicar desempates deterministas.
- Crear versiones inmutables de reportes.
- Mostrar ranking, Top 5, evidencias y advertencias.

### Estado

- Pendiente: Specs 008 a 012.
- `ANALYZING` existe como punto de espera, pero aun no produce analisis ni
  ranking.

### Orden recomendado

1. Spec 008: extraccion segura de texto.
2. Spec 009: analisis estructurado por Claude.
3. Spec 010: calculo determinista por documento.
4. Spec 011: identidad y deduplicacion.
5. Spec 012: reporte versionado y ranking.

### Criterio de cierre

- El sistema debe producir un ranking reproducible aun cuando Claude falle
  parcialmente.
- Claude nunca puede decidir contratacion, descartar candidatos ni calcular el
  orden final.

## 6. Revision Humana, Documentos y Exportaciones

**Valor:** permite usar el resultado sin exponer CVs ni automatizar decisiones
de contratacion.

### Alcance funcional

- Estado humano por candidato y reporte: `PENDIENTE`, `EN_REVISION`,
  `PRESELECCIONADO`, `DESCARTADO`.
- Descarga autenticada y autorizada de CV.
- Filtros por score, cumplimiento, disponibilidad, advertencias y evidencia.
- Exportacion PDF/XLSX con minimizacion de datos.
- Auditoria de descargas y exportaciones.
- Perfiles compartidos y correccion de informacion extraida.

### Estado

- Pendiente: Specs 013 a 018.
- No debe iniciarse descarga antes de cerrar autorizacion de Spec 014.
- No debe iniciarse exportacion antes de existir ranking inmutable.

## 7. Historial, Papelera, Privacidad y Operacion

**Valor:** permite conservar, buscar y eliminar datos de candidatos de forma
controlada.

### Alcance funcional

- Busqueda historica solo tras confirmacion explicita.
- Disponibilidad y correcciones de perfil compartido.
- Papelera, restauracion por 180 dias y purga automatica.
- Eliminacion inmediata por privacidad.
- Eliminacion del original y datos procesados.
- Anonimizacion de reportes historicos.
- Configuracion administrativa segura.
- Consulta de auditoria inmutable.
- Notificaciones internas y por correo.

### Estado

- Pendiente: Specs 019 a 024.
- Depende de candidatos, reportes y documentos consolidados.

## Frontend

El frontend contiene base Vite/React y Specs FE-001 a FE-011, pero cada alcance
debe verificarse por su spec correspondiente. Las pantallas prioritarias son:

- Autenticacion y administracion.
- Vacantes.
- Jobs y conexion Outlook.
- Ranking y detalle de reporte.
- Estado humano y descarga protegida.
- Exportaciones, notificaciones, perfiles, papelera, privacidad y operacion.

## Ruta para Completar el Producto

1. Cerrar Spec 007 y sus gates.
2. Implementar Specs 008 a 012 para el primer reporte/ranking util.
3. Implementar Specs 013 a 015 para revision humana, descarga y exportacion.
4. Implementar Specs 016 a 021 para directorio, historial y privacidad.
5. Implementar Specs 022 a 024 para notificaciones, configuracion y auditoria.
6. Completar specs frontend en paralelo con cada capacidad backend estable.

El primer hito funcional completo para un reclutador es Spec 012: crear una
vacante, obtener CVs desde Outlook, analizarlos y consultar un ranking
explicable.
