const baseUrl = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export type ApiError = { status: number; code?: string; message: string; correlationId?: string };

export class ApiFailure extends Error {
  constructor(public readonly detail: ApiError) { super(detail.message); }
}

function csrfToken() {
  return document.cookie.split("; ").find((cookie) => cookie.startsWith("XSRF-TOKEN="))?.split("=")[1];
}

export async function request<T>(path: string, init: RequestInit = {}, accessToken?: string): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  if (path.endsWith("/refresh") || path.endsWith("/logout")) {
    const csrf = csrfToken();
    if (csrf) headers.set("X-CSRF-TOKEN", decodeURIComponent(csrf));
  }
  const response = await fetch(`${baseUrl}/api/v1${path}`, { ...init, headers, credentials: "include" });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({ status: response.status, message: "No se pudo completar la solicitud." }));
    throw new ApiFailure({ status: response.status, message: detail.message ?? "No se pudo completar la solicitud.", code: detail.code, correlationId: detail.correlationId });
  }
  const contentType = response.headers.get("content-type");
  if (response.status === 204 || response.headers.get("content-length") === "0" || !contentType?.includes("application/json")) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}
