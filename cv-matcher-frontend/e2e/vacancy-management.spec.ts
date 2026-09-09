import { expect, test, type Page } from "@playwright/test";

const user = { id: "recruiter-id", fullName: "Ana Reclutadora", email: "ana@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false };
const detail = { id: "vacancy-id", title: "Backend Java", description: "Servicios Java", dateFrom: "2026-09-01", dateTo: "2026-09-15", status: "ACTIVE", version: 2, requirements: [{ id: "req-1", description: "Java", weight: 5, mandatory: true, position: 0 }], createdAt: "2026-09-01T12:00:00Z", updatedAt: "2026-09-01T12:00:00Z" };

async function mockAuth(page: Page) { await page.route("http://localhost:8080/api/v1/auth/**", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ accessToken: "access-token", tokenType: "Bearer", expiresIn: 900, user, forcePasswordChange: false }) })); }

test("un RECRUITER crea una vacante con requisitos ordenados", async ({ page }) => {
  await mockAuth(page); let body: unknown;
  await page.route("http://localhost:8080/api/v1/vacancies", async (route) => { body = route.request().postDataJSON(); await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(detail) }); });
  await page.goto("/vacantes/nueva");
  await page.getByLabel("Título").fill("Backend Java"); await page.locator("textarea").fill("Servicios Java");
  await page.getByLabel("Fecha inicial (Bolivia)").fill("2026-09-01"); await page.getByLabel("Fecha final (Bolivia)").fill("2026-09-15");
  await page.getByRole("group", { name: "Requisitos" }).getByLabel("Descripción").nth(0).fill("Java"); await page.getByRole("button", { name: "Añadir requisito" }).click(); await page.getByRole("group", { name: "Requisitos" }).getByLabel("Descripción").nth(1).fill("PostgreSQL");
  await page.getByRole("button", { name: "Crear vacante" }).click(); await expect.poll(() => body).toEqual({ title: "Backend Java", description: "Servicios Java", dateFrom: "2026-09-01", dateTo: "2026-09-15", expectedVersion: 0, requirements: [{ description: "Java", weight: 1, mandatory: true }, { description: "PostgreSQL", weight: 1, mandatory: false }] });
});

test("conserva el borrador ante VERSION_CONFLICT", async ({ page }) => {
  await mockAuth(page); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id", async (route) => { if (route.request().method() === "GET") await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(detail) }); else await route.fulfill({ status: 409, contentType: "application/json", body: JSON.stringify({ status: 409, code: "VERSION_CONFLICT", message: "Interno" }) }); });
  await page.goto("/vacantes/vacancy-id"); await page.getByLabel("Título").fill("Borrador local"); await page.getByRole("button", { name: "Guardar cambios" }).click();
  await expect(page.getByText("La vacante cambió mientras la editabas.")).toBeVisible(); await expect(page.getByLabel("Título")).toHaveValue("Borrador local"); await page.getByRole("button", { name: "Cancelar edición" }).click(); await expect(page).toHaveURL(/\/vacantes$/);
});

test("lista archivadas y confirma archivo", async ({ page }) => {
  await mockAuth(page); await page.route("http://localhost:8080/api/v1/vacancies?**", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [{ id: "vacancy-id", title: "Backend Java", status: "ACTIVE", version: 2, updatedAt: "2026-09-01T12:00:00Z" }], page: 0, size: 20, totalItems: 1, totalPages: 1 }) })); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(detail) })); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id/archive", async (route) => route.fulfill({ status: 204 }));
  await page.goto("/vacantes"); await page.getByRole("button", { name: "Backend Java" }).click(); await page.getByRole("button", { name: "Archivar" }).click(); await expect(page.getByRole("dialog")).toBeVisible(); await page.getByRole("button", { name: "Confirmar" }).click();
});

test("un 403 al archivar descarta el detalle ya cargado", async ({ page }) => {
  await mockAuth(page); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(detail) })); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id/archive", async (route) => route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ status: 403, code: "FORBIDDEN", message: "Interno" }) })); await page.route("http://localhost:8080/api/v1/vacancies?**", async (route) => route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ status: 403, code: "FORBIDDEN", message: "Interno" }) }));
  await page.goto("/vacantes/vacancy-id"); await expect(page.getByLabel("Título")).toHaveValue("Backend Java"); await page.getByRole("button", { name: "Archivar" }).click(); await page.getByRole("button", { name: "Confirmar" }).click();
  await expect(page).toHaveURL(/\/vacantes$/); await expect(page.getByText("Backend Java")).not.toBeVisible(); await expect(page.getByText("Servicios Java")).not.toBeVisible(); await expect(page.getByText("Java", { exact: true })).not.toBeVisible();
});

test("reactiva con expectedVersion y el diálogo se puede controlar con teclado", async ({ page }) => {
  await mockAuth(page); const archived = { ...detail, status: "ARCHIVED", version: 3 }; let body: unknown;
  await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id", async (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(archived) })); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id/reactivate", async (route) => { body = route.request().postDataJSON(); await route.fulfill({ status: 204 }); });
  await page.goto("/vacantes/vacancy-id"); const trigger = page.getByRole("button", { name: "Reactivar" }); await trigger.focus(); await trigger.press("Enter"); const cancel = page.getByRole("button", { name: "Cancelar" }); await expect(cancel).toBeFocused(); await page.keyboard.press("Shift+Tab"); await expect(page.getByRole("button", { name: "Confirmar" })).toBeFocused(); await page.keyboard.press("Escape"); await expect(trigger).toBeFocused(); await trigger.press("Enter"); await page.getByRole("button", { name: "Confirmar" }).click(); await expect.poll(() => body).toEqual({ expectedVersion: 3 });
});

test("pagina, filtra archivadas y muestra errores seguros", async ({ page }) => {
  await mockAuth(page); const queries: string[] = [];
  await page.route("http://localhost:8080/api/v1/vacancies?**", async (route) => { const url = new URL(route.request().url()); queries.push(url.search); const current = Number(url.searchParams.get("page")); await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [{ id: "vacancy-id", title: "Backend Java", status: "ACTIVE", version: 2, updatedAt: "2026-09-01T12:00:00Z" }], page: current, size: 20, totalItems: 40, totalPages: 2 }) }); });
  await page.goto("/vacantes"); await page.getByRole("button", { name: "Siguiente" }).click(); await expect(page.getByText("Página 2 de 2")).toBeVisible(); await page.getByLabel("Filtrar por estado de vacante").selectOption("ARCHIVED"); await expect(page.getByText("Página 1 de 2")).toBeVisible(); expect(queries.some((query) => query.includes("status=ARCHIVED") && query.includes("page=0"))).toBe(true);
});

for (const [status, code, message] of [[401, "UNAUTHENTICATED", "Bienvenido"], [403, "FORBIDDEN", "No tienes permisos"], [404, "VACANCY_NOT_FOUND", "ya no está disponible"], [422, "VALIDATION_ERROR", "Revisa los campos"], [409, "VACANCY_ARCHIVED", "No se puede editar"]] as const) {
  test(`maneja ${code} de forma segura`, async ({ page }) => {
    await mockAuth(page); await page.route("http://localhost:8080/api/v1/vacancies/vacancy-id", async (route) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify({ status, code, message: "Interno" }) })); await page.goto("/vacantes/vacancy-id"); if (status === 401) await expect(page.getByRole("heading", { name: "Bienvenido" })).toBeVisible(); else if (status === 403) { await expect(page).toHaveURL(/\/vacantes$/); await expect(page.getByText("Backend Java")).not.toBeVisible(); } else await expect(page.getByRole("alert")).toContainText(message);
  });
}
