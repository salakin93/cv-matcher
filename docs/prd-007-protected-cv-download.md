# PRD - Descarga Protegida de CV

## Estado

Borrador funcional para el punto 7: descarga autenticada y autorizada del CV.
Define únicamente la descarga del documento original asociado a un resultado de
reporte.

## Objetivo

Permitir que el equipo de reclutamiento consulte el CV utilizado para evaluar a
un candidato, manteniendo el documento privado y sin exponer enlaces o datos
técnicos internos.

## Usuarios

Los usuarios con rol `RECRUITER` y `ADMIN` pueden descargar un CV cuando están
autenticados y autorizados para consultar el reporte correspondiente.

## Alcance funcional

- La descarga se inicia desde un candidato dentro de un reporte.
- Sólo puede descargarse el CV original utilizado para evaluar a ese candidato
  en ese reporte.
- El documento se entrega como archivo PDF o DOCX, según su formato original.
- No existen enlaces públicos o permanentes para los CVs.
- El sistema no muestra rutas internas, claves de almacenamiento, enlaces de
  acceso, nombres originales ni información técnica del documento.
- Cada descarga efectiva se registra para auditoría administrativa.
- Cada usuario puede realizar como máximo 20 descargas en un período de 10
  minutos.

## Reglas de negocio

| ID | Regla |
| --- | --- |
| BR-007-01 | Sólo `RECRUITER` y `ADMIN` con sesión válida pueden descargar un CV. |
| BR-007-02 | La descarga sólo está disponible desde la relación exacta entre reporte y candidato; no se permite acceder por perfil, identificador de documento ni referencia interna. |
| BR-007-03 | Se descarga únicamente el documento que se utilizó para evaluar al candidato en ese reporte. No se permiten otras versiones ni documentos alternativos de la misma persona. |
| BR-007-04 | Si el documento fue eliminado, está en papelera, está dañado, no está disponible o no puede verificarse, no se entrega ningún archivo. |
| BR-007-05 | La descarga no modifica el estado humano, perfil, disponibilidad, análisis, evidencias, score ni ranking. |
| BR-007-06 | Cada descarga efectiva queda registrada con el usuario, fecha y referencia interna del candidato/documento para consulta exclusiva de administradores. |
| BR-007-07 | El límite de 20 descargas por usuario cada 10 minutos se aplica antes de entregar el archivo. |

## Fuera de alcance

- Subir, editar, reemplazar, compartir por correo o eliminar CVs.
- Descargar documentos mediante URL pública, enlace permanente, perfil o
  identificador interno.
- Descargar otros documentos de un candidato que no fueron usados en el
  reporte consultado.
- Exportar reportes, modificar estados humanos, perfiles, disponibilidad,
  análisis, scores o ranking.
- Mostrar el detalle de auditoría a reclutadores en la vista ordinaria.

## Criterios de aceptación

### AC-007-01 — Descarga autorizada

Dado un reclutador o administrador autenticado que consulta un reporte, cuando
selecciona descargar el CV de un candidato, entonces recibe el documento usado
para evaluar a ese candidato en formato PDF o DOCX.

### AC-007-02 — Acceso restringido

Cuando una persona no autenticada, sin el rol permitido o sin acceso al
reporte intenta descargar un CV, entonces el sistema rechaza la solicitud sin
exponer el archivo ni detalles internos.

### AC-007-03 — Documento no disponible

Dado un CV eliminado, en papelera, dañado o no disponible, cuando un usuario
autorizado solicita su descarga, entonces el sistema no entrega bytes del
archivo y comunica que el documento no está disponible sin revelar datos
técnicos o personales adicionales.

### AC-007-04 — Aislamiento de documentos

Dado un candidato con más de un CV, cuando un reclutador descarga desde un
reporte, entonces sólo puede recibir el CV utilizado por dicho reporte.

### AC-007-05 — Auditoría y límite

Cuando una descarga se completa, entonces queda registrada para auditoría
administrativa. Si el usuario supera 20 descargas en 10 minutos, entonces el
sistema rechaza nuevas descargas hasta que finalice el período aplicable.

### AC-007-06 — Sin efectos sobre el resultado

Cuando un CV se descarga, entonces no cambian el estado humano, perfil,
disponibilidad, análisis, evidencias, score ni ranking del candidato.

## Definition of Ready

`READY_FOR_ARCHITECT`
