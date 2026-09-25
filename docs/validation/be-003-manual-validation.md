# Validacion manual BE-003

**Fecha:** 2026-09-24
**Entorno:** backend local Java 25, PostgreSQL 17 local y migraciones `V1` y `V2` aplicadas.

## Resultado

Los casos ejecutados completaron con los resultados esperados. Se usaron cuentas y datos ficticios; no se registran tokens, cookies ni identificadores de prueba.

| Caso | Resultado esperado | Resultado observado |
| --- | --- | --- |
| Crear vacante valida | `201`, `ACTIVE`, version inicial `0` | Aprobado |
| Rango Bolivia-UTC | Inicio y fin de dias completos en `America/La_Paz` | Aprobado mediante consulta SQL |
| Listar y consultar vacante | `200` con requisitos y fechas locales | Aprobado |
| Titulo activo duplicado | `201` con advertencia `DUPLICATE_ACTIVE_TITLE` | Aprobado |
| Sin requisitos | `422 VALIDATION_ERROR` | Aprobado |
| Rango invalido | `422 VALIDATION_ERROR` | Aprobado |
| Peso fuera de 1--5 | `422 VALIDATION_ERROR` | Aprobado |
| Reemplazar vacante activa | `200`, requisitos reemplazados y version incrementada | Aprobado |
| Conflicto optimista | `409 VACANCY_CONFLICT` con version anterior | Aprobado |
| Archivar vacante | `200`, estado `ARCHIVED` y version incrementada | Aprobado |
| Editar vacante archivada | `409 VACANCY_CONFLICT` | Aprobado |
| Repetir archivo | `200` sin cambiar version ni datos | Aprobado |
| Reactivar vacante | `200`, datos conservados y estado `ACTIVE` | Aprobado |
| Auditoria efectiva | Un evento por crear, editar, archivar y reactivar | Aprobado mediante consulta SQL |
| Vacantes sin bearer | `401 UNAUTHENTICATED` | Aprobado |
| Marca obligatorio/opcional ausente | `422 VALIDATION_ERROR` | Aprobado |

## Verificacion UTC

Para un rango local de `2026-09-01` a `2026-09-30`, PostgreSQL persistio:

```text
reception_start_utc = 2026-09-01 04:00:00+00
reception_end_utc   = 2026-10-01 03:59:59.999999+00
```
