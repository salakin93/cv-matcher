# Backlog de Ejecucion Backend y Frontend

## Estado

Plan de implementacion derivado de `prd-001` a `prd-011`, todos aprobados para
especificacion. No autoriza mezclar incrementos: cada tarea se inicia solo
cuando sus dependencias y su spec tecnica estan aprobadas.

## Regla de Ejecucion

1. Actualizar la spec tecnica afectada con el PRD aprobado.
2. Implementar backend y contratos OpenAPI.
3. Validar manualmente el flujo backend con datos sinteticos.
4. Implementar frontend contra OpenAPI aprobado.
5. Ejecutar revision tecnica, QA, seguridad/privacidad y release.
6. Crear un commit atomico solo despues de los gates.

## Bloque 0 - Alineacion de Especificaciones

| ID | Tarea | Salida | Dependencia |
| --- | --- | --- | --- |
| ARC-001 | Actualizar specs 001-004 con reglas de PRD 001-002. | Specs de identidad, vacante y job consistentes. | PRD 001-002 |
| ARC-002 | Actualizar specs 005-007 con conexion unica, filtro temporal de nombre, dos candidatos por mensaje, 500 MiB y errores parciales de AV/storage. | Specs Outlook e ingestion consistentes. | PRD 003-004 |
| ARC-003 | Actualizar specs 008-012 con minimizacion para Claude, candidatos anonimos, deduplicacion solo por correo, umbral por job y resultado sin candidatos. | Specs de analisis/ranking consistentes. | PRD 005 |
| ARC-004 | Crear o actualizar specs 013-024 con PRD 006-011. | Specs de acciones humanas, privacidad y operacion. | PRD 006-011 |
| ARC-005 | Revisar secuencia Flyway y contratos OpenAPI de todas las specs actualizadas. | Mapa de migraciones y contratos sin conflictos. | ARC-001 a ARC-004 |

## Backend - Fundacion y Acceso

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| BE-001 | Registro publico, verificacion, reenvios y respuestas neutrales. | 001 | ARC-001 |
| BE-002 | Login, refresh, logout, sesiones simultaneas, expiracion absoluta y bloqueo. | 001 | BE-001 |
| BE-003 | Recuperacion, cambio de contrasena y cambio de correo con revocacion de sesiones. | 001 | BE-002 |
| BE-004 | Provisionamiento primer ADMIN, administracion de cuentas, roles, activacion y proteccion del ultimo ADMIN. | 001 | BE-002 |
| BE-005 | Auditoria inmutable de seguridad y cuentas. | 001 | BE-001 a BE-004 |

## Backend - Vacantes y Jobs

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| BE-006 | CRUD compartido de vacantes, requisitos, fechas Bolivia/UTC, archivo y reactivacion. | 002 | BE-002 |
| BE-007 | Control optimista, advertencia de titulo duplicado y auditoria de vacantes. | 002 | BE-006 |
| BE-008 | Cola durable, snapshot de vacante y umbral, un job activo por vacante. | 002 | BE-006 |
| BE-009 | Estados, cancelacion, retry con mismo snapshot/umbral, leases e idempotencia. | 002 | BE-008 |
| BE-010 | Limite global configurable y detalle seguro de job/OpenAPI. | 002, 011 | BE-008 |

## Backend - Outlook e Ingestion

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| BE-011 | Conexion Outlook unica, OAuth server-side, estado seguro y reautorizacion ADMIN. | 003 | BE-002 |
| BE-012 | Discovery Inbox por rango snapshot, paginacion, IDs inmutables y limite operativo. | 003 | BE-009, BE-011 |
| BE-013 | Worker de adjuntos: nombre temporal, seleccion de dos candidatos por mensaje y limites de bytes. | 004 | BE-012 |
| BE-014 | Validacion real PDF/DOCX, deduplicacion por contenido, antivirus y cifrado privado. | 004 | BE-013 |
| BE-015 | Conteos, advertencias y errores parciales de ingestion sin filtrar datos. | 004 | BE-014 |

## Backend - Analisis y Ranking

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| BE-016 | Extraccion cifrada de texto PDF/DOCX y exclusion de texto no util. | 005 | BE-014 |
| BE-017 | Identidad por correo CV/remitente y candidatos anonimos por documento. | 005 | BE-016 |
| BE-018 | Cliente Claude server-side con texto minimizado y validacion atomica de contrato. | 005 | BE-016 |
| BE-019 | Score determinista, precision decimal, `NO_DEMOSTRADO` y snapshots. | 005 | BE-018 |
| BE-020 | Version inmutable, ranking, Top 5, advertencias y `NO_RANKABLE_CANDIDATES`. | 005 | BE-017, BE-019 |

