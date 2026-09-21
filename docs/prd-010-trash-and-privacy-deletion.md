# PRD - Papelera y Eliminacion por Privacidad

## Objetivo

Permitir retirar CVs de uso operativo, restaurarlos durante un período limitado
y eliminar de inmediato todos los datos de una persona cuando exista una
solicitud de privacidad.

## Papelera compartida

- Un `RECRUITER` o `ADMIN` puede mover un CV a la papelera compartida.
- El CV en papelera no puede descargarse ni participar en nuevos reportes,
  rankings, análisis o búsquedas históricas.
- Mover un CV a papelera no altera versiones de reportes ya terminadas. Esas
  entradas siguen visibles para preservar su reproducibilidad, pero su CV deja
  de estar disponible para descarga.
- Cualquier `RECRUITER` o `ADMIN` puede restaurar un CV antes de 180 días desde
  su envío a papelera. Al restaurarlo, vuelve a estar disponible para futuras
  operaciones.
- Al cumplirse 180 días, una purga automática elimina permanentemente el
  archivo original, texto extraído y datos procesados del CV. No se puede
  restaurar un CV purgado.
- Si la purga deja un perfil sin ningún CV disponible, elimina también los
  datos extraídos de ese perfil. Los reportes históricos conservan su entrada
  sin hacer disponible el documento ni datos de perfil eliminados.
- Los envíos a papelera, restauraciones y purgas efectivas se auditan con datos
  mínimos y sin contenido de CV.

## Eliminacion por privacidad

- Solo un `ADMIN` puede iniciar una eliminación por privacidad y debe confirmar
  que la acción es inmediata e irreversible.
- La eliminación alcanza todos los CVs, archivos originales, texto extraído,
  perfiles, disponibilidad, correcciones y datos procesados de la persona,
  incluso si algún CV está en papelera.
- La operación elimina esos datos inmediatamente; no espera la purga de 180
  días ni permite restauración.
- La solicitud permanece en curso hasta completar la eliminacion. Solo informa
  exito cuando todos los datos fueron eliminados; si falla, bloquea el acceso al
  candidato y documentos y devuelve un error seguro.
- Todas las apariciones de la persona en reportes históricos se anonimizan como
  `Candidato eliminado por privacidad`. Se eliminan de esas entradas correo,
  ubicación, disponibilidad, evidencias y cualquier referencia descargable al
  CV. Scores y estructura histórica del reporte se conservan.
- La auditoría conserva solo que ocurrió una eliminación por privacidad, quién
  la ejecutó y cuándo, sin datos que permitan identificar a la persona.
- Si la eliminación no puede terminar por completo, el sistema bloquea el
  acceso al candidato y sus documentos, registra un fallo seguro y no declara
  la operación como completada hasta finalizarla.

## Fuera de alcance

- Recuperar CVs purgados o datos eliminados por privacidad.
- Eliminación por privacidad realizada por reclutadores.
- Editar CVs, eliminar selectivamente partes de un perfil o modificar scores de
  reportes históricos.

## Criterios de aceptacion

### AC-010-01 - Papelera y restauracion

Cuando un reclutador mueve un CV a papelera, queda excluido de futuras
operaciones y descargas. Si otro reclutador lo restaura antes de 180 días,
vuelve a estar disponible sin alterar reportes cerrados.

### AC-010-02 - Purga

Cuando un CV cumple 180 días en papelera, entonces el sistema elimina de forma
permanente su archivo y datos procesados, y no ofrece restauración.

### AC-010-03 - Privacidad

Cuando un administrador confirma una eliminación por privacidad, entonces se
eliminan inmediatamente los datos de la persona y sus reportes históricos se
anonimizan sin exponer datos personales.

## Definition of Ready

`READY_FOR_ARCHITECT`
