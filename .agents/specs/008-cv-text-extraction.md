# 008 - Secure CV Text Extraction

## Objetivo

Entregar extracción segura y durable de texto de los documentos `AVAILABLE` de
la spec 007. El worker descifra temporalmente CVs PDF/DOCX privados, usa parsers
seguros con límites, valida que exista texto útil y persiste el resultado
cifrado para análisis posterior. No expone texto, no crea perfiles de candidato
y no llama a Claude.

## Referencias

- `docs/PRD.md`, secciones 4, 5 y 6.
- `docs/PRODUCT_BACKLOG.md`, Epics 3 y 4.
- `docs/architecture.md`, secciones 6, 7, 8 y 11.
- `.agents/context/project.md` y `.agents/context/constraints.md`.
- Specs 004, 007.

## Alcance

### Incluido

- Worker durable que continúa jobs `ANALYZING` con documentos disponibles.
- Descifrado temporal, extracción segura de texto de PDF y DOCX.
- Límites de bytes, páginas, caracteres, tiempo y memoria para parsers.
- Detección de texto útil y clasificación segura de PDF corrupto/protegido,
  DOCX inválido o documento sin texto.
- Persistencia cifrada AES-GCM de texto extraído y metadatos técnicos mínimos.
- Idempotencia, recuperación de lease y conteos de extracción por job.
- Puertos internos para que el futuro módulo `analysis` obtenga texto de un
  documento autorizado sin conocer storage, cifrado ni tablas internas.
- Auditoría mínima, métricas, OpenAPI de estado de job y pruebas con documentos
  sintéticos no personales.

### Excluido

- OCR de imágenes, soporte de formatos distintos de PDF/DOCX y extracción de
  contenido de correo.
- Mostrar, descargar, buscar, indexar o exportar texto extraído.
- Extracción de nombre, correo, teléfono, ubicación, habilidades o perfil de
  candidato; deduplicación, disponibilidad, papelera y privacidad.
- Claude, evaluación de requisitos, puntajes, ranking, reportes, decisiones
  humanas, notificaciones y UI.
- Cambiar los archivos originales, reescribir CVs o almacenar texto sin cifrar.

## Decisiones arquitectónicas

1. `document` conserva archivo y texto extraído. `analysis` consume un puerto
   como `DocumentTextPort`, limitado a documentos vinculados a un job reclamado;
   no lee `candidate_document`, filesystem ni claves de cifrado.
2. El texto de CV recibe controles equivalentes al original: AES-GCM, storage
   privado, acceso sólo server-side, sin logs ni API. No se almacena en índices
   de búsqueda ni blobs PostgreSQL en claro.
3. El descifrado y parser ocurren fuera de transacciones. Buffers y archivos
   temporales se eliminan en `finally`, aun ante timeout, cancelación o error.
4. La extracción se versiona por `extractor_version` y `content_sha256`. Una
   repetición con la misma versión/contenido es idempotente; un cambio futuro de
   extractor crea una revisión separada, sin sobrescribir evidencia previa.
5. Un documento sin texto útil es `IGNORED` para análisis con razón segura,
   pero conserva su original `AVAILABLE` para retención y futura descarga
   autorizada. No se borra ni se considera malware.

## Modelo y persistencia

Crear exclusivamente `V9__cv_text_extraction.sql`; no modificar V1–V8.

### `candidate_document_text`

| Columna | Regla |
| --- | --- |
| `id` | UUID PK. |
| `candidate_document_id` | FK no nula a documento `AVAILABLE`. |
| `content_sha256` | Hash del original usado para extracción. |
| `extractor_version` | Identificador no vacío de parser/política. |
| `text_ciphertext` | Texto AES-GCM cifrado, nunca público. |
| `text_key_version` | Entero no nulo. |
| `character_count` | Entero no negativo. |
| `language_hint` | `ES`, `EN` o `UNKNOWN`; sólo heurística técnica. |
| `status` | `EXTRACTED`, `IGNORED` o `FAILED`. |
| `reason_code` | Código seguro nullable. |
| `created_at`, `updated_at` | `timestamptz` UTC. |

