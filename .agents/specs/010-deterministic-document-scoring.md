# 010 - Scoring determinista por documento

## Objetivo
Calcular los puntajes reproducibles de un documento exclusivamente en backend a partir de evaluaciones Claude previamente validadas y snapshot de requisitos.

## Referencias
- `docs/prd-005-extraction-ai-scoring-ranking.md`, seccion 6.
- `docs/architecture.md`, secciones 4, 7 y 9.
- Spec 009.

## Alcance
### Incluido
- Formula de puntajes por documento, precision y persistencia ligada al snapshot.
### Excluido
- Llamada Claude, identidad/deduplicacion, ranking de personas, reporte, umbral/UI y cambios de requisitos.

## Comportamiento y reglas
- `mandatoryScore` es promedio ponderado de compatibilidades obligatorias. `optionalAverage` es promedio ponderado opcional; `optionalBonus=min(20, optionalAverage*0.20)`; `totalScore=min(100, mandatoryScore+optionalBonus)`.
- Sin obligatorios: `mandatoryScore=0`; sin opcionales: promedio/bonus `0`. Falta de evidencia obligatoria fuerza compatibilidad `0` y `NO_DEMOSTRADO`, pero permanece elegible.
- Pesos 1--5 del snapshot son autoridad. Conservar precision decimal y redondear valores finales a dos decimales; solo backend ejecuta la formula.

## Contratos API
No agrega API publica. Expone contrato interno de resultado para 011/012 con componentes de score, cantidad de obligatorios `CUMPLE` y version/identidad del snapshot; frontend no recalcula.

## Configuracion centralizada
La formula y limite de 20 son reglas de dominio versionadas, no configuracion externa ni parametro `ADMIN`. Referenciar SSOT de reglas en arquitectura/contexto.

## Datos y persistencia
Persistir componentes y total con referencia inmutable a evaluaciones y requisitos snapshot. No recalcular un resultado terminado ni mezclar requisitos actuales de vacante.

## Integraciones
No integra sistemas externos.

## Errores y estados
No calcular si falta el conjunto validado de evaluaciones; el documento conserva advertencia proveniente de 009 y queda fuera de resultados evaluados. Errores de integridad son seguros y no fabrican score.

## Seguridad y privacidad
Scores no incluyen texto CV ni payload Claude. Aplicar autorizacion solo cuando 012 los publique; este caso de uso interno no expone API.

## Observabilidad
Metricas agregadas de documentos calculados/incompletos y errores de invariantes; logs sin evidencia, identidad ni contenido.

## Estrategia de pruebas
### Validacion manual
Con evaluaciones sinteticas, comprobar pesos, sin obligatorios/opcionales, tope 20/100, `NO_DEMOSTRADO`, precision y repeticion con mismo snapshot.
### Automatizacion diferida
Unitarias tabulares de formula, bordes/precision e invariantes; integracion de persistencia inmutable.

## Criterios de aceptacion
1. Mismo snapshot y evaluaciones producen mismo resultado.
2. Bonus opcional no supera 20 y total no supera 100.
3. `NO_DEMOSTRADO` obligatorio aporta cero y no excluye el documento.
4. Claude/frontend no calculan ni sustituyen puntajes.

## Riesgos y dependencias
Depende de evaluaciones completas de 009. La version del algoritmo debe conservarse al evolucionar reglas futuras.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: score es regla determinista del modulo `analysis`, no una respuesta de proveedor.

## Definition of Ready
`READY_FOR_DEV`.
