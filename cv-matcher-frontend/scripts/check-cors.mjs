import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { loadEnv } from "vite";

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const rootEnv = resolve(frontendRoot, "../.env");
const requiredOrigin = loadEnv(process.env.NODE_ENV ?? "development", frontendRoot, "VITE_").VITE_FRONTEND_ORIGIN;

if (!requiredOrigin) {
  console.error("CORS no verificado: falta VITE_FRONTEND_ORIGIN en el entorno frontend.");
  process.exitCode = 1;
} else if (!existsSync(rootEnv)) {
  console.error("CORS no verificado: falta el archivo .env local.");
  process.exitCode = 1;
} else {
  const line = readFileSync(rootEnv, "utf8").split(/\r?\n/).find((value) => value.startsWith("CORS_ALLOWED_ORIGINS="));
  const origins = line?.slice("CORS_ALLOWED_ORIGINS=".length).replace(/^['"]|['"]$/g, "").split(",").map((origin) => origin.trim()) ?? [];

  if (origins.includes(requiredOrigin)) {
    console.log("CORS local verificado para Vite.");
  } else {
    console.error("CORS no verificado: falta el origen local de Vite.");
    process.exitCode = 1;
  }
}
