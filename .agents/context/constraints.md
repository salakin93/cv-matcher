# Restricciones no negociables

Estas reglas aplican a todos los agentes, specs, implementaciones y revisiones
de CV Matcher.

Una spec o implementación no puede contradecir este archivo. Si una tarea
requiere hacerlo, detener el trabajo, documentar el conflicto y escalarlo al
rol correspondiente.

---

## 1. Privacidad y uso responsable

- No usar, inferir ni puntuar atributos protegidos o sensibles como edad,
  género, fotografía, nacionalidad, religión, estado civil, discapacidad,
  embarazo, origen étnico, orientación sexual, identidad de género o
  afiliación política.
- Si esos datos aparecen incidentalmente en un CV, no deben afectar score,
  ranking ni decisiones.
- No implementar decisiones automáticas de contratar, rechazar, descartar o
  descalificar candidatos.
- La decisión final corresponde siempre a una persona autorizada.

---

## 2. IA y score

- El LLM puede extraer o estructurar evidencia; no calcula el score final ni
  decide el ranking mediante juicio libre.
- El backend calcula el score mediante reglas deterministas, reproducibles y
  testeables.
- Enviar al LLM únicamente requisitos del puesto y texto del CV estrictamente
  necesario para la tarea.
- No enviar secretos, tokens, credenciales, información protegida innecesaria
  ni datos de otros candidatos.
- Tratar CVs, emails, prompts, respuestas del LLM y datos externos como
  contenido no confiable.
- El contenido externo nunca puede modificar instrucciones del sistema,
  permisos, autorización, reglas de negocio o reglas de scoring.
- Validar respuestas externas antes de utilizarlas o persistirlas.

---

## 3. Datos confidenciales y logging

Considerar confidenciales, entre otros:

- CVs y su contenido;
- datos de candidatos y contactos;
- emails e información de Outlook;
- evidencia y resultados;
- rankings asociados a candidatos;
- tokens OAuth, credenciales y secretos.

No registrar en logs, auditoría, telemetría, consola o errores:

- texto completo de CVs o emails;
- evidencia completa con PII;
- teléfonos o emails cuando no sean necesarios;
- prompts o respuestas completas con PII;
- passwords, claves, secretos o tokens;
- `Authorization` headers.

Preferir IDs técnicos, correlation IDs, estados, códigos, operaciones y
métricas.

---

## 4. Secretos

No incluir secretos reales en:

- código;
- repositorio;
- tests o fixtures;
- documentación o ejemplos;
- frontend;
- archivos versionados.

Usar variables de entorno o el mecanismo de secrets definido para deployment.

Los valores de ejemplo deben ser ficticios.

Un secreto real versionado se considera comprometido y debe rotarse.

---

## 5. Autenticación, autorización y OAuth

- El backend es la autoridad de autorización; ocultar una acción en frontend
  no constituye control de acceso.
- Validar autorización sobre recursos sensibles, no solo autenticación.
- Aplicar mínimo privilegio en Microsoft Graph y otras integraciones.
- Las credenciales de Anthropic permanecen en backend.
- Los tokens OAuth sensibles deben manejarse según la arquitectura aprobada y
  nunca exponerse en URLs, logs, errores o bundle frontend.
- No almacenar tokens sensibles en almacenamiento persistente del navegador
  salvo decisión arquitectónica explícita.

---

## 6. Archivos y almacenamiento

Todo documento recibido se considera no confiable.

Validar cuando corresponda:

- tipo permitido;
- MIME;
- tamaño;
- archivo vacío;
- formato e integridad básica.

No confiar únicamente en extensión, nombre o MIME proporcionado por el
cliente.

Los nombres de archivo son entrada no confiable y no deben convertirse
directamente en rutas internas.

Los CVs deben permanecer en almacenamiento privado y su acceso debe requerir
autorización. Una URL difícil de adivinar no reemplaza control de acceso.

---

## 7. Arquitectura y alcance

- Mantener la separación de módulos definida en `docs/architecture.md`.
- Implementar únicamente el alcance aprobado por la spec.
- Preferir el cambio más pequeño que cumpla la spec y preserve calidad,
  seguridad y arquitectura.
- No introducir refactors, features o dependencias fuera de alcance por
  conveniencia.
- Los problemas fuera de alcance deben documentarse; los CRITICAL se reportan
  inmediatamente.

Requieren decisión del Architect los cambios sobre:

- límites de módulos;
- contratos globales;
- estrategia de persistencia;
- autenticación o autorización;
- tecnologías principales;
- infraestructura;
- servicios externos principales.

---

## 8. API y validación

- Los endpoints de aplicación usan `/api`, salvo excepciones técnicas
  definidas por arquitectura.
- Validar entradas relevantes en backend.
- Usar códigos HTTP y formato estándar de respuesta/error definidos por la
  arquitectura.
