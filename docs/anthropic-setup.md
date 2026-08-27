# Configuración de Anthropic Claude (Matching de CVs)

Guía de configuración del módulo de IA que evalúa CVs contra los requisitos del puesto. Contexto funcional en [PRD.md](./PRD.md) secciones 6.2, 6.3 y RF-05.

---

## 1. Cuenta y credenciales

- Crear una cuenta de Anthropic con **presupuesto mensual definido** (dependencia previa a producción, ver PRD sección 11).
- Generar **API keys separadas para staging y producción**, cada una con su propio límite de gasto si la consola lo permite, para que un error en staging no consuma presupuesto de producción.
- Las keys se inyectan como variable de entorno del backend, nunca en el frontend ni en el repositorio (ver [deployment.md](./deployment.md)).

```
ANTHROPIC_API_KEY=
ANTHROPIC_MODEL=claude-sonnet-5
```

---

## 2. Modelo

- **Modelo inicial aprobado:** `claude-sonnet-5`.
- Una vez validada la calidad con CVs reales, se puede evaluar `claude-haiku-4-5` para procesamiento masivo (menor costo, mayor velocidad), **sin cambiar el contrato de salida (JSON Schema) ni la fórmula de score**. El cambio de modelo debe quedar registrado por evaluación en `candidate_evaluation.model_name` para trazabilidad y comparación de calidad entre modelos.
- El nombre del modelo se configura por variable de entorno, no hardcodeado, para poder cambiarlo sin desplegar código nuevo.

---

## 3. Qué se envía al modelo

- Únicamente: (a) el texto de los requisitos de la versión de búsqueda activa, y (b) el texto extraído del CV (máximo 30.000 caracteres, priorizando experiencia, educación y habilidades si el CV excede el límite).
- **Nunca se envían:** foto, metadatos del archivo no relevantes, ni ningún dato que permita inferir atributos protegidos (edad, género, nacionalidad, religión, estado civil, discapacidad, embarazo — ver PRD principio 2).
- El texto del CV no se registra en logs de la aplicación ni en los logs del cliente HTTP hacia Anthropic.

---

## 4. Prompt y versionado

- El prompt vive versionado en el código (o en una tabla de configuración) con un identificador `prompt_version` que se persiste junto a cada evaluación (`candidate_evaluation.prompt_version`), para poder auditar qué prompt generó qué resultado y comparar cambios.
- El prompt debe instruir explícitamente al modelo:
  - Evaluar cada requisito de forma independiente y devolver evidencia textual (máx. 2 citas de 300 caracteres por requisito).
  - Nunca emitir un juicio de "contratar" o "rechazar" — solo evidencia y nivel de cumplimiento.
  - No inferir ni comentar sobre atributos protegidos aunque aparezcan en el CV.
  - Extraer nombre, correo y teléfono del candidato cuando estén presentes en el texto (usados para deduplicación, ver [database.md](./database.md) sección 3).

---

## 5. Contrato de salida (JSON Schema)

Estructura esperada de la respuesta:

```json
{
  "candidateName": "string",
  "candidateEmail": "string | null",
  "candidatePhone": "string | null",
  "summary": "string, máx 600 caracteres",
  "strengths": ["string", "..."],
  "gaps": ["string", "..."],
  "requirements": [
    {
      "requirementId": "string",
      "level": "CUMPLE | PARCIAL | NO_EVIDENCIA | NO_APLICA",
      "evidence": ["string, máx 300 caracteres", "hasta 2 elementos"],
      "reason": "string",
      "confidence": 0.0
    }
  ]
}
```

- `requirementId` debe corresponder exactamente a un `job_requirement.id` de la versión activa; el backend rechaza y reintenta si referencia un id inexistente.
- `confidence` es un número entre 0 y 1 — **es puramente informativo y nunca participa en el cálculo del score** (que depende solo de `level` y el peso configurado, ver PRD sección 6.2). Su único uso es:
  1. Mostrarse junto a cada evidencia en el detalle del candidato, para que el operador entienda qué tan segura estuvo la IA de esa evidencia puntual.
  2. Promediarse por candidato (`candidate_evaluation.confidence_avg`); si el promedio es menor a `0.4` (configurable en `job_search_version.scoring_config_json`), el ranking marca visualmente al candidato como "confianza baja — revisar evidencia manualmente", sin cambiar su score ni su posición en el orden.

---

## 6. Validación, reintentos y errores

| Situación | Manejo |
|---|---|
| JSON no cumple el schema | Reintentar la llamada una vez con el mismo prompt; si vuelve a fallar, marcar el CV como `ERROR_EVALUACION` y continuar con el resto del lote. |
| `429` / `5xx` de la API de Anthropic | Reintento con backoff, respetando límites de tasa; no bloquea otros CVs del lote. |
| Timeout de red | Igual tratamiento que `5xx`: reintento con backoff antes de marcar error. |
| `requirementId` inexistente en la respuesta | Se descarta esa evidencia puntual y se registra como inconsistencia; si toda la respuesta es inconsistente, se trata como JSON inválido. |

Registrar siempre, junto al resultado: `model_name`, `prompt_version` y conteo de tokens usados (para control de costo, ver sección 7).

---

## 7. Control de costo

- Con 150 CVs por lote como máximo y textos truncados a 30.000 caracteres, estimar el costo por lote antes de ir a producción (Feature 6.2, T6.2.3 del PRD) usando el precio vigente del modelo elegido.
- Registrar tokens de entrada/salida por evaluación para poder proyectar el gasto mensual según volumen real de búsquedas.
- Configurar alerta de costo estimado acumulado (ver [deployment.md](./deployment.md), monitoreo) para detectar desvíos frente al presupuesto mensual definido con RR.HH./Legal.

---

## 8. Aviso obligatorio en la interfaz

Todo resultado generado con este módulo debe mostrarse junto al texto fijo (ver PRD sección 10 / architecture.md sección 2):

> "Resultado asistido por IA. Revise la evidencia antes de tomar decisiones de selección."