Constraint único `(candidate_document_id, content_sha256, extractor_version)`.
`EXTRACTED` exige ciphertext y `character_count >= minUsefulCharacters`;
`IGNORED`/`FAILED` no contienen ciphertext. No persistir fragmentos, previews,
entidades personales ni texto en columnas auxiliares.

Agregar a `matching_job` conteos derivados `extracted_text_count`,
`ignored_text_count`, `failed_text_count` y `text_extraction_completed_at`.
Agregar eventos `TEXT_EXTRACTION_STARTED`, `TEXT_EXTRACTED`,
`TEXT_EXTRACTION_IGNORED`, `TEXT_EXTRACTION_FAILED` y
`TEXT_EXTRACTION_COMPLETED` a `matching_job_event`.

## Reglas de negocio

1. El worker reclama un job `ANALYZING` con lease y procesa sólo documentos
   `AVAILABLE` ya vinculados al job. Cancelación prevalece antes de descifrar,
   persistir o avanzar estado.
2. Antes de parser, valida integridad del ciphertext, hash y formato persistido.
   Un tag AES-GCM o hash inválido produce fallo seguro; jamás se entrega bytes a
   una librería parser si falló integridad.
3. Límites iniciales server-side: 50 MiB por documento, 300 páginas PDF,
   500.000 caracteres extraídos y 30 segundos de parser por documento. Al
   exceder, marca `IGNORED` con `TEXT_EXTRACTION_LIMIT_EXCEEDED`.
4. PDF protegido, corrupto o sin texto útil y DOCX corrupto/sin texto útil se
   clasifican `IGNORED` con código cerrado. Ningún mensaje del parser llega al
   cliente, auditoría o log estructurado.
5. Tras extracción válida, normaliza Unicode, elimina caracteres de control no
   imprimibles y conserva el texto completo cifrado; no resume, traduce, infiere
   identidad ni modifica requisitos de vacante.
6. Reintentos, reinicios, lease vencido y documentos repetidos consultan primero
   la constraint de versión/hash. Si ya hay resultado terminal, no vuelven a
   descifrar ni cambian conteos.
7. Cuando todos los documentos vinculados tienen resultado terminal, el job
   permanece `ANALYZING` como espera del futuro worker Claude. Si ninguno quedó
   `EXTRACTED`, el job llega a `FAILED` con `NO_USABLE_CV_TEXT`.
8. Falla de storage/cifrado/parser no recuperable lleva a `FAILED` con
   `TEXT_EXTRACTION_UNAVAILABLE`; errores transitorios tienen máximo tres
   intentos fuera de transacción y respetan cancelación.

## Errores y seguridad

El cliente sólo observa estados y conteos seguros de job. No existe endpoint de
texto. Códigos permitidos incluyen:

| Situación | Resultado seguro |
| --- | --- |
| PDF/DOCX corrupto o protegido | `IGNORED` / `DOCUMENT_UNREADABLE` |
| Sin texto útil | `IGNORED` / `NO_USABLE_TEXT` |
| Límite de parser | `IGNORED` / `TEXT_EXTRACTION_LIMIT_EXCEEDED` |
| Integridad/cifrado inválido | `FAILED` / `DOCUMENT_INTEGRITY_FAILURE` |
| Parser/storage no disponible | `FAILED` / `TEXT_EXTRACTION_UNAVAILABLE` |

- No loguear texto, caracteres, nombre de archivo, hash, ruta, stacktrace de
  parser, token, clave, buffer ni contenido de error de librería.
- La clave de texto puede ser la misma política de clave de documentos o una
  `CV_TEXT_ENCRYPTION_KEY` separada; versión y rotación son server-side.
- Puertos internos verifican autorización de job/documento antes de descifrar;
  no aceptar UUIDs o rutas arbitrarias desde API.

## Auditoría y observabilidad

Eventos por documento quedan en `matching_job_event`. Agregar una auditoría
`TEXT_EXTRACTION_COMPLETED` o `TEXT_EXTRACTION_FAILED` por resultado efectivo
del job, sin contenido o metadatos identificables.