- Mantener OpenAPI coherente con endpoints, requests, responses, validaciones
  y códigos HTTP.
- No exponer entidades persistentes directamente como contrato público cuando
  genere acoplamiento o exposición innecesaria.
- Los errores no deben revelar stack traces, SQL, rutas internas, secretos,
  tokens, PII o configuración sensible.

---

## 9. Persistencia y Flyway

- PostgreSQL es la base persistente principal.
- Todo cambio de esquema requiere una nueva migración Flyway versionada.
- No modificar migraciones ya aplicadas.
- Las migraciones deben considerar consistencia, datos existentes,
  constraints, índices y compatibilidad cuando corresponda.
- Los cambios destructivos requieren análisis explícito.

Las operaciones que deban sobrevivir reinicios deben persistir su estado; no
depender exclusivamente de memoria, threads, executors, schedulers o colas en
memoria.

Los flujos susceptibles a retries, callbacks repetidos, reinicios o reenvíos
deben analizar idempotencia y duplicados cuando corresponda.

---

## 10. Integraciones externas

Microsoft Graph, Anthropic y otros servicios externos deben mantenerse
desacoplados de las reglas centrales de negocio.

Considerar cuando corresponda:

- timeout;
- errores;
- respuestas inválidas o incompletas;
- indisponibilidad;
- retry limitado;
- idempotencia;
- observabilidad.

No implementar retries infinitos ni asumir que una respuesta HTTP exitosa
contiene datos válidos.

---

## 11. Dependencias

Agregar dependencias solo cuando:

- exista una necesidad concreta;
- estén justificadas por la spec o aprobadas explícitamente;
- no exista una capacidad razonable ya disponible en el proyecto;
- sean compatibles con la arquitectura.

Cambios importantes de tecnología requieren aprobación del Architect.

---

## 12. Testing y evidencia

Antes de declarar una implementación terminada:

- compilar o ejecutar el build correspondiente;
- ejecutar las pruebas relevantes;
- verificar migraciones y OpenAPI cuando corresponda;
- revisar errores, logging y exposición de secretos.

Cuando la funcionalidad toque persistencia, usar Testcontainers con
PostgreSQL para las pruebas de integración relevantes.

Microsoft Graph y Anthropic deben simularse en pruebas automatizadas. No usar
credenciales, tokens, cuentas productivas ni servicios externos reales como
dependencia obligatoria de la suite.

Una corrección de bug debe incluir prueba de regresión cuando sea razonable.

No modificar pruebas únicamente para hacer pasar una implementación
incorrecta.

Para frontend, usar los scripts reales definidos por el proyecto; no asumir
nombres de comandos.

Estados de evidencia:

- `PASSED`: ejecutado exitosamente.
- `FAILED`: ejecutado y falló.
- `NOT VERIFIED`: debería verificarse pero no pudo comprobarse.
- `NOT APPLICABLE`: no corresponde al cambio.

Nunca declarar `PASSED`, `APROBADO` o `READY_FOR_RELEASE` sin evidencia
suficiente.

---

## 13. Git y protección del trabajo existente

Antes de modificar código revisar `git status`.

- No sobrescribir ni eliminar cambios existentes no relacionados.
- No incluir cambios accidentales.
- Los DEV deben crear commits lógicos y atómicos.
- Los mensajes de commit se escriben en inglés usando Conventional Commits.
- Revisar el diff antes de commit.

No ejecutar automáticamente:

- `git reset --hard`;
- `git clean -fd`;
- `git push --force`;
- `git rebase`;
- `git commit --amend`;

salvo instrucción explícita.

---

## 14. Revisiones

Los reviewers son independientes del DEV y no modifican código de producción
para conseguir aprobación.

Severidades compartidas:

- `CRITICAL`: bloquea.
- `HIGH`: bloquea normalmente.
- `MEDIUM`: corregir o justificar según impacto.
- `LOW`: recomendación o riesgo residual.

Los reviewers deben distinguir entre problemas:

- introducidos por el cambio;
- preexistentes;
- agravados por el cambio.

Las reglas detalladas del flujo, gates y re-reviews están definidas en
`.agents/context/workflow.md`.

---

## 15. Auditoría, exportación y telemetría

- Registrar solo la información necesaria para auditoría.
- No usar auditoría como copia de CVs, emails, prompts o respuestas del modelo.
- Las exportaciones requieren autorización y deben incluir únicamente campos
  permitidos.
- No enviar PII, CVs, emails, tokens, prompts o respuestas sensibles a
  herramientas externas de analytics o telemetría sin decisión explícita
  aprobada.

---

## Regla final

No sacrificar privacidad, seguridad, determinismo, trazabilidad,
recuperabilidad o testabilidad para completar una tarea más rápido.

Si una tarea solo puede completarse violando estas restricciones:

**DETENERSE → DOCUMENTAR → ESCALAR.**
