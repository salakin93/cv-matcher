# 009 - Análisis estructurado Claude

## Objetivo
Obtener y validar evaluaciones estructuradas por requisito de Claude, tratando tanto CV como respuesta externa como datos no confiables.

## Referencias
- `docs/prd-005-extraction-ai-scoring-ranking.md`, secciones 3--5.
- `docs/architecture.md`, secciones 7, 8.2, 10 y 11.
- Specs 004 y 008.

## Alcance
### Incluido
- Minimizacion de texto, solicitud Claude, contrato JSON estricto, validacion completa, persistencia de evaluaciones y manejo de fallos por documento.
### Excluido
- Extraccion, identidad, formula, ranking/reporte, seleccion ADMIN de modelo (011), UI y decisiones humanas.

## Comportamiento y reglas
- Para cada CV con texto util, enviar solo texto necesario ya minimizado y requisitos del snapshot. Antes de envio retirar nombre, correo, telefono, direccion y enlaces detectables; nunca enviar original, sender, token, ruta, hash ni datos Outlook.
- Respuesta en espanol contiene exactamente una evaluacion por requisito: ID esperado, entero 0--100, `CUMPLE`/`NO_CUMPLE`/`NO_DEMOSTRADO`, evidencia y explicacion breves. Validar cardinalidad, IDs, rangos, limites, idioma, evidencia contra texto enviado y ausencia de secretos/URLs/instrucciones.
- Respuesta invalida o bloqueo no persiste parcialmente y deja advertencia. Exito persiste el conjunto validado; Claude no cambia snapshot, score, orden ni estado humano.

## Contratos API
No agrega endpoint publico. Contrato interno versionado `ClaudeAnalysisRequest/Response` y JSON Schema estricto. Detalle de job/reporte posterior expone solo advertencia segura, nunca prompt, texto o payload.

## Configuracion centralizada
`AnthropicProperties` y cliente HTTP centralizados en modulo `analysis`, con `ANTHROPIC_API_KEY`, modelo, timeout de 30 segundos, maximo de tres reintentos y maximo `Retry-After` de 60 segundos por entorno. El modelo exacto usado se entrega a 012 para snapshot; 011 solo cambia modelo permitido para trabajos futuros.

## Datos y persistencia
Flyway agrega evaluacion por documento/requisito con compatibilidad, estado, evidencia, explicacion y modelo; constraint evita evaluaciones incompletas como resultado valido. Guardar solo tras validacion completa.

## Integraciones
Un unico adaptador Anthropic server-side. Llamadas fuera de transaccion; clasificar timeout/rate limit/transitorio frente a rechazo/bloqueo/contrato invalido. No usar credenciales reales en validacion.

## Errores y estados
Fallos parciales permiten continuar con otros CVs y advierten. Cada llamada vence a 30 segundos; errores transitorios y `429` se reintentan hasta tres veces, respetando `Retry-After` hasta 60 segundos por reintento. Agotado ese presupuesto, el documento queda con advertencia segura.

## Seguridad y privacidad
Proteccion contra prompt injection: contenido CV nunca modifica instrucciones ni invoca acciones. No loguear texto, prompt, respuestas, evidencias completas sensibles, API key o payload proveedor.

## Observabilidad
Metricas agregadas de solicitudes, latencia, tokenizacion si no revela contenido, reintentos, rechazo y respuesta invalida; logs solo con job/documento opaco y codigo seguro.

## Estrategia de pruebas
### Validacion manual
Con doble Claude y CVs sinteticos, verificar minimizacion, respuesta valida, IDs faltantes/extra, rangos/estados invalidos, bloqueo, rate limit, timeout y fallo parcial.
### Automatizacion diferida
Schema/validadores unitarios, pruebas de sanitizacion, doble HTTP para clasificacion/retry y Testcontainers para atomicidad de persistencia; pruebas de prompt injection/no filtracion.

## Criterios de aceptacion
1. Claude recibe solo texto minimizado y requisitos snapshot.
2. No se persiste una respuesta parcial o invalida.
3. Una respuesta valida tiene exactamente una evaluacion valida por requisito.
4. Fallos de un CV no eliminan evaluaciones validas de otros.
5. Claude no puede influir formula, ranking o decisiones humanas.

## Riesgos y dependencias
Depende de 008 y disponibilidad/coste de Anthropic. Timeout y reintentos afectan coste, latencia y resultado reproducible de fallos.

## Decisiones / preguntas abiertas
- Ninguna pregunta abierta bloqueante.

## Definition of Ready
`READY_FOR_DEV`
