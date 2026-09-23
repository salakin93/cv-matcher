# 003 - Gestión de vacantes

## Objetivo
Permitir gestionar vacantes compartidas con requisitos y rango de recepcion inmutables para futuros jobs.

## Referencias
- `docs/prd-002-vacancies-async-jobs.md`, secciones 1--4 y 11.
- `docs/architecture.md`, secciones 4, 6, 9 y 10.
- Spec 001.

## Alcance
### Incluido
- Crear, listar, consultar, reemplazar, archivar y reactivar vacantes `ACTIVE`/`ARCHIVED`.
- Validacion de requisitos, rango Bolivia-UTC, advertencia de titulo activo duplicado y auditoria efectiva.
### Excluido
- Crear o procesar jobs (004+), reportes, notificaciones y UI.

## Comportamiento y reglas
- `RECRUITER` y `ADMIN` activos operan datos compartidos. Titulo, descripcion, rango y 1--30 requisitos son obligatorios; peso entero 1--5, obligatorio/opcional y orden preservado. Requisitos repetidos o parecidos son validos si representan criterios diferentes.
- El rango de fechas cubre dias completos `America/La_Paz`, se persiste UTC y requiere inicio no posterior a fin.
- Solo `ACTIVE` se reemplaza completamente. Version optimista conserva primer cambio; segundo recibe conflicto y recarga.
- Titulo normalizado duplicado entre activas es advertencia, no bloqueo. Archivar/reactivar repetido no cambia datos ni audita.

## Contratos API
- `POST /api/v1/vacancies`, `GET /api/v1/vacancies`, `GET/PUT /api/v1/vacancies/{id}`, `POST /api/v1/vacancies/{id}/archive`, `POST /api/v1/vacancies/{id}/reactivate`.
- DTO incluye rango en fecha local y version; respuestas exponen advertencia `DUPLICATE_ACTIVE_TITLE` sin impedir `201`/`200`.
- Validacion retorna `422`; estado/version incompatible `409`; inexistente `404` seguro.

## Configuracion centralizada
La zona `America/La_Paz` es constante de negocio compartida en el modulo/SSOT definido por arquitectura, no entrada cliente ni ajuste ADMIN.

## Datos y persistencia
Flyway agrega `vacancy` y `vacancy_requirement`, UUID, instantes UTC, version y orden. La representacion permite snapshots posteriores sin que estos muten la vacante.

## Integraciones
No integra proveedores ni inicia jobs.

## Errores y estados
`ARCHIVED` conserva historial y rechaza edicion. Archivar no cancela un job activo; reactivar tampoco modifica su snapshot. La restriccion de crear un job mientras la vacante esta archivada o ya tiene uno activo pertenece a 004.

## Seguridad y privacidad
Requiere bearer valido y rol permitido. No incluye CV/PII; auditoria no almacena contenido libre de descripcion/requisito.

## Observabilidad
Logs estructurados de operaciones y metricas agregadas por estado; eventos de auditoria solo para crear, editar, archivar/reactivar efectivos.

## Estrategia de pruebas
### Validacion manual
Con cuentas ficticias, crear validas/invalidas, verificar conversion de ambos extremos Bolivia, advertencia duplicada, conflicto concurrente y archivo/reactivacion.
### Automatizacion diferida
Unitarias de rango/validacion, integracion Flyway/PostgreSQL y API de autorizacion, conflicto optimista y advertencia.

## Criterios de aceptacion
1. No se crea vacante sin requisitos validos o con rango invalido.
2. El rango guardado representa los dos dias completos en Bolivia.
3. Edicion concurrente conserva el primer cambio.
4. Una vacante archivada no se edita; reactivacion conserva datos.
5. Titulo activo duplicado advierte sin bloquear.

## Riesgos y dependencias
Depende de identidad. Los snapshots y restriccion de job activo son responsabilidad de 004.

## Decisiones / preguntas abiertas
- ARCHITECTURAL DECISION: conversion de fecha local a UTC es backend; la SPA no decide el rango efectivo.

## Definition of Ready
`READY_FOR_DEV`.
