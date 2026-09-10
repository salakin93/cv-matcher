import { defineConfig, devices } from "@playwright/test";
import { frontendOrigin } from "./e2e/routes";

export default defineConfig({
  testDir: "./e2e",
  use: { baseURL: frontendOrigin, trace: "on-first-retry" },
  webServer: { command: "npm run dev", url: frontendOrigin, reuseExistingServer: true },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
