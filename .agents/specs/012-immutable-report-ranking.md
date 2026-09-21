# 012 - Reporte inmutable y ranking

## Objetivo
Publicar como maximo una version de reporte inmutable por job con ranking explicable, Top 5, umbral y advertencias seguras.

## Referencias
- `docs/prd-005-extraction-ai-scoring-ranking.md`, secciones 7--10.
- `docs/architecture.md`, secciones 4, 6--7, 9 y 11.
- Specs 004 y 009--011.

## Alcance
### Incluido
- Creacion inmutable de reporte desde candidatos seleccionados/evaluados, orden determinista, Top 5, lectura autorizada y finalizacion de job.
### Excluido
- Estado humano (PRD 006), descarga (007), filtros/exportaciones (008), perfil/historico/papelera/privacidad, UI y recalculo.

## Comportamiento y reglas
- Orden: mayor `totalScore`, `mandatoryScore`, cantidad de obligatorios `CUMPLE`, fecha de CV mas reciente e ID estable. Top 5 son los primeros cinco del mismo orden.
- Umbral entero 0--100, sugerido 70, viene del snapshot job; es indicador y nunca filtra candidatos.
- Reporte conserva snapshot de vacante/requisitos/umbral, candidatos seleccionados, scores, evaluaciones, modelo y version de prompt/algoritmo necesaria para reproducibilidad. Mutaciones posteriores no lo cambian.
- Si hay evaluados: `COMPLETED` o `COMPLETED_WITH_WARNINGS`. Si no hay candidato rankeable: `COMPLETED_WITH_WARNINGS`, sin reporte vacio, con advertencia segura. Cancelado no crea reporte.

## Contratos API
- `GET /api/v1/reports/{reportId}` y listado por vacante/job devuelven version, resumen, ranking, Top 5, umbral, scores, cumplimiento, evidencia/explicacion y advertencias permitidas.
- No devolver texto CV, sender, prompt/payload, tokens, hashes, rutas, IDs internos o documento. `404` seguro cuando no existe/no autorizado; no permitir editar/recalcular.

## Configuracion centralizada
No agrega configuracion. Reglas de ranking/score provienen de 010 y se conservan por version en snapshot; modelo actual se selecciona por configuracion central de 011 para jobs futuros.

## Datos y persistencia
Flyway agrega `report_version`, `report_candidate` y `requirement_assessment` inmutables, vinculados al job. Constraint garantiza maximo un reporte no vacio por job; transicion final y publicacion son atomicas. No copiar texto CV ni datos tecnicos.

## Integraciones
No llama proveedores. Consume resultados persistidos de 009--011 y actualiza job 004 en transaccion breve.

## Errores y estados
Una violacion de invariantes evita publicar reporte y lleva job a fallo seguro. Advertencias parciales no eliminan candidatos validos. Reporte terminado no admite PATCH, delete ni recalculate en este alcance.

## Seguridad y privacidad
Solo usuarios autenticados `RECRUITER`/`ADMIN` autorizados sobre datos compartidos pueden leer. Proteger identidad/evidencia segun minimizacion; controles posteriores de privacidad pueden anonimizar historicos sin cambiar score/estructura.

## Observabilidad
Metricas de reportes por resultado, candidatos rankeados, advertencias y duracion; logs con IDs opacos/job/correlation ID, sin PII, CV o proveedor.

## Estrategia de pruebas
### Validacion manual
Con resultados sinteticos, verificar todos los desempates, Top 5, umbral indicador, advertencias parciales, cero rankeables y que editar vacante/modelo no cambia reporte.
### Automatizacion diferida
Unitarias tabulares de orden/Top 5, integracion PostgreSQL de inmutabilidad/publicacion unica y API de autorizacion/redaccion; regresion de snapshots.

## Criterios de aceptacion
1. Ranking y Top 5 siguen exactamente el orden determinista backend.
2. Umbral persistido no oculta entradas.
3. Cada job publica a lo sumo un reporte inmutable cuando hay rankeables.
4. Sin rankeables termina `COMPLETED_WITH_WARNINGS` sin reporte vacio.
5. La lectura no expone texto CV, sender ni datos internos.

## Riesgos y dependencias
Depende de 004, 009--011. La política Claude está resuelta en 009: timeout de 30 segundos, hasta 3 reintentos y espera `Retry-After` máxima de 60 segundos por intento.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: reportes son snapshots inmutables; resultados externos no recalculan ni reordenan historicos.

## Definition of Ready
`READY_FOR_DEV`.
