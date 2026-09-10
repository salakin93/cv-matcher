import { expect, test, type Page } from "@playwright/test";
import { apiPath, apiRoute } from "./routes";

const user = {
  id: "user-id",
  fullName: "Ana Reclutadora",
  email: "ana@example.test",
  role: "RECRUITER",
  status: "ACTIVE",
  forcePasswordChange: false,
};

async function mockAuth(page: Page, overrides: Record<string, { status: number; body?: object }> = {}) {
  await page.route(apiRoute("/auth/**"), async (route) => {
    const endpoint = new URL(route.request().url()).pathname.replace(apiPath("/auth"), "");
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

test("redirige al cambio de contraseña cuando la cuenta lo exige", async ({ page }) => {
  await mockAuth(page, {
    "/refresh": {
      status: 200,
      body: {
        accessToken: "access-token",
        tokenType: "Bearer",
        expiresIn: 900,
        user: { ...user, forcePasswordChange: true },
        forcePasswordChange: true,
      },
    },
  });

  await page.goto("/");

  await expect(page).toHaveURL(/\/cambiar-contrasena$/);
  await expect(page.getByRole("heading", { name: "Cambia tu contraseña" })).toBeVisible();
});

test("registra una cuenta con respuesta 202 vacía", async ({ page }) => {
  await mockAuth(page, { "/register": { status: 202 } });

  await page.goto("/registro");
  await page.getByLabel("Nombre completo").fill("Ana Reclutadora");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña", { exact: true }).fill("ClaveSegura1");
  await page.getByLabel("Confirma tu contraseña").fill("ClaveSegura1");
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
  await page.getByLabel("Contraseña", { exact: true }).fill("ClaveSegura1");
  await page.getByLabel("Confirma tu contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Crear cuenta" }).click();

  await expect(page.getByRole("alert")).toContainText("Revise los datos enviados.");
});

test("evita doble envío de registro mientras la solicitud está pendiente", async ({ page }) => {
  await mockAuth(page);
  let finishRequest!: () => void;
  await page.route(apiRoute("/auth/register"), async (route) => {
    await new Promise<void>((resolve) => { finishRequest = resolve; });
    await route.fulfill({ status: 202 });
  });

  await page.goto("/registro");
  await page.getByLabel("Nombre completo").fill("Ana Reclutadora");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña", { exact: true }).fill("ClaveSegura1");
  await page.getByLabel("Confirma tu contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Crear cuenta" }).click();

  await expect(page.getByRole("button", { name: "Creando cuenta..." })).toBeDisabled();
  finishRequest();
  await expect(page.getByText("Revisa tu correo para continuar con la verificación.")).toBeVisible();
});

test("evita doble envío de cambio de contraseña mientras la solicitud está pendiente", async ({ page }) => {
  await mockAuth(page, { "/refresh": { status: 200, body: { accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user, forcePasswordChange: false } } });
  let finishRequest!: () => void;
  await page.route(apiRoute("/auth/password/change"), async (route) => {
    await new Promise<void>((resolve) => { finishRequest = resolve; });
    await route.fulfill({ status: 204 });
  });

  await page.goto("/cambiar-contrasena");
  await page.getByLabel("Contraseña actual").fill("ClaveSegura1");
  await page.getByLabel("Nueva contraseña", { exact: true }).fill("ClaveNueva2");
  await page.getByLabel("Confirma la nueva contraseña").fill("ClaveNueva2");
  await page.getByRole("button", { name: "Actualizar contraseña" }).click();

  await expect(page.getByRole("button", { name: "Actualizando contraseña..." })).toBeDisabled();
  finishRequest();
  await expect(page).toHaveURL(/\/ingresar$/);
});

test("valida localmente la política y confirmación de contraseña", async ({ page }) => {
  await mockAuth(page);

  await page.goto("/registro");
  await page.getByLabel("Nombre completo").fill("Ana Reclutadora");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña", { exact: true }).fill("insegura");
  await page.getByLabel("Confirma tu contraseña").fill("diferente");
  await page.getByRole("button", { name: "Crear cuenta" }).click();

  await expect(page.getByRole("alert")).toContainText("La contraseña debe tener al menos 8 caracteres");
});

test("completa verificación, reenvío y recuperación sin revelar la cuenta", async ({ page }) => {
  await mockAuth(page);

  await page.goto("/reenviar-verificacion");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByRole("button", { name: "Continuar" }).click();
  await expect(page.getByRole("status")).toContainText("Si corresponde");

  await page.goto("/verificar-correo");
  await page.getByLabel("Token recibido").fill("token-de-prueba");
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect(page.getByRole("status")).toContainText("operación se completó");

  await page.goto("/recuperar");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByRole("button", { name: "Continuar" }).click();
  await expect(page.getByRole("status")).toContainText("Si corresponde");

  await page.goto("/restablecer-contrasena");
  await page.getByLabel("Token recibido").fill("token-de-prueba");
  await page.getByLabel("Nueva contraseña", { exact: true }).fill("ClaveNueva2");
  await page.getByLabel("Confirma la nueva contraseña").fill("ClaveNueva2");
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect(page.getByRole("status")).toContainText("operación se completó");
});

test("renueva la sesión y reintenta una operación autenticada tras 401", async ({ page }) => {
  await mockAuth(page, {
    "/refresh": { status: 200, body: { accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user, forcePasswordChange: false } },
  });
  let attempts = 0;
  await page.route(apiRoute("/auth/email-change/request"), async (route) => {
    attempts += 1;
    await route.fulfill(attempts === 1
      ? { status: 401, contentType: "application/json", body: JSON.stringify({ status: 401, message: "No autenticado" }) }
      : { status: 202 });
  });

  await page.goto("/cambiar-correo");
  await page.getByLabel("Correo").fill("nuevo@example.test");
  await page.getByLabel("Contraseña actual").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Continuar" }).click();

  await expect(page.getByRole("status")).toContainText("Si corresponde");
  await expect.poll(() => attempts).toBe(2);
  await expect(page.getByLabel("Contraseña actual")).toHaveValue("");
});

test("descarta el refresh inicial tardío después de un login", async ({ page }) => {
  await mockAuth(page);
  let releaseRefresh!: () => void;
  await page.route(apiRoute("/auth/refresh"), async (route) => {
    await new Promise<void>((resolve) => { releaseRefresh = resolve; });
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        accessToken: "old-token",
        tokenType: "Bearer",
        expiresIn: 900,
        user: { ...user, fullName: "Sesión anterior" },
        forcePasswordChange: false,
      }),
    });
  });

  await page.goto("/ingresar");
  await page.getByLabel("Correo").fill("ana@example.test");
  await page.getByLabel("Contraseña").fill("ClaveSegura1");
  await page.getByRole("button", { name: "Iniciar sesión" }).click();

  releaseRefresh();
  await expect(page.getByRole("heading", { name: "Hola, Ana Reclutadora" })).toBeVisible();
  await expect(page.getByText("Hola, Sesión anterior")).not.toBeVisible();
});
