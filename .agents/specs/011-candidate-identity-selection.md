# 011 - Identidad por correo y selección de candidato

## Objetivo
Agrupar documentos evaluados por correo para un job y seleccionar exactamente el CV mas reciente por identidad, preservando candidatos anonimos independientes.

## Referencias
- `docs/prd-005-extraction-ai-scoring-ranking.md`, seccion 2.
- `docs/prd-004-secure-cv-ingestion.md`, secciones 6--7.
- `docs/architecture.md`, secciones 4, 6 y 9.
- Specs 006, 008 y 010.

## Alcance
### Incluido
- Extraccion interna de correo de texto, normalizacion, precedencia de identidad, deduplicacion por job y seleccion estable de CV.
### Excluido
- Correccion/fusion manual de perfiles, directorio/busqueda historica, UI, report ranking y descarga.

## Comportamiento y reglas
- Precedencia obligatoria: correo valido extraido del CV; si falta, correo remitente protegido del mensaje. Los correos coinciden sin caso.
- Mismos valores de la clave aplicable identifican persona; elegir CV de fecha de recepcion mas reciente, y ante empate ID interno estable. Todo CV sin correo de CV ni remitente es `Candidato anonimo`, incluso si contiene un nombre util; cada anonimo es una entrada separada.
- El correo se conserva cifrado y las fuentes internas no se muestran. No deduplicar por nombre, similitud ni otra inferencia. Esta seleccion no altera documentos, sus scores ni snapshots.

## Contratos API
No agrega endpoint publico. Contrato interno a 012 entrega ID candidato opaco, correo autorizado o marcador anonimo, documento/score elegido y desempate estable; remitente y fuente no salen del modulo.

## Configuracion centralizada
Normalizacion de correo es un unico componente del modulo `candidate`; su algoritmo no se replica en controller, frontend ni reportes. No agrega secretos/configuracion externa.

## Datos y persistencia
Flyway agrega `candidate_profile` y relacion de identidad/documento segun limites de modulo, con correo cifrado e indice/clave protegida para igualdad. La unicidad de seleccion es por job e identidad de correo; no usar hash expuesto como API.

## Integraciones
No llama proveedores directamente. Consume correo remitente protegido obtenido solo para un CV disponible en esta etapa posterior a discovery, y texto protegido de 008 dentro de casos de uso autorizados.

## Errores y estados
Fallo de extraer correo no descarta documento evaluado: crea anonimo. Correos invalidos se tratan como ausentes; no se inventan ni se unen por nombre o similitud.

## Seguridad y privacidad
El remitente nunca se muestra ni se envia a Claude. Cifrado, acceso por modulo y minimizacion conforme arquitectura; no logs con correo normalizado ni fuente de identidad.

## Observabilidad
Metricas agregadas de claves de correo CV/remitente/anonimo y deduplicados, sin valores; diagnosticos solo IDs opacos.

## Estrategia de pruebas
### Validacion manual
Con documentos sintéticos, comprobar precedencia correo CV > remitente, coincidencias normalizadas, fechas/ID empate y anonimos independientes aunque tengan nombre.
### Automatizacion diferida
Unitarias de normalizacion/precedencia, integracion de cifrado/indice de igualdad y regresion de deduplicacion por correo y anonimos por documento.

## Criterios de aceptacion
1. Dos CVs con mismo email CV producen una persona y se elige el mas reciente.
2. Remitente se usa solo sin correo CV; no existe deduplicacion por nombre.
3. Dos CVs sin correos, aunque tengan el mismo nombre, permanecen como anonimos independientes.
4. Anonimos permanecen separados y fuentes internas no se exponen.

## Riesgos y dependencias
Depende de 006, 008 y 010. No deduplicar candidatos anonimos puede mostrar varias entradas de una misma persona; es la regla de privacidad y determinismo aprobada.

## Decisiones / preguntas abiertas
- PRODUCT DECISION: la deduplicacion v1 usa solo correo del CV o, como fallback, correo remitente protegido. Todo documento sin correo es anonimo independiente; no hay matching por nombre ni difuso.

## Definition of Ready
`READY_FOR_DEV`.
