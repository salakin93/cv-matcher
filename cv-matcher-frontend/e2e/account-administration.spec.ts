import { expect, test, type Page } from "@playwright/test";
import { apiPath, apiRoute } from "./routes";

const admin = { id: "admin-id", fullName: "Ana Administradora", email: "admin@example.test", role: "ADMIN", status: "ACTIVE", forcePasswordChange: false };
const account = { id: "target-id", fullName: "Bruno Reclutador", email: "bruno@example.test", role: "RECRUITER", status: "ACTIVE", emailVerifiedAt: "2026-09-08T12:00:00Z", forcePasswordChange: false, updatedAt: "2026-09-08T12:00:00Z" };

async function mockAdmin(page: Page, accountResponse: { status: number; body?: object } = { status: 200, body: { items: [account], page: 0, size: 20, totalItems: 1, totalPages: 1 } }) {
  await page.route(apiRoute("/auth/**"), async (route) => {
    const endpoint = new URL(route.request().url()).pathname.replace(apiPath("/auth"), "");
    const response = endpoint === "/refresh" ? { status: 200, body: { accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user: admin, forcePasswordChange: false } } : { status: 204 };
    await route.fulfill({ status: response.status, contentType: response.body ? "application/json" : undefined, body: response.body ? JSON.stringify(response.body) : undefined });
  });
  await page.route(apiRoute("/admin/users?**"), async (route) => route.fulfill({ status: accountResponse.status, contentType: accountResponse.body ? "application/json" : undefined, body: accountResponse.body ? JSON.stringify(accountResponse.body) : undefined }));
}

test("un ADMIN lista, filtra y confirma el cambio antes de enviarlo", async ({ page }) => {
  await mockAdmin(page);
  let patches = 0;
  await page.route(apiRoute("/admin/users/target-id/status"), async (route) => { patches += 1; await route.fulfill({ status: 204 }); });
  await page.goto("/administracion/usuarios");
  await expect(page.getByRole("heading", { name: "Cuentas de usuarios" })).toBeVisible();
  await expect(page.getByText("bruno@example.test")).toBeVisible();
  await page.getByLabel("Filtrar por estado").selectOption("ACTIVE");
  await page.getByRole("button", { name: "Desactivar" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  expect(patches).toBe(0);
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect.poll(() => patches).toBe(1);
});

test("cambia un rol y recarga la página actual", async ({ page }) => {
  let lists = 0;
  await mockAdmin(page);
  await page.route(apiRoute("/admin/users/target-id/role"), async (route) => route.fulfill({ status: 204 }));
  await page.unroute(apiRoute("/admin/users?**"));
  await page.route(apiRoute("/admin/users?**"), async (route) => { lists += 1; await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [account], page: 0, size: 20, totalItems: 1, totalPages: 1 }) }); });
  await page.goto("/administracion/usuarios");
  await page.getByRole("button", { name: "Cambiar rol" }).click();
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect.poll(() => lists).toBeGreaterThan(1);
});

test("pagina y reinicia a la primera página al cambiar un filtro", async ({ page }) => {
  const queries: string[] = [];
  await page.route(apiRoute("/auth/**"), async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user: admin, forcePasswordChange: false }) }));
  await page.route(apiRoute("/admin/users?**"), async (route) => {
    const url = new URL(route.request().url());
    queries.push(url.search);
    const currentPage = Number(url.searchParams.get("page"));
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [account], page: currentPage, size: 20, totalItems: 40, totalPages: 2 }) });
  });
  await page.goto("/administracion/usuarios");
  await page.getByRole("button", { name: "Siguiente" }).click();
  await expect(page.getByText("Página 2 de 2")).toBeVisible();
  await page.getByLabel("Filtrar por rol").selectOption("ADMIN");
  await expect(page.getByText("Página 1 de 2")).toBeVisible();
  expect(queries.some((query) => query.includes("page=0") && query.includes("role=ADMIN"))).toBe(true);
});

test("muestra el estado vacío del listado", async ({ page }) => {
  await mockAdmin(page, { status: 200, body: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 } });
  await page.goto("/administracion/usuarios");
  await expect(page.getByText("No hay cuentas que coincidan con los filtros seleccionados.")).toBeVisible();
});

test("el diálogo contiene el foco, cierra con Escape y lo restaura al disparador", async ({ page }) => {
  await mockAdmin(page);
  await page.goto("/administracion/usuarios");
  const trigger = page.getByRole("button", { name: "Desactivar" });
  await trigger.focus();
  await trigger.press("Enter");
  const cancel = page.getByRole("button", { name: "Cancelar" });
  const confirm = page.getByRole("button", { name: "Confirmar" });
  await expect(cancel).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(confirm).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(cancel).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await expect(trigger).toBeFocused();
});

