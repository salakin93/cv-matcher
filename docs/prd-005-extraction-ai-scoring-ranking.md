# PRD - Extraccion, IA, Scoring y Ranking

## Estado

APROBADO_PARA_SPECS. Revision de arquitectura completada. Este documento define
comportamiento de producto; no define arquitectura ni implementacion tecnica.

## Objetivo

Transformar CVs validos en un ranking explicable, reproducible y util para
reclutadores. El sistema apoya la decision humana; nunca contrata ni descarta

## 1. Extraccion de Texto

- Solo se procesan CVs disponibles y cifrados.
- Se extrae texto de PDF y DOCX en espanol o ingles.
- El texto extraido se conserva cifrado y solo puede usarse internamente.
- No existe API, descarga, busqueda ni exportacion de texto extraido.
- El texto de un CV nunca aparece en logs, auditoria, metricas ni respuestas
  HTTP.

| Caso | Resultado |
| --- | --- |
| PDF/DOCX con texto util | Queda listo para analisis. |
| Documento vacio, corrupto o protegido | Se excluye con codigo seguro. |
| CV escaneado sin texto util | Se conserva privado, se marca `NO_USABLE_TEXT`, no usa OCR, no se envia a Claude ni entra al ranking. |
| Limite de paginas, caracteres, memoria o tiempo | Se excluye con codigo seguro. |
| Ningun CV tiene texto util | El job continua segun la regla de candidatos/ranking y muestra advertencias seguras. |

## 2. Identidad y Candidatos

La identidad se resuelve en este orden:

1. Correo extraido del texto del CV.
2. Correo remitente del mensaje, solo si no existe correo valido en el CV.
3. Candidato anonimo independiente, si no existe un correo valido.

### Reglas

- El correo se conserva cifrado.
- No se exponen remitente, hashes, IDs Outlook ni fuentes internas de identidad.
- Dos CVs con el mismo correo se consideran la misma persona.
- Para una misma persona se usa el CV mas reciente.
- Si las fechas de dos CVs empatan, se usa un identificador interno estable.
- Todo CV sin correo extraido ni correo remitente entra como `Candidato anonimo`,
  incluso si contiene un nombre util.
- Varios candidatos anonimos aparecen como entradas separadas, una por documento;
  nunca se deduplican por nombre ni similitud.

## 3. Envio a Claude

- Claude recibe solo texto extraido necesario y requisitos del snapshot de la
  vacante.
- Antes de enviar el texto, el backend elimina nombre, correo, telefono,
  direccion y enlaces personales detectables.
- Claude no recibe archivo original, identidad, correo remitente, tokens, rutas,
  hashes ni datos de Outlook.
- Claude responde siempre en espanol.
- Claude no puede cambiar requisitos, pesos, umbral, ranking ni decisiones
  humanas.

## 4. Validacion de Claude

Para cada requisito, Claude debe devolver:

- Compatibilidad entera de 0 a 100.
- Estado: `CUMPLE`, `NO_CUMPLE` o `NO_DEMOSTRADO`.
- Evidencia breve.
- Explicacion breve en espanol.

El backend valida estrictamente:

- Existe exactamente una evaluacion por requisito.
- No faltan ni sobran requisitos.
- Los puntajes estan entre 0 y 100.
- El estado es valido.
- La evidencia corresponde al texto enviado.
- La explicacion y evidencia estan en espanol y dentro de limites.
- La respuesta no contiene instrucciones, secretos, URLs o datos internos.

Si una respuesta es invalida, no se guarda parcialmente.

## 5. Fallos de Analisis

| Caso | Resultado |
| --- | --- |
| Claude responde correctamente | El documento queda analizado. |
| Error temporal, rate limit o timeout | El sistema usa timeout de 30 segundos y reintenta hasta tres veces; en `Retry-After` espera como maximo 60 segundos por reintento. |
| Claude devuelve respuesta invalida | El documento queda con advertencia segura. |
| Claude bloquea contenido | El documento queda con advertencia segura. |
| Algunos CVs fallan, otros son validos | El reporte se genera con advertencias y solo incluye candidatos evaluados. |

## 6. Scoring Determinista

Claude entrega compatibilidades, pero el backend calcula el score final.

### Formulas

```text
mandatoryScore = promedio ponderado de requisitos obligatorios
optionalAverage = promedio ponderado de requisitos opcionales
optionalBonus = min(20, optionalAverage x 0.20)
totalScore = min(100, mandatoryScore + optionalBonus)
```

### Reglas

