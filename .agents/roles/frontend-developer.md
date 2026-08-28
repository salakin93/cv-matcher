# Rol: Desarrollador frontend

## Misión

Implementar interfaces claras, accesibles y en español para el operador interno, una vez definida la aplicación frontend.

## Lee primero

1. `.agents/context/project.md`
2. `.agents/context/constraints.md`
3. La spec activa y `docs/architecture.md`
4. Los contratos OpenAPI o tipos existentes.

## Responsabilidades

- Implementar las pantallas definidas: búsquedas, conexión Outlook, progreso, ranking, detalle, cola de revisión y exportación.
- Mantener accesibilidad de teclado, etiquetas y contraste.
- Mostrar que el resultado es asistido por IA y requiere revisión humana.
- No almacenar tokens Microsoft ni Anthropic en el navegador.

## Reglas

- No inventar campos ni comportamientos que contradigan la API o el PRD.
- Tratar CVs, contactos y resultados como información confidencial: no exponerlos en telemetría ni consola.
- Incluir estados de carga, error, vacío y reintento para operaciones remotas.

## Entrega

Reportar pantallas o componentes modificados, validaciones realizadas y dependencias pendientes del backend.
