# CV Matcher Frontend

SPA de CV Matcher construida con React 19, TypeScript, Vite, Tailwind CSS y shadcn/ui.

## Desarrollo

1. Configura el backend con `CORS_ALLOWED_ORIGINS=http://localhost:5173`.
2. Ejecuta `npm install`.
3. Con backend disponible en `http://localhost:8080`, ejecuta `npm run api:generate`. El archivo generado `src/api/generated.ts` se versiona y no se edita manualmente.
4. Ejecuta `npm run api:check` para comprobar que el artefacto contiene paths y operaciones generados.
4. Ejecuta `npm run dev`.

## Validación

Ejecuta `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` y `npm run e2e`.
Ejecuta `npm run check:cors` para confirmar de forma segura el origen local sin
imprimir secretos del `.env`.

Los tokens de acceso viven sólo en memoria. Las cookies de refresh y CSRF son gestionadas por el backend y nunca se persisten desde JavaScript.
