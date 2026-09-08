import { StrictMode } from "react";
import { act, render, waitFor } from "@testing-library/react";
import { useAuth } from "./AuthProvider";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "./AuthProvider";

describe("AuthProvider", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("comparte el refresh inicial bajo StrictMode", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({
      accessToken: "access-token",
      forcePasswordChange: false,
      user: { id: "user-id", fullName: "Ana", email: "ana@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false },
    }));
    vi.stubGlobal("fetch", fetchMock);

    render(<StrictMode><AuthProvider><p>Aplicación</p></AuthProvider></StrictMode>);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  });

  it("renueva una sola vez y reintenta una solicitud autenticada tras 401", async () => {
    let authenticatedRequest: ReturnType<typeof useAuth>["authenticatedRequest"] | undefined;
    function Probe() {
      authenticatedRequest = useAuth().authenticatedRequest;
      return <p>Aplicación</p>;
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({
        accessToken: "first-token",
        forcePasswordChange: false,
        user: { id: "user-id", fullName: "Ana", email: "ana@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false },
      }))
      .mockResolvedValueOnce(Response.json({ status: 401, message: "No autenticado" }, { status: 401 }))
      .mockResolvedValueOnce(Response.json({
        accessToken: "second-token",
        forcePasswordChange: false,
        user: { id: "user-id", fullName: "Ana", email: "ana@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false },
      }))
      .mockResolvedValueOnce(Response.json({ accepted: true }));
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(authenticatedRequest).toBeDefined());

    await act(async () => {
      await expect(authenticatedRequest!("/protected")).resolves.toEqual({ accepted: true });
    });

    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Authorization")).toBe("Bearer first-token");
    expect(new Headers(fetchMock.mock.calls[3][1].headers).get("Authorization")).toBe("Bearer second-token");
  });

  it("descarta un refresh inicial tardío después de iniciar una sesión nueva", async () => {
    let login: ReturnType<typeof useAuth>["login"] | undefined;
    const state: { current: ReturnType<typeof useAuth>["user"] } = { current: null };
    let resolveRefresh!: (response: Response) => void;
    function Probe() {
      const auth = useAuth();
      login = auth.login;
      state.current = auth.user;
      return <p>Aplicación</p>;
    }
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveRefresh = resolve; }))
      .mockResolvedValueOnce(Response.json({
        accessToken: "new-token",
        forcePasswordChange: false,
        user: { id: "new-user", fullName: "Sesión nueva", email: "new@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false },
      }));
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(login).toBeDefined());
    await act(async () => { await login!("new@example.test", "ClaveSegura1"); });
    expect(state.current?.fullName).toBe("Sesión nueva");

    await act(async () => {
      resolveRefresh(Response.json({
        accessToken: "old-token",
        forcePasswordChange: false,
        user: { id: "old-user", fullName: "Sesión anterior", email: "old@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false },
      }));
    });
    await waitFor(() => expect(state.current?.fullName).toBe("Sesión nueva"));
  });

  it("descarta un refresh inicial tardío después de cerrar sesión", async () => {
    let logout: ReturnType<typeof useAuth>["logout"] | undefined;
    const state: { current: ReturnType<typeof useAuth>["user"] } = { current: null };
    let resolveRefresh!: (response: Response) => void;
    function Probe() {
      const auth = useAuth();
      logout = auth.logout;
      state.current = auth.user;
      return <p>Aplicación</p>;
    }
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveRefresh = resolve; }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    render(<AuthProvider><Probe /></AuthProvider>);
    await waitFor(() => expect(logout).toBeDefined());
    await act(async () => { await logout!(); });
    expect(state.current).toBeNull();

    await act(async () => {
      resolveRefresh(Response.json({
        accessToken: "old-token",
        forcePasswordChange: false,
        user: { id: "old-user", fullName: "Sesión anterior", email: "old@example.test", role: "RECRUITER", status: "ACTIVE", forcePasswordChange: false },
      }));
    });
    await waitFor(() => expect(state.current).toBeNull());
  });
});
