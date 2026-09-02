# PRD — CV Matcher

## 1. Propósito

CV Matcher ayuda a los reclutadores a revisar CVs recibidos en una cuenta Outlook compartida, compararlos contra una vacante y generar un ranking explicable. El sistema apoya la decisión humana: nunca contrata ni descarta candidatos automáticamente.

## 2. Usuarios y permisos

| Rol | Capacidades |
| --- | --- |
| Reclutador | Gestiona vacantes, solicita reportes, revisa candidatos, descarga CVs, exporta y administra perfiles de candidatos compartidos. |
| Administrador | Todo lo anterior, más usuarios, roles, parámetros, integraciones Outlook/Claude, eliminación por privacidad y auditoría. |

Los datos de vacantes, reportes y directorio son compartidos por todos los reclutadores. La auditoría es inmutable y sólo visible para administradores.

## 3. Autenticación y cuentas

- Registro público con nombre completo, correo y contraseña; el rol inicial es `RECLUTADOR`.
- La cuenta requiere verificación por enlace temporal antes de iniciar sesión. Se permite reenviar el enlace.
- Inicio de sesión con token de acceso. Toda operación protegida sin token válido responde `401 Unauthorized`.
- Política inicial de contraseña: mínimo ocho caracteres, una mayúscula, una minúscula y un número. La política será configurable posteriormente por administración.
- Recuperación mediante enlace temporal, de un solo uso y con respuesta neutral para no revelar si un correo existe.
- Token de acceso de 15 minutos y sesión máxima de ocho horas, ambos configurables.
- Cinco intentos fallidos bloquean la cuenta durante 15 minutos.
- El primer administrador debe cambiar obligatoriamente su contraseña al ingresar. Los administradores pueden activar, desactivar, promover o degradar usuarios; esos cambios invalidan tokens existentes.
- El cambio de correo exige verificar el nuevo correo y cierra todas las sesiones existentes.

## 4. Vacantes y reportes

Una vacante tiene título, descripción, rango `from`/`to` y uno o más requisitos. Cada requisito tiene descripción, peso entero de 1 a 5 y marca de obligatorio.

- Las fechas se introducen en `America/La_Paz` y se convierten a UTC para su almacenamiento y consulta.
- Un reclutador puede editar una vacante y generar otro reporte. Cada ejecución crea una versión de reporte inmutable con su configuración, resultados y modelo de IA utilizado.
- Las vacantes pueden archivarse y reactivarse. Una vacante archivada conserva su historial pero no permite nuevos reportes.
- Se permite un reporte activo por vacante. Las solicitudes se encolan y la concurrencia global es configurable.
- El trabajo es asíncrono. Se puede consultar su estado y el solicitante recibe una notificación dentro de la aplicación y por correo cuando finaliza, con o sin advertencias, o falla.
- Si hay fallas parciales, se entrega el reporte con advertencias y razones seguras. Si no hay CV válido analizable, o no puede operar Outlook o la IA, el trabajo falla.

## 5. Obtención y gestión de CVs

- La fuente inicial es una única cuenta Outlook de la organización; se consulta exclusivamente la carpeta Inbox y el rango indicado.
- Se aceptan PDF y DOCX. Se pueden procesar CVs escritos en español o inglés.
- Si un correo contiene una carta de presentación y un CV, se identifica el CV y se informa el documento ignorado. Si contiene varios CVs, cada uno es inicialmente un candidato separado.
- Para duplicados de la misma persona dentro de un reporte se presenta una sola entrada, usando el CV más reciente. Identidad: correo extraído del CV, luego correo remitente, luego nombre normalizado con menor confianza.
- Un CV puede aparecer en reportes de varias vacantes. Cada vacante analiza todos los CVs de su rango.
- PDFs corruptos, protegidos con contraseña, sin texto útil, u otros documentos que no sean CV se excluyen del ranking e informan una razón segura.
- El CV original se guarda en almacenamiento local privado; la base de datos conserva su referencia y metadatos.
- Los CVs se conservan indefinidamente hasta que un reclutador los lleve a la papelera. Desde allí cualquier reclutador puede restaurarlos durante 180 días. Después se eliminan permanentemente el archivo y datos procesados, manteniendo auditoría mínima.
- Un CV en papelera queda excluido de reportes, rankings y búsqueda histórica.
- Un administrador puede realizar eliminación definitiva inmediata por solicitud de privacidad. Sus apariciones históricas se anonimizan como “Candidato eliminado por privacidad”.

