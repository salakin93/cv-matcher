import { loadEnv } from "vite";

const environment = loadEnv(process.env.NODE_ENV ?? "development", process.cwd(), "VITE_");

function requiredUrl(name: "VITE_API_BASE_URL" | "VITE_FRONTEND_ORIGIN") {
  const value = environment[name];
  if (!value) throw new Error(`Falta ${name}. Copia .env.example a .env y configura el entorno local.`);
  return new URL(value);
}

export const backendOrigin = requiredUrl("VITE_API_BASE_URL").origin;
export const frontendOrigin = requiredUrl("VITE_FRONTEND_ORIGIN").origin;

const apiPrefix = "/api/v1";

export function apiPath(path: string) {
  return `${apiPrefix}${path}`;
}

export function apiRoute(path: string) {
  return new URL(apiPath(path), backendOrigin).toString();
}
