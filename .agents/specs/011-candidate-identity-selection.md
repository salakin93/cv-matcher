# 011 - Identidad y selección de candidato

## Objetivo
Agrupar documentos evaluados por persona para un job y seleccionar exactamente el CV mas reciente por identidad, preservando candidatos anonimos independientes.

## Referencias
- `docs/prd-005-extraction-ai-scoring-ranking.md`, seccion 2.
- `docs/prd-004-secure-cv-ingestion.md`, secciones 6--7.
- `docs/architecture.md`, secciones 4, 6 y 9.
- Specs 006, 008 y 010.

## Alcance
### Incluido
- Extraccion interna de email/nombre de texto, normalizacion, precedencia de identidad, deduplicacion por job y seleccion estable de CV.
### Excluido
- Correccion/fusion manual de perfiles, directorio/busqueda historica, UI, report ranking y descarga.

## Comportamiento y reglas
- Precedencia obligatoria: email valido extraido del CV; si falta, sender protegido del mensaje; si falta, nombre extraido normalizado. Correo coincide sin caso; nombre normalizado es menor confianza pero deduplica cuando no existe correo valido.
- Mismos valores de la clave aplicable identifican persona; elegir CV de fecha de recepcion mas reciente, y ante empate ID interno estable. Un CV sin email, sender ni nombre util es `Candidato anonimo`; cada anonimo es una entrada separada.
- Nombre/correo se conservan cifrados y fuentes internas no se muestran. Esta seleccion no altera documentos, sus scores ni snapshots.

## Contratos API
No agrega endpoint publico. Contrato interno a 012 entrega ID candidato opaco, identidad permitida, confianza, documento/score elegido y desempate estable; sender y fuente no salen del modulo.

## Configuracion centralizada
Normalizacion de email/nombre es un unico componente del modulo `candidate`; su algoritmo no se replica en controller, frontend ni reportes. No agrega secretos/configuracion externa.

## Datos y persistencia
Flyway agrega `candidate_profile` y relacion de identidad/documento segun limites de modulo, con valores cifrados e indice/clave protegida para igualdad. La unicidad de seleccion es por job e identidad; no usar hash expuesto como API.

## Integraciones
No llama proveedores. Consume sender protegido descubierto por 006 y texto protegido de 008 dentro de casos de uso autorizados.

## Errores y estados
Fallo de extraer identidad no descarta documento evaluado: crea anonimo. Datos identitarios invalidos se tratan como ausentes; no se inventan ni se unen por similitud no aprobada.

## Seguridad y privacidad
Sender nunca se muestra ni se envia a Claude. Cifrado, acceso por modulo y minimizacion conforme arquitectura; no logs con email/nombre normalizado ni fuente de identidad.

## Observabilidad
Metricas agregadas de claves CV/sender/nombre/anonimo y deduplicados, sin valores; diagnosticos solo IDs opacos.

## Estrategia de pruebas
### Validacion manual
Con documentos sintéticos, comprobar precedencia CV email > sender > nombre, coincidencias normalizadas, fechas/ID empate y anonimos independientes.
### Automatizacion diferida
Unitarias de normalizacion/precedencia, integracion de cifrado/indice de igualdad y regresion de deduplicacion por job.

## Criterios de aceptacion
1. Dos CVs con mismo email CV producen una persona y se elige el mas reciente.
2. Sender se usa solo sin email CV; nombre normalizado solo sin ambos.
3. Dos CVs sin correos con mismo nombre normalizado se deduplican.
4. Anonimos permanecen separados y fuentes internas no se exponen.

## Riesgos y dependencias
Depende de 006, 008 y 010. Deduplicar por nombre puede unir homonimos; es la regla de menor confianza aprobada, no una inferencia adicional.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: precedencia CV email, sender protegido, nombre normalizado es la unica deduplicacion v1; no hay matching difuso.

## Definition of Ready
`READY_FOR_DEV`.