test("un conflicto administrativo se muestra de forma segura y no se reintenta", async ({ page }) => {
  await mockAdmin(page);
  let patches = 0;
  await page.route(apiRoute("/admin/users/target-id/status"), async (route) => { patches += 1; await route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ status: 409, code: "LAST_ACTIVE_ADMIN", message: "Interno", correlationId: "trace-123" }) }); });
  await page.goto("/administracion/usuarios");
  await page.getByRole("button", { name: "Desactivar" }).click();
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect(page.getByRole("alert")).toContainText("último administrador activo");
  expect(patches).toBe(1);
});

test("permite reintentar el listado después de un 403 sin conservar cuentas", async ({ page }) => {
  let requests = 0;
  await page.route(apiRoute("/auth/**"), async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user: admin, forcePasswordChange: false }) }));
  await page.route(apiRoute("/admin/users?**"), async (route) => {
    requests += 1;
    const response = requests <= 2 ? { status: 403, body: { status: 403, code: "FORBIDDEN", message: "Interno" } } : { status: 200, body: { items: [account], page: 0, size: 20, totalItems: 1, totalPages: 1 } };
    await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
  });
  await page.goto("/administracion/usuarios");
  await expect(page.getByRole("alert")).toContainText("No tienes permisos");
  await expect(page.getByText("bruno@example.test")).not.toBeVisible();
  await page.getByRole("button", { name: "Reintentar" }).click();
  await expect(page.getByText("bruno@example.test")).toBeVisible();
});

for (const [code, message] of [["VALIDATION_ERROR", "solicitud no es válida"], ["USER_NOT_FOUND", "cuenta ya no está disponible"]] as const) {
  test(`mantiene el diálogo y muestra ${code} junto a la confirmación`, async ({ page }) => {
    await mockAdmin(page);
    await page.route(apiRoute("/admin/users/target-id/status"), async (route) => route.fulfill({ status: code === "VALIDATION_ERROR" ? 422 : 404, contentType: "application/json", body: JSON.stringify({ status: code === "VALIDATION_ERROR" ? 422 : 404, code, message: "Interno" }) }));
    await page.goto("/administracion/usuarios");
    await page.getByRole("button", { name: "Desactivar" }).click();
    await page.getByRole("button", { name: "Confirmar" }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(page.getByRole("dialog").getByRole("alert")).toContainText(message);
  });
}

for (const [code, message] of [["SELF_ADMINISTRATION_FORBIDDEN", "No puedes cambiar tu propio rol"], ["EMAIL_NOT_VERIFIED", "debe verificar su correo"]] as const) {
  test(`muestra el conflicto ${code} sin reintentar`, async ({ page }) => {
    await mockAdmin(page);
    await page.route(apiRoute("/admin/users/target-id/status"), async (route) => route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ status: 409, code, message: "Interno" }) }));
    await page.goto("/administracion/usuarios");
    await page.getByRole("button", { name: "Desactivar" }).click();
    await page.getByRole("button", { name: "Confirmar" }).click();
    await expect(page.getByRole("dialog").getByRole("alert")).toContainText(message);
  });
}

test("una sesión revocada redirige a login sin mostrar datos administrativos", async ({ page }) => {
  let refreshes = 0;
  await page.route(apiRoute("/auth/refresh"), async (route) => {
    refreshes += 1;
    const response = refreshes === 1 ? { status: 200, body: { accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user: admin, forcePasswordChange: false } } : { status: 401, body: { status: 401, code: "UNAUTHENTICATED", message: "No autenticado" } };
    await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
  });
  await page.route(apiRoute("/admin/users?**"), async (route) => route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ status: 401, code: "UNAUTHENTICATED", message: "No autenticado" }) }));
  await page.goto("/administracion/usuarios");
  await expect(page).toHaveURL(/\/ingresar$/);
  await expect(page.getByText("Cuentas de usuarios")).not.toBeVisible();
});

test("un RECRUITER no puede abrir la ruta administrativa", async ({ page }) => {
  await page.route(apiRoute("/auth/**"), async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user: { ...admin, role: "RECRUITER" }, forcePasswordChange: false }) }));
  await page.goto("/administracion/usuarios");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: "Hola, Ana Administradora" })).toBeVisible();
  await expect(page.getByText("Cuentas de usuarios")).not.toBeVisible();
});
