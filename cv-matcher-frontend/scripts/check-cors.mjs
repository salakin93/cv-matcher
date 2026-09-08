import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const rootEnv = resolve(dirname(fileURLToPath(import.meta.url)), "../../.env");
const requiredOrigin = "http://localhost:5173";

if (!existsSync(rootEnv)) {
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
