import { createContext, type ReactNode, useContext, useEffect, useState } from "react";
import { ApiFailure, request } from "../api/client";
import type { components } from "../api/generated";

export type User = components["schemas"]["UserInfo"];
type TokenResponse = components["schemas"]["TokenResponse"];
type AuthContextValue = { user: User | null; token: string | null; loading: boolean; login(email: string, password: string): Promise<void>; logout(): Promise<void>; authenticatedRequest<T>(path: string, init?: RequestInit): Promise<T>; clearSession(): void };
const AuthContext = createContext<AuthContextValue | null>(null);
let refreshPromise: Promise<TokenResponse> | null = null;

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
  const clearSession = () => { setToken(null); setUser(null); };
  const apply = (response: TokenResponse) => { setToken(response.accessToken); setUser(response.user); };

  useEffect(() => {
    restoreSession().then(apply).catch(() => clearSession()).finally(() => setLoading(false));
  }, []);

  async function login(email: string, password: string) { apply(await request<TokenResponse>("/auth/login", { method: "POST", body: JSON.stringify({ email, password }) })); }
  async function logout() { try { await request<void>("/auth/logout", { method: "POST" }); } finally { clearSession(); } }
  async function authenticatedRequest<T>(path: string, init?: RequestInit) {
    if (!token) throw new ApiFailure({ status: 401, message: "La sesión expiró." });
    try { return await request<T>(path, init, token); } catch (error) { if (error instanceof ApiFailure && error.detail.status === 401) clearSession(); throw error; }
  }
  return <AuthContext.Provider value={{ user, token, loading, login, logout, authenticatedRequest, clearSession }}>{children}</AuthContext.Provider>;
}

export function useAuth() { const value = useContext(AuthContext); if (!value) throw new Error("useAuth debe usarse dentro de AuthProvider"); return value; }
