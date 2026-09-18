# PRD - Vacantes y Ejecucion Asincrona

## Estado

Borrador funcional consolidado con decisiones de producto aprobadas en la
sesion de refinamiento. Este documento define comportamiento de producto; no
define arquitectura ni implementacion tecnica.

## Objetivo

Permitir que reclutadores y administradores definan una vacante y soliciten un
reporte durable, sin esperar el procesamiento de Outlook, CVs o IA durante la
solicitud.

## Acceso

- Todo `RECLUTADOR` o `ADMIN` activo puede crear, consultar, editar, archivar,
  reactivar y solicitar reportes.
- Las vacantes son compartidas y no pertenecen exclusivamente a quien las creo.
- Una cuenta sin sesion vigente o desactivada no puede operar vacantes ni jobs.

## 1. Crear Vacante

### Datos requeridos

- Titulo obligatorio.
- Descripcion obligatoria.
- Fecha inicial y final de recepcion.
- Entre 1 y 30 requisitos.
- Cada requisito tiene descripcion, peso de 1 a 5 y marca obligatorio/opcional.
- El orden de requisitos se conserva.
- Se permiten requisitos con descripciones parecidas o repetidas si representan
  criterios diferentes.

### Casos

| Caso | Resultado |
| --- | --- |
| Datos validos | Crea vacante `ACTIVE`. |
| Titulo repetido de otra vacante activa | Muestra advertencia visual, pero permite guardar. |
| Titulo, descripcion, fechas o requisitos invalidos | No crea vacante e informa validacion. |
| Sin requisitos | No crea vacante. |
| Mas de 30 requisitos | No crea vacante. |
| Fecha inicial posterior a fecha final | No crea vacante. |

### Titulos duplicados

- La advertencia compara titulos ignorando mayusculas, minusculas y espacios
  iniciales/finales.
- Solo compara con vacantes activas.
- No impide guardar ni requiere confirmacion adicional.

## 2. Editar Vacante

- Solo una vacante `ACTIVE` puede editarse.
- La edicion reemplaza titulo, descripcion, rango y requisitos como una
  actualizacion completa.
- Si dos personas editan al mismo tiempo, solo se aplica la primera
  actualizacion. La segunda debe recargar la vacante y decidir como aplicar sus
  cambios.
- Una vacante archivada no puede editarse.

### Edicion con job activo

- Se permite editar una vacante mientras tiene un job activo.
- El job existente conserva su snapshot original.
- La edicion afecta solo jobs futuros.

## 3. Archivar y Reactivar

### Archivar

- Una vacante `ACTIVE` puede pasar a `ARCHIVED`.
- Archivar no elimina vacante, requisitos ni historial.
- Archivar una vacante con job activo no cancela ese job.
- Una vacante archivada no permite crear nuevos jobs.

### Reactivar

- Una vacante `ARCHIVED` puede volver a `ACTIVE`.
- Reactivar conserva titulo, descripcion, rango y requisitos existentes.
- Si todavia existe un job activo, la vacante sigue sin permitir otro job hasta
  que el anterior sea terminal.
- Repetir archivo o reactivacion sobre el mismo estado no altera datos ni genera
  auditoria adicional.

## 4. Rango de Recepcion

- El usuario introduce solo fechas, no horas.
- Las fechas se interpretan siempre en `America/La_Paz`.
- El rango incluye el dia inicial y el dia final completos.
- El sistema convierte internamente ese rango a UTC para consultar Outlook.
- No existe un limite funcional de cantidad de dias.

### Volumen operativo

- Un rango muy amplio puede superar el limite operativo de mensajes procesables.
- En ese caso el sistema produce un reporte parcial con advertencia segura.
- El reclutador puede crear otro job con un rango mas acotado.
- El sistema no debe ocultar que el resultado es parcial.

## 5. Crear un Job de Reporte

- Solo una vacante existente y `ACTIVE` puede crear un job.
- Crear un job responde inmediatamente; no espera Outlook, descarga de archivos,
  IA ni ranking.
- El job inicia en `QUEUED`.
- Cada job conserva una copia inmutable de titulo, rango de recepcion y
  requisitos, incluidos pesos, obligatoriedad y orden.
- Editar, archivar o reactivar la vacante no modifica jobs ya creados.

| Caso | Resultado |
| --- | --- |
| Vacante activa sin job activo | Crea job `QUEUED`. |
| Vacante inexistente | No crea job. |
| Vacante archivada | No crea job. |
| Ya existe job activo | No crea otro job. |
| Dos solicitudes simultaneas | Solo una crea job; la otra informa que ya existe uno activo. |

