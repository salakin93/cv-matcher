# 007 - Ingesta segura de CV

## Objetivo
Descargar y conservar solo CVs PDF/DOCX limpios, cifrados y disponibles para extraccion, sin exponer documentos ni metadatos sensibles.

## Referencias
- `docs/prd-004-secure-cv-ingestion.md`.
- `docs/architecture.md`, secciones 6, 7, 8.1, 10 y 11.
- Specs 004 y 006.

## Alcance
### Incluido
- Seleccion de adjuntos, descarga, validacion, antivirus, cifrado/storage, deduplicacion por contenido y conteos/motivos seguros.
### Excluido
- Extraccion, OCR, descarga del usuario, identidad de persona, analisis/ranking y UI.

## Comportamiento y reglas
- En `INGESTING_DOCUMENTS`, revisar maximo dos adjuntos por mensaje. Solo `fileAttachment` no inline/item/reference cuyo nombre temporal coincida sin acento/caso con `cv`, `hoja de vida`, `curriculum`, `resume` o `résumé` se descarga.
- Limites: 10 MiB por archivo y 500 MiB acumulados por job; alcanzar limites agrega advertencia y conserva resultados validos previos.
- Validar tipo real PDF/DOCX, no vacio/corrupto/protegido y ClamAV limpio antes de cifrar AES-256-GCM en storage privado. Malware se cuarentena; AV/storage no disponible ignora solo el archivo.
- Mismo adjunto no se reprocesa; contenido igual entre mensajes conserva el primero. Si queda alguno disponible avanza a `ANALYZING`; si ninguno, `FAILED/NO_VALID_CV_DOCUMENTS`.

## Contratos API
No agrega endpoint publico. Detalle job expone conteos aceptado/ignorado/cuarentena, advertencias agregadas y motivos seguros; nunca nombre, hash, Graph ID, ruta ni archivo.

## Configuracion centralizada
`DocumentProperties` en `application.yml` concentra raiz privada, limites 10 MiB/500 MiB, clave/version de cifrado y parametros ClamAV. La raiz y secretos solo vienen de entorno conforme a arquitectura.

## Datos y persistencia
Flyway agrega `candidate_document`/registro de procesamiento con estado, formato real, tamano, recepcion, hash tecnico, referencia opaca y version de clave. Bytes no van a PostgreSQL; nombre, MIME declarado, URL/ruta publica e IDs Outlook no persisten en claro.

## Integraciones
Descarga por adaptador Graph y escaneo por adaptador ClamAV fuera de transacciones. Limpiar temporales ante fallo; no llamar Claude.

## Errores y estados
Usar motivos seguros definidos por PRD: `NOT_CV_FILENAME`, `FILE_TOO_LARGE`, `EMPTY_DOCUMENT`, `UNSUPPORTED_FORMAT`, `CORRUPT_DOCUMENT`, `PASSWORD_PROTECTED`, `MALWARE_DETECTED`, `ANTIVIRUS_UNAVAILABLE`, `STORAGE_UNAVAILABLE`, `DUPLICATE_CONTENT`; y advertencias de limites. Cancelacion no publica ranking.

## Seguridad y privacidad
No persistir ni loguear nombre temporal, bytes, ruta, hash o respuesta AV. Documentos disponibles y metadatos sensibles usan controles de acceso/cifrado de arquitectura.

## Observabilidad
Metricas agregadas por estado/motivo, bytes y limites; logs con job/documento opaco sin PII ni detalles AV.

## Estrategia de pruebas
### Validacion manual
Con adjuntos sinteticos y dobles Graph/AV: filtro, dos adjuntos, tamanos, tipos falsos, corrupto/protegido, malware, AV/storage caido, duplicado y ningun valido.
### Automatizacion diferida
Pruebas de parsers/tipo, limites y limpieza; integracion storage cifrado/ClamAV doble/PostgreSQL; seguridad de no exposicion y recuperacion/cancelacion.

## Criterios de aceptacion
1. Solo adjuntos elegibles se descargan y solo limpios quedan disponibles.
2. Ningun nombre, ID, hash, ruta o contenido se expone.
3. Limites por mensaje/archivo/job generan resultado parcial seguro.
4. Malware se cuarentena y fallos AV/storage no bloquean otros adjuntos.
5. Sin CV disponible, job falla con `NO_VALID_CV_DOCUMENTS`.

## Riesgos y dependencias
Depende de 006 y ClamAV operativo en produccion. El límite de mensajes está resuelto en 1000 por job conforme a 006.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: archivos cifrados privados, no blobs PostgreSQL, conforme arquitectura.

## Definition of Ready
`READY_FOR_DEV`.
