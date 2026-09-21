# PRD - Filtros y Exportaciones de Reporte

## Estado

Borrador funcional para el punto 6: Revision Humana, Documentos y
Exportaciones. Define unicamente los filtros del ranking y las exportaciones.
El estado humano se define en `docs/prd-006-human-status-by-report.md` y la
descarga protegida en `docs/prd-007-protected-cv-download.md`.

## Objetivo

Permitir que el equipo de reclutamiento encuentre entradas relevantes de un
reporte y exporte sus resultados sin incluir CVs ni datos personales no
necesarios.

## Precondicion

La funcionalidad esta disponible solo para una version de reporte inmutable ya
completada. No modifica esa version ni sus resultados.

## Usuarios

Los usuarios con rol `RECRUITER` y `ADMIN` autenticados pueden consultar los
filtros y solicitar una exportacion del reporte que estan autorizados a ver.

## Alcance funcional

### Filtros

- Los filtros se aplican solo a las entradas del reporte abierto; no consultan
  perfiles historicos ni otros reportes.
- Se puede indicar un puntaje total minimo, maximo o ambos. Los limites son
  inclusivos, estan entre 0 y 100 y el minimo no puede superar al maximo.
- El filtro de cumplimiento obligatorio permite elegir una opcion:
  `TODOS_CUMPLEN`, `ALGUNO_NO_CUMPLE` o `ALGUNO_NO_DEMOSTRADO`.
- `TODOS_CUMPLEN` muestra entradas cuyos requisitos obligatorios estan todos en
  estado `CUMPLE`. `ALGUNO_NO_CUMPLE` y `ALGUNO_NO_DEMOSTRADO` muestran
  entradas con al menos un requisito obligatorio en el estado indicado.
- El filtro de advertencias muestra solo entradas que tienen una o mas
  advertencias seguras en el reporte.
- El filtro de evidencia insuficiente muestra solo entradas con al menos un
  requisito en estado `NO_DEMOSTRADO`.
- Los filtros activos se combinan con `Y`. Si no hay filtros, se muestran todas
  las entradas del reporte. El usuario puede limpiar todos los filtros.
- Los filtros no se guardan, no cambian scores, ranking, analisis, estado
  humano, CV ni datos de candidato.

La disponibilidad y su filtro quedan fuera de esta funcionalidad. Se entregan
junto con el perfil compartido en la funcionalidad 7.

### Exportaciones

- Un usuario autorizado puede solicitar el reporte completo en PDF o XLSX.
- La exportacion siempre incluye todas las entradas del reporte, sin considerar
  los filtros activos de la pantalla.
- Por cada entrada, PDF y XLSX incluyen solamente: nombre, correo, ubicacion,
  puntaje obligatorio, puntaje opcional, puntaje total y evidencias breves por
  requisito.
- La disponibilidad no se incluye hasta que la funcionalidad 7 la administre
  como dato de perfil compartido.
- Una exportacion no incluye CVs, texto extraido, telefono, direccion,
  atributos sensibles, estado humano, rutas, enlaces de descarga, IDs internos
  ni metadatos tecnicos del documento.
- Si el reporte contiene una entrada anonimizada por privacidad, se exporta con
  el nombre `Candidato eliminado por privacidad` y sin correo, ubicacion ni
  evidencias personales disponibles.
- Si la generacion falla, no se entrega un archivo parcial y se muestra un
  mensaje seguro. El fallo no revela datos internos ni personales adicionales.
- Generar o descargar una exportacion no cambia el reporte, sus entradas ni los
  estados humanos.

### Auditoria

- Cada descarga de CV completada y cada exportacion completada crean un evento
  de auditoria inmutable visible solo para `ADMIN`.
- El evento conserva el usuario que realizo la accion, la fecha y hora UTC, el
  tipo de accion y la referencia interna necesaria para identificar el reporte
  o la entrada. No conserva CVs, texto extraido, correos ni otros datos
  personales.

## Reglas de negocio

| ID | Regla |
| --- | --- |
| BR-008-01 | Los filtros solo reducen visualmente las entradas del reporte consultado. |
| BR-008-02 | El score filtrado es siempre `totalScore`; no se filtra por `mandatoryScore` ni `optionalScore`. |
| BR-008-03 | Una exportacion contiene el reporte completo, incluso si la vista tiene filtros activos. |
| BR-008-04 | PDF y XLSX tienen el mismo conjunto permitido de datos por candidato. |
| BR-008-05 | Las exportaciones y descargas efectivas se auditan sin almacenar contenido personal en el evento de auditoria. |

## Fuera de alcance

- Filtro por disponibilidad, disponibilidad del candidato, perfiles compartidos
  y correccion de informacion extraida.
- Busqueda por nombre, correo, habilidad o cualquier dato del directorio
  historico.
- Exportar CVs, un subconjunto manual de candidatos, otros formatos, envios por
  correo o enlaces publicos.
- Incluir el estado humano, comentarios o motivos operativos en la exportacion.
- Consultar auditoria como `RECRUITER`.

## Criterios de aceptacion

### AC-008-01 - Filtros combinados

Dado un reporte terminado, cuando un reclutador aplica limites de score y uno
o mas filtros adicionales, entonces ve solo las entradas que cumplen todos los
filtros activos y el reporte no cambia.

### AC-008-02 - Cumplimiento obligatorio

Dado un reporte con requisitos obligatorios en distintos estados, cuando el
reclutador selecciona cada opcion de cumplimiento, entonces ve exactamente las
entradas definidas para esa opcion.

### AC-008-03 - Limpiar filtros

Dado un reporte con filtros activos, cuando el reclutador los limpia, entonces
vuelve a ver todas sus entradas sin modificar ningun resultado.

### AC-008-04 - Exportacion completa minimizada

Dado un reporte y filtros activos, cuando un reclutador autorizado exporta PDF
o XLSX, entonces recibe todas las entradas del reporte y solo los campos
permitidos, sin CVs ni datos excluidos.

### AC-008-05 - Acceso restringido

Cuando una persona no autenticada, sin rol permitido o sin autorizacion para
el reporte solicita una exportacion, entonces el sistema la rechaza sin
entregar ningun archivo ni detalle interno.

### AC-008-06 - Auditoria

Cuando se completa una descarga de CV o una exportacion, entonces se registra
un evento de auditoria visible solo para administradores y sin contenido
personal.

## Definition of Ready

`READY_FOR_ARCHITECT`
