# PRD - Ingesta Segura de CVs

## Estado

APROBADO_PARA_SPECS. Revision de arquitectura completada. Este documento define
comportamiento de producto; no define arquitectura ni implementacion tecnica.

## Objetivo

Descargar, validar, analizar y conservar de forma privada los CVs recibidos
como adjuntos, para usarlos en etapas posteriores de extraccion y ranking.

## 1. Inicio de Ingesta

- Solo un job en `INGESTING_DOCUMENTS` puede iniciar esta etapa.
- Solo un worker procesa un job al mismo tiempo.
- Si el worker cae, otro puede continuar cuando venza el lease.
- Reintentos, reinicios o recuperacion no duplican documentos ni conteos.
- Si el job se cancela, el worker se detiene tan pronto como sea seguro.

## 2. Adjuntos Elegibles

Un adjunto solo se intenta procesar si cumple todas estas condiciones:

1. Pertenece a un mensaje descubierto previamente.
2. Es un `fileAttachment`.
3. No es inline, item ni reference attachment.
4. Su nombre contiene una palabra de CV permitida.
5. Esta dentro del limite de 10 MiB.
6. Su contenido real es PDF o DOCX.
7. No esta vacio, corrupto ni protegido con contrasena.
8. El antivirus informa resultado limpio.

### Filtro de nombre

El nombre se usa solo de forma temporal para decidir si vale la pena descargar
el adjunto. `cv` coincide como cualquier subcadena. Las demas expresiones
coinciden sin distinguir mayusculas ni acentos:

- `cv`
- `hoja de vida`
- `curriculum`
- `resume`
- `résumé`

El nombre nunca se persiste, expone, registra en logs ni aparece en auditoria.

### Casos

| Caso | Resultado |
| --- | --- |
| Nombre coincide y contenido PDF/DOCX valido | Continua validacion y antivirus. |
| Nombre no coincide | Ignorado con `NOT_CV_FILENAME`; no se descarga. |
| Adjuntos inline, item o reference | Ignorados con motivo seguro. |
| Archivo mayor a 10 MiB | Ignorado con `FILE_TOO_LARGE`; no se descarga. |
| Archivo vacio | Ignorado con `EMPTY_DOCUMENT`. |
| Formato distinto de PDF/DOCX | Ignorado con `UNSUPPORTED_FORMAT`. |
| PDF/DOCX corrupto | Ignorado con `CORRUPT_DOCUMENT`. |
| PDF protegido con contrasena | Ignorado con `PASSWORD_PROTECTED`. |

## 3. Limites

### Por mensaje

- El sistema consulta metadatos minimos de todos los adjuntos y selecciona como
  maximo dos candidatos cuyo nombre cumpla el filtro.
- Si existen mas de dos candidatos por nombre, el job registra la advertencia
  `MESSAGE_ATTACHMENT_LIMIT_REACHED`.
- No descarga, guarda ni expone nombres de candidatos omitidos.

### Por archivo

- Maximo 10 MiB por CV.

### Por job

- No existe limite funcional de cantidad de documentos.
- Existe limite operativo de 500 MiB acumulados descargados.
- Al alcanzar 500 MiB, el job deja de descargar nuevos adjuntos y registra
  `JOB_BYTE_LIMIT_REACHED`.
- El resultado puede continuar con documentos procesados hasta ese punto e
  indica alcance parcial.

## 4. Antivirus

### Archivo limpio

- Continua al cifrado y almacenamiento privado.

### Malware detectado

- Se cuarentena.
- No queda disponible para extraccion, descarga ni ranking.
- Se cuenta como documento cuarentenado con `MALWARE_DETECTED`.
- El job continua con otros adjuntos.

### Antivirus no disponible

- El archivo se ignora con `ANTIVIRUS_UNAVAILABLE`.
- No queda disponible ni se almacena.
- El job continua con otros adjuntos.
- Si ningun CV valido queda disponible, el job termina con
  `NO_VALID_CV_DOCUMENTS`.

## 5. Cifrado y Almacenamiento