## Backend - Acciones sobre Resultados

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| BE-021 | Estado humano por entrada de reporte, concurrencia y auditoria. | 006 | BE-020 |
| BE-022 | Descarga autenticada del CV snapshot, streaming seguro y cuota movil. | 007 | BE-020 |
| BE-023 | Filtros de ranking y exportacion PDF/XLSX minimizada. | 008 | BE-020 |
| BE-024 | Disponibilidad, correcciones de perfil y control de concurrencia. | 009 | BE-017 |
| BE-025 | Busqueda historica confirmada, tipo de job y version combinada. | 009 | BE-020, BE-024 |

## Backend - Retencion y Operacion

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| BE-026 | Papelera compartida, restauracion y purga automatica a 180 dias. | 010 | BE-014, BE-024 |
| BE-027 | Eliminacion por privacidad: bloqueo, borrado inmediato de PII y anonimizacion. | 010 | BE-026 |
| BE-028 | Configuracion allowlisted de modelo y concurrencia. | 011 | BE-010, BE-018 |
| BE-029 | Consulta administrativa de auditoria paginada y minimizada. | 011 | BE-005, BE-021 a BE-028 |
| BE-030 | Notificaciones in-app/correo idempotentes para estados terminales. | 011 | BE-009, BE-020, BE-028 |

## Frontend - Fundacion y Operacion

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| FE-001 | Cliente OpenAPI tipado, sesion en memoria, guards, manejo uniforme de errores. | 001 | BE-001, BE-002 |
| FE-002 | Registro, verificacion, login, logout, recuperacion, cambio de contrasena/correo. | 001 | FE-001, BE-003 |
| FE-003 | Administracion ADMIN de cuentas, roles, activacion y conflictos. | 001 | FE-001, BE-004 |
| FE-004 | Listado, formulario, requisitos, archivo/reactivacion y conflictos de vacantes. | 002 | FE-001, BE-006, BE-007 |
| FE-005 | Solicitud de job con umbral, estado, polling, cancelacion, retry y warnings. | 002-004 | FE-004, BE-008 a BE-015 |
| FE-006 | Panel ADMIN Outlook, inicio/reautorizacion OAuth y estado seguro. | 003 | FE-001, BE-011 |

## Frontend - Reportes y Ciclo de Vida

| ID | Tarea | PRD | Dependencias |
| --- | --- | --- | --- |
| FE-007 | Versiones, ranking, Top 5, umbral, evidencias y advertencias. | 005 | FE-005, BE-020 |
| FE-008 | Selector de estado humano con conflicto visible. | 006 | FE-007, BE-021 |
| FE-009 | Descarga protegida sin URL persistente y manejo de cuota. | 007 | FE-007, BE-022 |
| FE-010 | Filtros de reporte y exportacion PDF/XLSX. | 008 | FE-007, BE-023 |
| FE-011 | Perfil compartido, disponibilidad, correcciones y busqueda historica confirmada. | 009 | FE-007, BE-024, BE-025 |
| FE-012 | Papelera, restauracion, vencimiento y flujo ADMIN de privacidad. | 010 | FE-009, FE-011, BE-026, BE-027 |
| FE-013 | Configuracion ADMIN, auditoria y bandeja de notificaciones. | 011 | FE-003, BE-028 a BE-030 |

## Gates por Tarea

- La tarea tiene spec tecnica `READY_FOR_DEV` y contratos OpenAPI definidos.
- No se mezclan migraciones ni decisiones de otros bloques.
- Backend: validacion manual con datos sinteticos y `git diff --check`.
- Frontend: contrato OpenAPI actualizado, estados loading/empty/warning/error,
  responsive y accesible.
- Revision tecnica, QA y seguridad/privacidad aprobadas antes del commit atomico.

## Orden de Inicio

1. ARC-001 a ARC-005.
2. BE-001 a BE-010 y FE-001 a FE-004.
3. BE-011 a BE-015 y FE-005 a FE-006.
4. BE-016 a BE-020 y FE-007.
5. BE-021 a BE-030 y FE-008 a FE-013, respetando dependencias.
