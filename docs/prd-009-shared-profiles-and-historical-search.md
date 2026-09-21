# PRD - Perfiles Compartidos y Busqueda Historica

## Objetivo

Permitir actualizar datos operativos compartidos de candidatos y buscar CVs ya
conservados cuando un reporte no alcanza el umbral de su vacante.

## Perfiles y disponibilidad

- Cada candidato tiene un perfil compartido para todos los `RECRUITER` y
  `ADMIN` autorizados.
- La disponibilidad inicia en `DESCONOCIDO` y puede cambiarse a `DISPONIBLE` o
  `NO_DISPONIBLE` por un usuario autorizado.
- Solo se pueden corregir ubicación y habilidades extraídas. Nombre y correo no
  se corrigen desde el perfil porque forman parte de la identidad extraída.
- Cada corrección conserva el valor original, el valor corregido, el usuario y
  la fecha y hora UTC. El valor corregido es el que usan el perfil y las
  búsquedas futuras.
- Si dos usuarios guardan el mismo perfil a la vez, se conserva el primer
  cambio confirmado; el segundo debe recargar antes de volver a guardar.
- Cambiar disponibilidad, ubicación o habilidades no cambia CVs, análisis,
  scores, ranking ni reportes ya terminados.

## Disponibilidad en reportes y exportaciones

- El filtro por disponibilidad se aplica a las entradas del reporte abierto
  usando la disponibilidad actual de su perfil compartido.
- Se pueden elegir uno o más valores de disponibilidad; una entrada coincide si
  su perfil tiene cualquiera de los valores elegidos. Se combina con los demás
  filtros del reporte mediante `Y`.
- Las exportaciones incluyen la disponibilidad actual del perfil. Si no se
  registró una actualización, muestran `DESCONOCIDO`.
- Este dato adicional no modifica la version inmutable del reporte ni sus
  resultados calculados.
- La búsqueda de texto dentro de un reporte permite filtrar por nombre, correo
  o habilidades de sus propias entradas. No consulta otros reportes ni inicia
  una búsqueda histórica.

## Busqueda historica

- Solo se ofrece desde un reporte terminado si ninguna de sus entradas tiene un
  `totalScore` igual o superior al umbral persistido de la vacante.
- Un `RECRUITER` o `ADMIN` debe confirmar expresamente la busqueda antes de que
  se procese cualquier CV. No se inicia de forma automatica.
- Antes de confirmar, el usuario puede indicar período de recepción,
  disponibilidad, habilidades o términos, ubicación y puntaje total mínimo.
  Todos son opcionales; el puntaje mínimo sugerido es 70 y puede cambiarse entre
  0 y 100.
- El usuario también puede indicar nombre o correo para limitar los perfiles
  históricos candidatos antes del análisis.
- La busqueda considera solo CVs disponibles, no eliminados por privacidad y no
  presentes en papelera. Analiza los CVs elegibles contra los requisitos de la
  vacante actual mediante un trabajo asincrono.
- Cada busqueda crea un job `HISTORICAL_SEARCH` propio, vinculado al reporte de
  origen. Un job crea como maximo una version de reporte.
- La busqueda analiza como maximo 500 CVs elegibles, priorizando los recibidos
  mas recientemente. Si existen mas, la nueva version indica una advertencia
  segura de alcance parcial.
- Al finalizar, crea una nueva version inmutable combinada: conserva las
  entradas del reporte que la originó y añade las entradas históricas
  analizadas. El reporte original no cambia.
- La nueva version deduplica personas con la misma regla del reporte: correo
  extraído, luego correo remitente y finalmente nombre normalizado; para una
  misma persona usa el CV más reciente disponible.
- Si no hay CVs históricos elegibles o ninguno supera el puntaje mínimo, no se
  crea una nueva versión y el usuario recibe un resultado seguro sin datos de
  terceros.
- El trabajo respeta la regla de un solo job activo por vacante. Un fallo no
  cambia el reporte que inició la búsqueda.

## Auditoria

Los cambios efectivos de disponibilidad y correcciones de perfil se auditan
para consulta exclusiva de `ADMIN`, sin almacenar CVs ni texto extraído.

## Fuera de alcance

- Corregir nombre, correo, identidad o deduplicación manual.
- Buscar automáticamente, fusionar perfiles, descargar CVs desde el directorio
  o modificar reportes existentes.
- Incluir CVs en papelera o eliminados por privacidad.

## Criterios de aceptacion

### AC-009-01 - Perfil compartido

Cuando un reclutador actualiza disponibilidad, ubicación o habilidades, los
demás reclutadores ven el valor actual; los reportes cerrados permanecen sin
cambios.

### AC-009-02 - Filtro y exportacion de disponibilidad

Cuando un reclutador filtra un reporte por disponibilidad o lo exporta, el
sistema usa el valor actual del perfil y muestra `DESCONOCIDO` si no existe uno
registrado.

### AC-009-03 - Confirmacion y elegibilidad

Dado un reporte sin candidatos que alcancen su umbral, cuando un reclutador no
confirma la búsqueda histórica, entonces no se inicia ningún análisis. Tras una
confirmación válida, solo se procesan CVs históricos elegibles.

### AC-009-04 - Nueva version combinada

Cuando la búsqueda histórica obtiene candidatos que cumplen el puntaje mínimo,
entonces crea una nueva versión combinada y ordenada; el reporte original
mantiene sus entradas y resultados.

## Definition of Ready

`READY_FOR_ARCHITECT`