## 6. Ranking y análisis con IA

Claude analiza el texto extraído, en español o inglés, y devuelve en español por requisito: compatibilidad de 0 a 100, estado (`CUMPLE`, `NO_CUMPLE` o `NO_DEMOSTRADO`), evidencia breve y explicación. El backend valida esa respuesta y es la única autoridad para calcular puntajes y ordenar el ranking.

Para cada candidato:

```text
mandatoryScore = promedio ponderado de compatibilidad de requisitos obligatorios
optionalAverage = promedio ponderado de compatibilidad de requisitos opcionales
optionalBonus = optionalAverage × 0.20, con máximo de 20
totalScore = mínimo entre 100 y mandatoryScore + optionalBonus
```

- El peso se usa tanto en requisitos obligatorios como opcionales.
- Si no existe evidencia de un requisito obligatorio, recibe 0 pero el candidato continúa en el ranking. `NO_DEMOSTRADO` se distingue de `NO_CUMPLE`.
- El umbral predeterminado sugerido para una vacante es 70 y puede cambiarse antes de generar el reporte.
- Los empates se resuelven por mayor `mandatoryScore`, luego mayor número de requisitos obligatorios cumplidos y finalmente CV más reciente.
- Claude no puede contratar, descartar, cambiar pesos, calcular el puntaje final ni ordenar candidatos.
- El modelo inicial es `claude-sonnet-5`. El administrador puede escoger un modelo para análisis futuros; el cambio no altera reportes existentes.

## 7. Resultados y decisiones humanas

El reporte muestra resumen, ranking completo, Top 5, puntajes obligatorio/opcional/total, requisitos no cumplidos o no demostrados, evidencias y estado de cada documento.

- Un reclutador puede descargar el CV original autenticado; no existen enlaces públicos permanentes.
- Puede filtrar por rango de puntaje, cumplimiento obligatorio, disponibilidad, documentos ignorados o evidencia insuficiente, y buscar por nombre, correo o habilidad.
- El estado operativo por candidato y reporte es humano y compartido: `PENDIENTE`, `EN_REVISION`, `PRESELECCIONADO` o `DESCARTADO`. No cambia el análisis ni se propaga a otras vacantes.
- Se pueden exportar reportes a PDF y XLSX. Incluyen únicamente nombre, correo, ubicación, disponibilidad, puntajes y evidencias; excluyen teléfono, dirección, atributos sensibles y rutas/enlaces de CV. Cada exportación se audita.

## 8. Directorio histórico

Cuando no existan candidatos que alcancen el umbral de la vacante, el sistema ofrece una búsqueda histórica sólo tras confirmación del reclutador.

- El reclutador define filtros: período de recepción, disponibilidad, habilidades/términos extraídos, ubicación cuando exista y puntaje mínimo respecto de la vacante actual.
- El sistema sugiere 70 como puntaje mínimo, modificable por el reclutador.
- Todos los reclutadores pueden actualizar disponibilidad (`DISPONIBLE`, `NO_DISPONIBLE`, `DESCONOCIDO`) y corregir datos extraídos del perfil. Se conserva el valor original, el cambio, usuario y fecha.
- La corrección se aplica al perfil compartido y a búsquedas futuras; los reportes históricos no cambian.

## 9. Administración e integraciones

- El administrador ve el estado de Outlook y Claude, puede iniciar/reautorizar la integración Outlook y seleccionar el modelo de IA.
- Secretos, tokens y claves se mantienen sólo en configuración segura del servidor; nunca se muestran ni editan desde la interfaz, logs o respuestas públicas.
- Se auditan cambios de usuarios y roles, activación, parámetros, reconexión Outlook, disponibilidad, correcciones de perfil, papelera, restauración, purga y exportaciones.

## 10. Alcance de interfaz

La primera versión es una aplicación web responsiva, optimizada para escritorio y funcional en móvil para consultar estado, notificaciones y resultados. Toda la interfaz y todos los reportes están en español.

## 11. Fuera de alcance inicial

- Decisiones automáticas de contratación o descarte.
- Soporte de documentos distintos de PDF y DOCX.
- Interfaz multilingüe.
- SSO.
