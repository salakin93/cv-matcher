# CV Matcher Frontend

SPA de CV Matcher construida con React 19, TypeScript, Vite, Tailwind CSS y shadcn/ui.

## Desarrollo

1. Copia `.env.example` a `.env` y configura `VITE_API_BASE_URL` y `VITE_FRONTEND_ORIGIN`.
2. Configura el backend con `CORS_ALLOWED_ORIGINS` igual a `VITE_FRONTEND_ORIGIN`.
3. Ejecuta `npm install`.
4. Con el backend disponible en `VITE_API_BASE_URL`, ejecuta `npm run api:generate`. El archivo generado `src/api/generated.ts` se versiona y no se edita manualmente.
5. Ejecuta `npm run api:check` para comprobar que el artefacto contiene paths y operaciones generados.
6. Ejecuta `npm run dev`.

## Validación

Ejecuta `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` y `npm run e2e`.
Ejecuta `npm run check:cors` para confirmar de forma segura el origen local sin
imprimir secretos del `.env`.

Los tokens de acceso viven sólo en memoria. Las cookies de refresh y CSRF son gestionadas por el backend y nunca se persisten desde JavaScript.
