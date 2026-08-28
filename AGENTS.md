# Guía para agentes de `cv-matcher`

Este archivo orienta a cualquier agente de IA que trabaje en este proyecto.

## Antes de empezar

1. Lee `README.md`, `.agents/context/` y la documentación disponible en `docs/`, si existe.
2. Revisa los archivos relacionados con la tarea antes de proponer cambios.
3. Conserva los cambios existentes del usuario; no reviertas trabajo ajeno.
4. Si un requisito no está claro o implica una decisión de producto, pregunta antes de asumirlo.

## Forma de trabajo

- Haz cambios pequeños y enfocados en la solicitud.
- Mantén el estilo, convenciones y estructura ya presentes en el repositorio.
- No incluyas secretos, tokens, contraseñas ni archivos `.env` en cambios o respuestas.
- Evita acciones destructivas como borrar directorios, reiniciar Git o sobrescribir configuraciones sin confirmación explícita.
- Al modificar código, ejecuta las comprobaciones o pruebas relevantes cuando estén disponibles.

## Documentación

- La guía funcional y técnica del proyecto debe mantenerse dentro de este repositorio, preferentemente en `README.md` o `docs/`.
- Actualiza la documentación cuando un cambio altere el uso, la configuración o la arquitectura.
- Para cambios funcionales importantes, crea o actualiza una especificación en `.agents/specs/` antes de implementar.

## Entrega

Al finalizar, indica de forma breve:

- qué se cambió;
- qué validación se ejecutó;
- cualquier pendiente, limitación o decisión que requiera revisión humana.