- Los pesos de requisitos van de 1 a 5.
- Un requisito obligatorio sin evidencia recibe compatibilidad 0 y estado
  `NO_DEMOSTRADO`.
- Un candidato con `NO_DEMOSTRADO` sigue visible en el ranking.
- Sin requisitos obligatorios, `mandatoryScore` es 0.
- Sin requisitos opcionales, `optionalAverage` y `optionalBonus` son 0.
- El backend conserva precision decimal y redondea el resultado final a dos
  decimales.
- Cambiar una vacante, prompt, modelo o algoritmo no altera un reporte terminado.

## 7. Umbral del Reporte

- El umbral sugerido es 70.
- El reclutador puede definir un valor entero entre 0 y 100 al crear el job.
- El umbral queda guardado en el snapshot del job.
- Cambiarlo no modifica la vacante ni reportes futuros.
- El umbral es un indicador, no un filtro.
- Todos los candidatos rankeados siguen visibles aunque no alcancen el umbral.

## 8. Ranking

El backend ordena candidatos de forma determinista:

1. Mayor `totalScore`.
2. Mayor `mandatoryScore`.
3. Mayor cantidad de requisitos obligatorios cumplidos.
4. CV recibido mas recientemente.
5. Identificador interno estable.

El frontend no recalcula scores ni resuelve empates.

### Top 5

- El Top 5 son los primeros cinco candidatos del ranking completo.
- No usa una formula diferente.
- Si existen menos de cinco candidatos, muestra los disponibles.

## 9. Reporte Inmutable

- Un job produce como maximo una version de reporte.
- El reporte conserva snapshot de vacante, requisitos, umbral, scores,
  evaluaciones, modelo, prompt y candidatos.
- Cambios posteriores no alteran resultados historicos.
- Un job cancelado nunca crea reporte.
- Un reporte terminado no se recalcula ni edita.

### Resultado del job

| Caso | Resultado |
| --- | --- |
| Candidatos evaluados sin advertencias | `COMPLETED`. |
| Candidatos evaluados con advertencias | `COMPLETED_WITH_WARNINGS`. |
| No existe candidato rankeable | `COMPLETED_WITH_WARNINGS` con `NO_RANKABLE_CANDIDATES`, sin crear reporte vacio. |
| Job cancelado | `CANCELLED`, sin reporte. |

## 10. Informacion Visible para Reclutadores

El reporte puede mostrar:

- Nombre o `Candidato anonimo`.
- Correo, solo si existe y el usuario esta autorizado.
- Confianza de identidad cuando corresponda.
- Posicion de ranking.
- Puntajes obligatorio, opcional y total.
- Cumplimiento por requisito.
- Evidencia y explicacion en espanol.
- Indicador de umbral.
- Advertencias seguras.
- Top 5.

El reporte nunca muestra:

- Texto completo de CV.
- Archivo original.
- Correo remitente.
- Hashes, tokens, rutas o IDs internos.
- Prompt, payload Claude ni secretos.
- Datos tecnicos de almacenamiento.

## Cambios Reflejados en Fuentes Actuales

Los siguientes cambios ya están reflejados en las fuentes técnicas y de producto
indicadas y no bloquean la implementación ni revisión:

- PRD y Spec 012: sin candidatos rankeables termina `COMPLETED_WITH_WARNINGS`,
  no `FAILED`.
- Spec 009: Claude recibe texto minimizado sin identificadores.
- Spec 011: deduplicar solo por correo extraido o correo remitente; candidatos
  sin correo entran como anonimos independientes.
- Specs 008-012: reflejar conteos, advertencias y comportamiento de CVs sin
  texto util.

## Criterios de Aceptacion

1. Solo CVs con texto util pasan a Claude; los escaneados sin texto se conservan
   privados y se excluyen de IA/ranking.
2. Claude recibe texto minimizado y requisitos snapshot, nunca identificadores
   ni archivos originales.
3. Backend valida toda respuesta Claude antes de persistir evaluaciones.
4. Claude no calcula score, ranking ni decisiones humanas.
5. El backend calcula scores reproducibles con pesos snapshot y reglas de
   `NO_DEMOSTRADO`.
6. El umbral es un indicador inmutable por job y no oculta candidatos.
7. El ranking y Top 5 usan desempates deterministas calculados por backend.
8. Cambios posteriores no modifican una version de reporte terminada.
9. Fallos parciales de IA generan advertencias sin descartar los candidatos
   evaluados correctamente.
10. Sin candidatos rankeables, el job termina `COMPLETED_WITH_WARNINGS` sin
    crear un reporte vacio.