## 6. Un Solo Job Activo

Un job es activo mientras este en:

- `QUEUED`: esperando procesamiento.
- `DISCOVERING`: buscando mensajes elegibles en Outlook.
- `INGESTING_DOCUMENTS`: validando y almacenando adjuntos.
- `ANALYZING`: esperando o realizando extraccion, analisis y ranking.

Reglas:

- Una vacante solo puede tener un job activo.
- Cuando el job llega a un estado terminal, la vacante puede crear otro.
- Los estados terminales son `COMPLETED`, `COMPLETED_WITH_WARNINGS`, `FAILED`,
  `REAUTHORIZATION_REQUIRED` y `CANCELLED`.

## 7. Consultar Estado y Conteos

El reclutador puede:

- Consultar un job individual.
- Listar jobs de una vacante.
- Filtrar por estado.
- Ver el intento, fechas, estado, codigo seguro de falla y conteos agregados.

El sistema puede mostrar mensajes descubiertos, documentos aceptados, documentos
ignorados, documentos en cuarentena, advertencias seguras y motivos seguros de
falla.

El sistema nunca muestra tokens, leases, IDs Graph, rutas de almacenamiento,
hashes, contenido de CV ni datos internos de proveedores.

## 8. Cancelar un Job

- Un reclutador o administrador puede cancelar un job activo.
- El estado final pasa a `CANCELLED`.
- Repetir la cancelacion no cambia historial, fechas ni auditoria.
- No se puede cancelar un job ya terminado por otra causa.

### Cancelacion durante procesamiento

- El worker se detiene tan pronto como sea seguro.
- Se conservan documentos o datos tecnicos ya persistidos.
- No se publica un reporte ni ranking parcial de un job cancelado.
- La cancelacion no elimina automaticamente datos tecnicos ya almacenados.

## 9. Reintentar un Job

- Solo `FAILED` y `REAUTHORIZATION_REQUIRED` permiten reintento desde el
  historial.
- Un job `CANCELLED` no se reintenta; se crea un job nuevo desde la vacante.
- El retry crea un nuevo job `QUEUED`.
- El nuevo job conserva el snapshot del job anterior.
- El historial anterior no se modifica.
- No existe un limite funcional de reintentos manuales.
- Si existe otro job activo para la vacante, el retry no puede crear uno nuevo.
- Un job con `REAUTHORIZATION_REQUIRED` necesita que un `ADMIN` resuelva la
  conexion Outlook antes de reintentarlo.

## 10. Recuperacion, Leases e Idempotencia

Estas reglas no requieren accion del reclutador:

- Cada job se conserva de forma durable.
- Si el backend se reinicia, un job pendiente o abandonado puede recuperarse.
- Solo un worker puede procesar un job al mismo tiempo.
- Si un worker deja de responder, otro puede continuar despues de que venza su
  lease.
- Reintentar o recuperar no debe duplicar documentos, transiciones finales ni
  conteos.
- Un job completado, fallido o cancelado no vuelve a procesarse automaticamente.
- Las llamadas externas no se ejecutan dentro de la solicitud HTTP que creo el
  job.

## 11. Auditoria

Se auditan acciones efectivas:

- Vacante creada.
- Vacante editada.
- Vacante archivada.
- Vacante reactivada.
- Job creado.
- Job cancelado.
- Job reintentado.

No se crea auditoria adicional cuando una operacion repetida no modifica nada.

## Criterios de Aceptacion

1. Un reclutador o administrador activo puede crear, consultar y editar una
   vacante compartida con requisitos validos.
2. La vacante interpreta sus fechas como dias completos de `America/La_Paz`.
3. Una vacante activa puede editarse mientras un job conserva su snapshot.
4. Una vacante archivada no permite nuevos jobs y archivar no cancela uno activo.
5. Cada vacante tiene como maximo un job activo.
6. Un job nuevo inicia en `QUEUED`, conserva su snapshot y responde sin esperar
   procesamiento externo.
7. Cancelar detiene de forma segura sin publicar un reporte parcial.
8. Solo jobs `FAILED` y `REAUTHORIZATION_REQUIRED` permiten retry.
9. Recuperacion de worker o reintentos no duplican datos ni conteos.
10. El estado expone solo conteos, advertencias y codigos seguros.
