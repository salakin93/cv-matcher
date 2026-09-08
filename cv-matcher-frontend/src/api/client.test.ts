import { afterEach, describe, expect, it, vi } from "vitest";
import { request } from "./client";

describe("request", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("acepta un 202 sin cuerpo", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 202 })));

    await expect(request<void>("/auth/register", { method: "POST", body: "{}" })).resolves.toBeUndefined();
  });

  it("devuelve un cuerpo JSON exitoso", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ accessToken: "token" })));

    await expect(request<{ accessToken: string }>("/auth/login", { method: "POST", body: "{}" })).resolves.toEqual({ accessToken: "token" });
  });

  it("envía CSRF y credenciales al renovar la sesión", async () => {
    document.cookie = "XSRF-TOKEN=csrf-value";
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ accessToken: "token" }));
    vi.stubGlobal("fetch", fetchMock);

    await request("/auth/refresh", { method: "POST" });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/v1/auth/refresh");
    expect(init.credentials).toBe("include");
    expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("csrf-value");
  });

  it.each([401, 403, 409, 422])("preserva errores seguros %i", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ status, code: "SAFE_ERROR", message: "Mensaje seguro", correlationId: "correlation-id" }, { status })));

    await expect(request("/auth/login", { method: "POST", body: "{}" })).rejects.toMatchObject({
      detail: { status, code: "SAFE_ERROR", message: "Mensaje seguro", correlationId: "correlation-id" },
    });
  });
});
