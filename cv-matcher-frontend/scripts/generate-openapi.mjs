import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { loadEnv } from "vite";

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const environment = loadEnv(process.env.NODE_ENV ?? "development", frontendRoot, "VITE_");
const apiBaseUrl = environment.VITE_API_BASE_URL;

if (!apiBaseUrl) {
  console.error("No se pudo generar OpenAPI: falta VITE_API_BASE_URL.");
  process.exitCode = 1;
} else {
  const openapiTypeScript = resolve(frontendRoot, "node_modules/.bin", process.platform === "win32" ? "openapi-typescript.cmd" : "openapi-typescript");
  const result = spawnSync(openapiTypeScript, [`${new URL(apiBaseUrl).origin}/v3/api-docs`, "-o", "src/api/generated.ts"], { cwd: frontendRoot, stdio: "inherit" });
  process.exitCode = result.status ?? 1;
}