Métricas sin PII:

- `documents.text_extraction` con `outcome` (`extracted`, `ignored`, `failed`);
- `documents.text_extraction_ignored` con `reason` del conjunto cerrado;
- `documents.text_extraction_duration` sin job, documento, tamaño o idioma como
  etiqueta.

## OpenAPI y configuración

- `JobDetail` documenta conteos y tiempo de extracción, no texto, hash,
  extractor interno, idioma detallado ni rutas.
- Propiedades server-side: versión de extractor, máximos de documento/páginas/
  caracteres/tiempo, mínimo de caracteres útiles y clave/version de cifrado.
- `test` usa PDFs/DOCX sintéticos sin PII y storage temporal cifrado; no permite
  OCR ni llamadas externas. `prod` falla rápido si parser seguro o clave faltan.

## Estrategia de pruebas

### Unitarias

- Integridad AES-GCM/hash, limpieza de buffers y temporales.
- PDF/DOCX sintéticos válidos, corruptos, protegidos, vacíos y límites.
- Normalización Unicode, texto útil, clasificación y redacción de excepciones.
- Idempotencia por hash/versión y máquina de estados.

### Integración Spring/PostgreSQL Testcontainers

- Dos workers/recovery procesan una vez cada documento de un job; cancelación
  evita cualquier resultado posterior.
- Texto válido se cifra y no aparece en tablas en claro, DTO, logs ni métricas.
- Documentos inválidos crean sólo códigos seguros; ninguno extraído produce
  `NO_USABLE_CV_TEXT`.
- Falla de descifrado, parser, storage y timeout no deja ciphertext parcial ni
  archivos temporales y conserva conteos coherentes.
- V9 desde V1–V8, OpenAPI, auditoría/métricas, `./gradlew test` y
  `git diff --check`.

## Criterios de aceptación

1. Sólo un worker reclama documentos de job y lease/replay/cancelación no
   duplican extracción ni continúan tras cancelar.
2. Sólo documentos `AVAILABLE` vinculados al job se descifran temporalmente;
   hash y tag inválidos no llegan al parser.
3. PDF/DOCX válidos producen texto cifrado; corruptos, protegidos, vacíos o que
   superan límites reciben códigos seguros sin exponer detalle de parser.
4. Texto extraído nunca aparece en PostgreSQL en claro, APIs, logs, auditoría,
   métricas, índices de búsqueda, archivos temporales persistentes ni UI.
5. Reintentos, reinicios y lease vencido son idempotentes por documento, hash y
   versión de extractor, conservando conteos correctos.
6. Cuando todos terminan, el job conserva `ANALYZING` para análisis futuro; sin
   texto utilizable termina `FAILED NO_USABLE_CV_TEXT`.
7. Límites de tamaño, páginas, caracteres y tiempo previenen consumo no acotado.
8. Puertos internos entregan texto sólo server-side a un módulo autorizado, sin
   exponer claves, storage ni detalles de persistencia.
9. Auditoría, OpenAPI, logs y métricas exponen sólo estados/conteos/códigos
   seguros, sin PII, hash, contenido o UUID como etiqueta.
10. V9 y pruebas Testcontainers con artefactos sintéticos cubren cifrado,
    parser, idempotencia, recuperación y errores sin OCR, candidatos, Claude,
    ranking, notificaciones, exportaciones o UI.

## Riesgos y dependencias

| Tipo | Detalle | Tratamiento |
| --- | --- | --- |
| Dependencia | Spec 007 entrega documentos cifrados `AVAILABLE`. | Consumir puerto de documento. |
| Riesgo | Parser vulnerable o PDF hostil. | Límites, timeout y parser aislado/seguro. |
| Riesgo | Texto filtrado por logs o DB. | Cifrado, DTOs mínimos y pruebas de ausencia. |
| Riesgo | Retry reextrae contenido costoso. | Constraint hash/versión y resultados terminales. |
| Dependencia futura | Falta evaluación Claude y perfiles. | Job espera `ANALYZING` tras extraer texto. |

## Definition of Ready

`READY_FOR_DEV`
