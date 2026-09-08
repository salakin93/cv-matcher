import { createContext, type ReactNode, useContext, useEffect, useRef, useState } from "react";
import { ApiFailure, request } from "../api/client";
import type { components } from "../api/generated";

export type User = Required<components["schemas"]["UserInfo"]>;
type TokenResponse = components["schemas"]["TokenResponse"];
type AuthContextValue = { user: User | null; token: string | null; loading: boolean; login(email: string, password: string): Promise<void>; logout(): Promise<void>; authenticatedRequest<T>(path: string, init?: RequestInit): Promise<T>; clearSession(): void };
const AuthContext = createContext<AuthContextValue | null>(null);
let refreshPromise: Promise<TokenResponse> | null = null;

function isSessionResponse(response: TokenResponse): response is TokenResponse & { accessToken: string; user: User } {
  const { user } = response;
  return typeof response.accessToken === "string"
    && typeof user?.id === "string"
    && typeof user.fullName === "string"
    && typeof user.email === "string"
    && typeof user.role === "string"
    && typeof user.status === "string"
    && typeof user.forcePasswordChange === "boolean";
}

function restoreSession() {
  if (refreshPromise === null) {
    refreshPromise = request<TokenResponse>("/auth/refresh", { method: "POST" });
    void refreshPromise.then(
      () => { refreshPromise = null; },
      () => { refreshPromise = null; },
    );
  }
  return refreshPromise;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const sessionGeneration = useRef(0);
  const clearSession = () => {
    sessionGeneration.current += 1;
    setToken(null);
    setUser(null);
  };
  const apply = (response: TokenResponse) => {
    if (!isSessionResponse(response)) throw new ApiFailure({ status: 502, message: "La respuesta de sesión no es válida." });
    setToken(response.accessToken);
    setUser(response.user);
  };

  useEffect(() => {
    const generation = sessionGeneration.current;
    restoreSession()
      .then((response) => { if (sessionGeneration.current === generation) apply(response); })
      .catch(() => { if (sessionGeneration.current === generation) clearSession(); })
      .finally(() => setLoading(false));
  }, []);

  async function login(email: string, password: string) {
    const generation = sessionGeneration.current + 1;
    sessionGeneration.current = generation;
    const response = await request<TokenResponse>("/auth/login", { method: "POST", body: JSON.stringify({ email, password }) });
    if (sessionGeneration.current === generation) apply(response);
  }
  async function logout() {
    clearSession();
    await request<void>("/auth/logout", { method: "POST" });
  }
  async function authenticatedRequest<T>(path: string, init?: RequestInit) {
    if (!token) throw new ApiFailure({ status: 401, message: "La sesión expiró." });
    const generation = sessionGeneration.current;
    try {
      return await request<T>(path, init, token);
    } catch (error) {
      if (!(error instanceof ApiFailure) || error.detail.status !== 401) throw error;
      try {
        const refreshed = await restoreSession();
        if (sessionGeneration.current !== generation) throw new ApiFailure({ status: 401, message: "La sesión expiró." });
        apply(refreshed);
        return await request<T>(path, init, refreshed.accessToken);
      } catch (refreshError) {
        if (sessionGeneration.current === generation) clearSession();
        throw refreshError;
      }
    }
  }
  return <AuthContext.Provider value={{ user, token, loading, login, logout, authenticatedRequest, clearSession }}>{children}</AuthContext.Provider>;
}

export function useAuth() { const value = useContext(AuthContext); if (!value) throw new Error("useAuth debe usarse dentro de AuthProvider"); return value; }
