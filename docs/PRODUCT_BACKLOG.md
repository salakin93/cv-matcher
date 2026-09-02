# Backlog de producto — CV Matcher

Este backlog deriva de `docs/PRD.md`. Las tareas técnicas se refinan durante planificación; las historias son el compromiso funcional verificable.

## Epic 1 — Cuentas, acceso y administración

### Feature 1.1 — Registro y autenticación

- Historia: como reclutador, quiero registrarme, verificar mi correo e iniciar sesión para acceder al sistema.
- Historia: como usuario, quiero recuperar mi contraseña mediante un enlace temporal para recuperar el acceso de forma segura.
- Historia: como usuario, quiero cambiar mi correo verificando el nuevo para mantener mis datos correctos.
- Tareas: API de registro/verificación/login/reset; tokens y expiración; política de contraseñas; bloqueo por intentos; pantallas de autenticación; pruebas de autorización.

### Feature 1.2 — Usuarios y roles

- Historia: como administrador, quiero gestionar usuarios, roles y activación para administrar el acceso.
- Historia: como primer administrador, quiero estar obligado a cambiar mi contraseña inicial.
- Tareas: modelo de roles; invalidación de sesiones; administración de usuarios; auditoría; UI administrativa.

## Epic 2 — Vacantes y ejecución de reportes

### Feature 2.1 — Gestión de vacantes

- Historia: como reclutador, quiero crear, editar, archivar y reactivar vacantes con requisitos ponderados.
- Tareas: CRUD de vacantes/requisitos; validaciones de pesos, fechas y al menos un requisito; archivo/reactivación; UI.

### Feature 2.2 — Trabajos asíncronos y notificaciones

- Historia: como reclutador, quiero generar un reporte y conocer su estado sin esperar la ejecución.
- Historia: como solicitante, quiero ser notificado dentro de la aplicación y por correo al finalizar o fallar.
- Tareas: cola durable; límite de un trabajo activo por vacante; concurrencia configurable; estados y reintentos; notificaciones; pruebas de recuperación.

## Epic 3 — Integración Outlook e ingesta documental

### Feature 3.1 — Integración segura con Outlook

- Historia: como administrador, quiero conectar y reautorizar la cuenta Outlook compartida para obtener CVs.
- Tareas: OAuth server-side; almacenamiento seguro de tokens; estado de conexión; permisos mínimos; manejo de errores y auditoría.

### Feature 3.2 — Descubrimiento y validación de documentos

- Historia: como reclutador, quiero que el reporte tome los CVs PDF/DOCX del Inbox dentro del rango solicitado.
- Historia: como reclutador, quiero saber por qué un documento fue ignorado.
- Tareas: consulta por rango UTC; clasificación CV/carta; extracción PDF/DOCX; control de archivos inválidos; almacenamiento privado; deduplicación por persona; pruebas con dobles de Graph.

## Epic 4 — Análisis explicable y ranking

### Feature 4.1 — Análisis Claude

- Historia: como reclutador, quiero que cada requisito tenga puntaje, estado y evidencia en español, aun si el CV está en inglés.
- Tareas: contrato estructurado con Claude; validación backend; administración de modelo; protección de datos; pruebas de respuestas inválidas.

### Feature 4.2 — Puntaje y reporte versionado

- Historia: como reclutador, quiero un ranking reproducible para comparar candidatos y entender el resultado.
- Tareas: cálculo determinista de `mandatoryScore`, bono opcional y total; desempates; versionado inmutable; Top 5; advertencias; filtros y búsqueda.

### Feature 4.3 — Decisión humana y exportación

- Historia: como reclutador, quiero revisar, descargar CVs autenticadamente, establecer un estado operativo y exportar resultados.
- Tareas: estados por candidato–reporte; descarga autorizada; exportación PDF/XLSX con minimización de datos; auditoría de exportación.

## Epic 5 — Directorio histórico, conservación y privacidad

### Feature 5.1 — Búsqueda histórica

- Historia: como reclutador, quiero buscar candidatos históricos si el reporte no alcanza el umbral, tras confirmarlo explícitamente.
- Tareas: filtros de período, disponibilidad, habilidades, ubicación y puntaje; sugerencia de 70; exclusión de papelera.

### Feature 5.2 — Perfil y disponibilidad compartidos

- Historia: como reclutador, quiero actualizar disponibilidad y corregir perfiles preservando el origen y auditoría.
- Tareas: modelo de perfil; historial de correcciones; disponibilidad; auditoría; conservación de reportes históricos.

### Feature 5.3 — Papelera y privacidad

- Historia: como reclutador, quiero enviar y restaurar CVs desde papelera.
- Historia: como administrador, quiero eliminar definitivamente los datos de un candidato por solicitud de privacidad.
- Tareas: soft delete; restauración; purga automática a 180 días; anonimización de reportes; eliminación de archivo/datos; auditoría mínima.

## Epic 6 — Configuración, auditoría y operación

### Feature 6.1 — Configuración segura

- Historia: como administrador, quiero conocer el estado de Outlook y Claude y ajustar parámetros permitidos sin exponer secretos.
- Tareas: panel de estado; modelo de IA futuro; parámetros de concurrencia y política de acceso; configuración segura en servidor.

### Feature 6.2 — Auditoría

- Historia: como administrador, quiero consultar un historial inmutable de acciones sensibles.
- Tareas: eventos auditables; consulta filtrable; control de acceso; retención y pruebas de integridad.

## Orden sugerido de entrega

1. Epic 1 y Feature 2.1.
2. Epic 3 y Feature 2.2.
3. Epic 4.
4. Epic 5.
5. Epic 6 de forma transversal desde el inicio, completándolo al cierre.
