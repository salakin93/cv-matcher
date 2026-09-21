# 008 - Extracción segura de texto CV

## Objetivo
Extraer texto util de CVs disponibles para uso interno, conservandolo cifrado y excluyendo de analisis los documentos sin texto util.

## Referencias
- `docs/prd-005-extraction-ai-scoring-ranking.md`, seccion 1.
- `docs/architecture.md`, secciones 6, 7, 10 y 11.
- Specs 004 y 007.

## Alcance
### Incluido
- Extraccion segura PDF/DOCX, clasificacion de texto util y limites de paginas, caracteres y tiempo.
### Excluido
- OCR, APIs de texto, identidad, Claude, scoring/ranking, descarga y UI.

## Comportamiento y reglas
- Solo documentos disponibles/cifrados se descifran temporalmente para parser seguro; PDF/DOCX en espanol o ingles pueden producir texto.
- PDF excede 50 paginas; PDF/DOCX exceden 100000 caracteres o 15 segundos por documento: se excluyen con codigo seguro. No se usa OCR.
- Escaneado sin texto util se conserva privado con `NO_USABLE_TEXT`; vacio/corrupto/protegido o limitado tampoco se envia a Claude ni entra al ranking. Si ninguno es util, etapas posteriores terminan con advertencias seguras, no un reporte vacio.

## Contratos API
No agrega endpoint. El job expone solo conteos/advertencias seguras; el texto extraido no tiene API, busqueda, exportacion ni descarga.

## Configuracion centralizada
`DocumentProperties.extraction` en `application.yml`: `maxPdfPages=50`, `maxCharacters=100000`, `timeout=15s`, externos por entorno y no editables por `ADMIN`. Es el SSOT requerido por arquitectura seccion 13.

## Datos y persistencia
Persistir estado de extraccion, motivo seguro y texto cifrado/referencia protegida asociada al documento. No guardar texto en logs, auditoria, metricas o respuestas; conservar los metadatos de documento de 007.

## Integraciones
Solo bibliotecas/parsers locales seguros; no llama proveedor externo ni OCR.

## Errores y estados
Fallos por documento no invalidan otros. `NO_USABLE_TEXT` y limites son advertencias; la decision final `COMPLETED_WITH_WARNINGS` sin reporte vacio pertenece a 012.

## Seguridad y privacidad
Texto tiene mismos controles de cifrado y autorizacion que el original. Borrar buffers/temporales cuando sea viable y no incluir fragmentos en diagnosticos.

## Observabilidad
Metricas agregadas de extraccion, limite y texto inutil; duracion sin contenido. Logs seguros por documento opaco/correlation ID.

## Estrategia de pruebas
### Validacion manual
Usar CVs sinteticos PDF/DOCX en espanol/ingles, escaneado, corrupto y fixtures que excedan 50 paginas, 100000 caracteres y 15 segundos.
### Automatizacion diferida
Unitarias de clasificacion/limites y parser seguro; integracion de persistencia cifrada y pruebas de no filtracion de texto.

## Criterios de aceptacion
1. Solo texto util queda disponible internamente para 009.
2. Un PDF sobre 50 paginas o documento sobre 100000 caracteres/15 segundos se excluye seguro.
3. CV escaneado no usa OCR ni llega a Claude/ranking.
4. Texto no se expone por API, logs, auditoria, exportacion o metricas.

## Riesgos y dependencias
Depende de 007. Parsers deben resistir archivos hostiles y respetar timeout externo.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: limites 50 paginas/100000 caracteres/15 segundos son configuracion externa por entorno, no parametros administrativos.

## Definition of Ready
`READY_FOR_DEV`.
