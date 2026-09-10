import { defineConfig, loadEnv } from "vite";
import { fileURLToPath, URL } from "node:url";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig(({ mode }) => {
  const frontendOrigin = new URL(loadEnv(mode, process.cwd(), "VITE_").VITE_FRONTEND_ORIGIN);

  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
    server: { host: frontendOrigin.hostname, port: Number(frontendOrigin.port), strictPort: true },
  };
});
