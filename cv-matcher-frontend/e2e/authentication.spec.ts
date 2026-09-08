import { expect, test, type Page } from "@playwright/test";

const user = {
  id: "user-id",
  fullName: "Ana Reclutadora",
  email: "ana@example.test",
  role: "RECRUITER",
  status: "ACTIVE",
  forcePasswordChange: false,
};

async function mockAuth(page: Page, overrides: Record<string, { status: number; body?: object }> = {}) {
  await page.route("http://localhost:8080/api/v1/auth/**", async (route) => {
    const endpoint = new URL(route.request().url()).pathname.replace("/api/v1/auth", "");
    const response = overrides[endpoint] ?? (endpoint === "/refresh"
      ? { status: 401, body: { status: 401, code: "UNAUTHENTICATED", message: "No autenticado" } }
      : endpoint === "/login"
        ? { status: 200, body: { accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user, forcePasswordChange: false } }
        : { status: 204 });
    await route.fulfill({ status: response.status, contentType: response.body ? "application/json" : undefined, body: response.body ? JSON.stringify(response.body) : undefined });
  });
}

test("protege la ruta inicial cuando refresh está revocado", async ({ page }) => {
  await mockAuth(page);

  await page.goto("/");

  await expect(page).toHaveURL(/\/ingresar$/);
  await expect(page.getByRole("heading", { name: "Bienvenido" })).toBeVisible();
});

test("registra una cuenta con respuesta 202 vacía", async ({ page }) => {
  await mockAuth(page, { "/register": { status: 202 } });

  await page.goto("/registro");
  await page.getByLabel("Nombre completo").fill("Ana Reclutadora");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Crear cuenta" }).click();

  await expect(page.getByText("Revisa tu correo para continuar con la verificación.")).toBeVisible();
});

test("inicia y cierra sesión", async ({ page }) => {
  await mockAuth(page, { "/logout": { status: 204 } });

  await page.goto("/ingresar");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
  await expect(page.getByRole("heading", { name: "Hola, Ana Reclutadora" })).toBeVisible();
  await page.getByRole("button", { name: "Cerrar sesión" }).click();

  await expect(page).toHaveURL(/\/ingresar$/);
});

test("muestra un error seguro de credenciales", async ({ page }) => {
  await mockAuth(page, { "/login": { status: 401, body: { status: 401, code: "UNAUTHENTICATED", message: "Credenciales inválidas", correlationId: "correlation-id" } } });

  await page.goto("/ingresar");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Iniciar sesión" }).click();

  await expect(page.getByRole("alert")).toContainText("Código de seguimiento: correlation-id");
});

test("muestra validación segura de registro", async ({ page }) => {
  await mockAuth(page, { "/register": { status: 422, body: { status: 422, code: "VALIDATION_ERROR", message: "Revise los datos enviados." } } });

  await page.goto("/registro");
  await page.getByLabel("Nombre completo").fill("Ana Reclutadora");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Crear cuenta" }).click();

  await expect(page.getByRole("alert")).toContainText("Revise los datos enviados.");
});

test("evita doble envío de registro mientras la solicitud está pendiente", async ({ page }) => {
  await mockAuth(page);
  let finishRequest!: () => void;
  await page.route("http://localhost:8080/api/v1/auth/register", async (route) => {
    await new Promise<void>((resolve) => { finishRequest = resolve; });
    await route.fulfill({ status: 202 });
  });

  await page.goto("/registro");
  await page.getByLabel("Nombre completo").fill("Ana Reclutadora");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Crear cuenta" }).click();

  await expect(page.getByRole("button", { name: "Creando cuenta..." })).toBeDisabled();
  finishRequest();
  await expect(page.getByText("Revisa tu correo para continuar con la verificación.")).toBeVisible();
});

test("evita doble envío de cambio de contraseña mientras la solicitud está pendiente", async ({ page }) => {
  await mockAuth(page, { "/refresh": { status: 200, body: { accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user, forcePasswordChange: false } } });
  let finishRequest!: () => void;
  await page.route("http://localhost:8080/api/v1/auth/password/change", async (route) => {
    await new Promise<void>((resolve) => { finishRequest = resolve; });
    await route.fulfill({ status: 204 });
  });

  await page.goto("/cambiar-contrasena");
  await page.getByLabel("Contraseña actual").fill("ClaveSegura1");
  await page.getByLabel("Nueva contraseña").fill("ClaveNueva2");
  await page.getByRole("button", { name: "Actualizar contraseña" }).click();

  await expect(page.getByRole("button", { name: "Actualizando contraseña..." })).toBeDisabled();
  finishRequest();
  await expect(page).toHaveURL(/\/ingresar$/);
});
