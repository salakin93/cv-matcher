import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const generatedFile = resolve(dirname(fileURLToPath(import.meta.url)), "../src/api/generated.ts");

if (!existsSync(generatedFile)) {
  console.error("OpenAPI no verificado: falta src/api/generated.ts.");
  process.exitCode = 1;
} else {
  const generated = readFileSync(generatedFile, "utf8");
  if (generated.includes("export interface paths") && generated.includes("export interface operations")) {
    console.log("Tipos OpenAPI verificados con paths y operaciones.");
  } else {
    console.error("OpenAPI no verificado: el artefacto no contiene paths y operaciones generados.");
    process.exitCode = 1;
  }
}