Un documento solo queda disponible si:

- Supero validacion de nombre, tipo real, tamano e integridad.
- El antivirus lo declaro limpio.
- Fue cifrado con AES-GCM.
- Se guardo en almacenamiento privado fuera del web root.
- Se persistieron unicamente metadatos tecnicos minimos.

El sistema no guarda bytes de CV en PostgreSQL.

### Fallo de cifrado o storage

- El archivo no queda disponible.
- Se elimina cualquier temporal creado.
- Se registra como ignorado con `STORAGE_UNAVAILABLE`.
- El job continua con otros adjuntos.

## 6. Duplicados

- Un adjunto repetido del mismo mensaje no se procesa otra vez.
- Si dos mensajes distintos contienen exactamente el mismo contenido, solo se
  conserva el primer CV.
- El contenido repetido se registra como ignorado con `DUPLICATE_CONTENT`.
- No se expone hash, nombre, ID Outlook ni origen del documento original.
- La deduplicacion de personas queda fuera de esta funcionalidad.

## 7. Datos Persistidos

Para documentos aceptados, ignorados o cuarentenados se conserva solo:

- Identificador interno de documento.
- Hashes tecnicos no publicos.
- Tamano.
- Formato real.
- Fecha de recepcion.
- Estado.
- Codigo seguro de motivo, cuando corresponda.
- Referencia opaca al archivo cifrado, solo para documentos disponibles.
- Version de clave de cifrado.

No se conserva:

- Nombre de archivo.
- MIME declarado.
- Bytes en PostgreSQL.
- Ruta publica.
- URL Graph.
- IDs Outlook en claro.
- Tokens.
- Resultado detallado del antivirus.

## 8. Conteos y Estado del Job

El reclutador puede ver:

- Documentos aceptados.
- Documentos ignorados.
- Documentos cuarentenados.
- Advertencias agregadas `MESSAGE_ATTACHMENT_LIMIT_REACHED` y
  `JOB_BYTE_LIMIT_REACHED`.
- Codigos seguros de motivos.

El reclutador no puede ver nombres, hashes, rutas, archivos, IDs Outlook, datos

## 9. Resultado Final

| Situacion | Resultado |
| --- | --- |
| Existe al menos un CV disponible | El job avanza a `ANALYZING`. |
| No existe ningun CV disponible | El job termina en `FAILED` con `NO_VALID_CV_DOCUMENTS`. |
| Se alcanzo limite de adjuntos o bytes, pero hay CVs validos | Continua con advertencia de alcance parcial. |
| El job se cancela | Se detiene de forma segura y no publica reporte ni ranking. |

## Cambios Reflejados en Spec 007

Los siguientes cambios ya están reflejados en la Spec 007 técnica y no bloquean
su implementación ni revisión:

- Usar nombre temporalmente como filtro inicial.
- Solicitar nombre del adjunto a Graph sin persistirlo.
- Maximo dos adjuntos por mensaje.
- Sin limite funcional de documentos por job.
- Limite operativo de 500 MiB por job.
- Deduplicacion por contenido entre mensajes.
- Antivirus o storage no disponibles ignoran solo ese archivo y permiten
  continuar el job.

## Criterios de Aceptacion

1. Solo se descarga un `fileAttachment` cuyo nombre cumpla el filtro permitido.
2. Todo archivo descargado debe validar PDF/DOCX real, limites, integridad y
   antivirus antes de quedar disponible.
3. Nombres, IDs Outlook, rutas, hashes y contenido de CV nunca se exponen al
   reclutador.
4. Cada mensaje revisa como maximo dos adjuntos y un job descarga como maximo
   500 MiB.
5. Malware se cuarentena y el job continua; antivirus o storage no disponible
   ignoran solo el archivo.
6. El mismo contenido no se conserva dos veces dentro de un job.
7. Sin CV disponible, el job falla con `NO_VALID_CV_DOCUMENTS`.
8. Con CVs disponibles y advertencias de limite, el job continua con alcance
   parcial indicado de forma segura.
