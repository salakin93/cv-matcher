import { useEffect, useRef, useState } from "react";
import type { components } from "../api/generated";
import { ApiFailure } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { Button, Card, Notice } from "../components/ui";

type Role = "RECRUITER" | "ADMIN";
type Status = "PENDING_VERIFICATION" | "ACTIVE" | "DISABLED";
type Account = Required<components["schemas"]["AccountSummary"]>;
type AccountPage = Omit<Required<components["schemas"]["AccountPage"]>, "items"> & { items: Account[] };
type Change = { account: Account; kind: "role"; value: Role } | { account: Account; kind: "status"; value: Status };

const pageSize = 20;
const formatDate = new Intl.DateTimeFormat("es-BO", { dateStyle: "medium", timeStyle: "short", timeZone: "America/La_Paz" });

function readableError(error: unknown) {
  if (!(error instanceof ApiFailure)) return "No se pudo completar la operación. Inténtalo nuevamente.";
  const messages: Record<string, string> = {
    LAST_ACTIVE_ADMIN: "No se puede desactivar ni cambiar el rol del último administrador activo.",
    SELF_ADMINISTRATION_FORBIDDEN: "No puedes cambiar tu propio rol ni estado desde esta pantalla.",
    EMAIL_NOT_VERIFIED: "La cuenta debe verificar su correo antes de poder activarse.",
    USER_NOT_FOUND: "La cuenta ya no está disponible. Actualiza la lista e inténtalo nuevamente.",
    VALIDATION_ERROR: "La solicitud no es válida. Revisa los datos e inténtalo nuevamente.",
  };
  const message = error.detail.code ? messages[error.detail.code] : undefined;
  const fallback = error.detail.status === 403 ? "No tienes permisos para administrar cuentas." : "No se pudo completar la operación.";
  return `${message ?? fallback}${error.detail.correlationId ? ` Código de seguimiento: ${error.detail.correlationId}` : ""}`;
}

function labelRole(role: Role) { return role === "ADMIN" ? "Administrador" : "Reclutador"; }
function labelStatus(status: Status) { return status === "ACTIVE" ? "Activa" : status === "DISABLED" ? "Desactivada" : "Pendiente de verificación"; }

export function AccountAdministrationPage() {
  const { authenticatedRequest } = useAuth();
  const [role, setRole] = useState<"" | Role>("");
  const [status, setStatus] = useState<"" | Status>("");
  const [page, setPage] = useState(0);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<AccountPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [mutationError, setMutationError] = useState("");
  const [change, setChange] = useState<Change | null>(null);
  const [pending, setPending] = useState(false);
  const requestId = useRef(0);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLElement | null>(null);
  const pendingRef = useRef(false);

  useEffect(() => {
    let current = true;
    const id = ++requestId.current;
    const query = new URLSearchParams({ page: String(page), size: String(pageSize) });
    if (role) query.set("role", role);
    if (status) query.set("status", status);
    setLoading(true);
    setError("");
    void authenticatedRequest<AccountPage>(`/admin/users?${query.toString()}`)
      .then((response) => { if (current && requestId.current === id) setResult(response); })
      .catch((cause: unknown) => {
        if (!current || requestId.current !== id) return;
        setResult(null);
        setError(readableError(cause));
      })
      .finally(() => { if (current && requestId.current === id) setLoading(false); });
    return () => { current = false; };
  }, [authenticatedRequest, page, reload, role, status]);

  useEffect(() => {
    if (!change) return;
    const dialog = dialogRef.current;
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>("button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])") ?? []);
    const close = () => { if (!pendingRef.current) { setMutationError(""); setChange(null); } };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); close(); return; }
      if (event.key !== "Tab") return;
      const elements = focusable();
      if (elements.length === 0) { event.preventDefault(); return; }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const focusTimer = window.setTimeout(() => focusable()[0]?.focus());
    document.addEventListener("keydown", handleKeyDown);
    return () => { window.clearTimeout(focusTimer); document.removeEventListener("keydown", handleKeyDown); triggerRef.current?.focus(); };
  }, [change]);

  const openRoleChange = (account: Account) => { triggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setMutationError(""); setChange({ account, kind: "role", value: account.role === "ADMIN" ? "RECRUITER" : "ADMIN" }); };
  const openStatusChange = (account: Account) => { triggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setMutationError(""); setChange({ account, kind: "status", value: account.status === "ACTIVE" ? "DISABLED" : "ACTIVE" }); };
  const confirmChange = async () => {
    if (!change) return;
    pendingRef.current = true;
    setPending(true);
    setMutationError("");
    try {
      const path = change.kind === "role" ? `/admin/users/${encodeURIComponent(change.account.id)}/role` : `/admin/users/${encodeURIComponent(change.account.id)}/status`;
      const body = change.kind === "role" ? { role: change.value } : { status: change.value };
      await authenticatedRequest<void>(path, { method: "PATCH", body: JSON.stringify(body) });
      setChange(null);
      setReload((value) => value + 1);
    } catch (cause) {
      setMutationError(readableError(cause));
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  };
  const totalPages = result?.totalPages ?? 0;
  const canGoBack = page > 0 && !loading;
  const canGoForward = page + 1 < totalPages && !loading;

  return <main className="mx-auto min-h-screen max-w-7xl px-4 py-8 sm:px-6">
    <header className="mb-8 flex flex-wrap items-center justify-between gap-4">
      <div><a href="/" className="text-xl font-bold tracking-tight text-brand">CV Matcher</a><p className="mt-1 text-sm text-slate-600">Administración de cuentas</p></div>
      <a className="text-sm font-semibold text-brand underline" href="/">Volver al inicio</a>
    </header>
    <Card>
      <div className="flex flex-wrap items-end justify-between gap-4"><div><h1 className="text-2xl font-bold">Cuentas de usuarios</h1><p className="mt-1 text-sm text-slate-600">Gestiona roles y estado de las cuentas autorizadas.</p></div><div className="flex flex-wrap gap-3">
        <label className="grid gap-1 text-sm font-medium">Rol<select aria-label="Filtrar por rol" className="min-h-11 rounded-lg border bg-white px-3" value={role} onChange={(event) => { setRole(event.target.value as "" | Role); setPage(0); }}><option value="">Todos</option><option value="ADMIN">Administrador</option><option value="RECRUITER">Reclutador</option></select></label>
        <label className="grid gap-1 text-sm font-medium">Estado<select aria-label="Filtrar por estado" className="min-h-11 rounded-lg border bg-white px-3" value={status} onChange={(event) => { setStatus(event.target.value as "" | Status); setPage(0); }}><option value="">Todos</option><option value="ACTIVE">Activa</option><option value="DISABLED">Desactivada</option><option value="PENDING_VERIFICATION">Pendiente de verificación</option></select></label>
      </div></div>
      {error && <div className="mt-5 space-y-3"><Notice>{error}</Notice><Button variant="secondary" onClick={() => setReload((value) => value + 1)} disabled={loading}>Reintentar</Button></div>}
      {loading && <p className="mt-6" aria-live="polite">Cargando cuentas…</p>}
      {!loading && result && <>
        {result.items.length === 0 ? <p className="mt-6 text-slate-600">No hay cuentas que coincidan con los filtros seleccionados.</p> : <div className="mt-6 overflow-x-auto"><table className="min-w-full text-left text-sm"><caption className="sr-only">Cuentas de usuarios</caption><thead className="border-b text-slate-600"><tr><th className="px-3 py-3">Nombre</th><th className="px-3 py-3">Correo</th><th className="px-3 py-3">Rol</th><th className="px-3 py-3">Estado</th><th className="px-3 py-3">Verificación</th><th className="px-3 py-3">Actualizada</th><th className="px-3 py-3"><span className="sr-only">Acciones</span></th></tr></thead><tbody>{result.items.map((account) => <tr className="border-b last:border-0" key={account.id}><td className="px-3 py-4 font-medium">{account.fullName}</td><td className="px-3 py-4">{account.email}</td><td className="px-3 py-4">{labelRole(account.role)}</td><td className="px-3 py-4">{labelStatus(account.status)}</td><td className="px-3 py-4">{account.emailVerifiedAt ? "Verificado" : "Sin verificar"}</td><td className="px-3 py-4">{formatDate.format(new Date(account.updatedAt))}</td><td className="px-3 py-4"><div className="flex gap-2"><Button variant="secondary" className="min-h-9 px-3 text-xs" onClick={() => openRoleChange(account)} disabled={pending}>Cambiar rol</Button><Button variant={account.status === "ACTIVE" ? "danger" : "secondary"} className="min-h-9 px-3 text-xs" onClick={() => openStatusChange(account)} disabled={pending}>{account.status === "ACTIVE" ? "Desactivar" : "Activar"}</Button></div></td></tr>)}</tbody></table></div>}
        <div className="mt-6 flex items-center justify-between gap-4"><p className="text-sm text-slate-600">Página {result.totalPages === 0 ? 0 : page + 1} de {result.totalPages} · {result.totalItems} cuentas</p><div className="flex gap-2"><Button variant="secondary" onClick={() => setPage((value) => value - 1)} disabled={!canGoBack}>Anterior</Button><Button variant="secondary" onClick={() => setPage((value) => value + 1)} disabled={!canGoForward}>Siguiente</Button></div></div>
      </>}
    </Card>
    {change && <div className="fixed inset-0 z-10 grid place-items-center bg-slate-950/40 p-4"><Card className="w-full max-w-md"><div ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="account-change-title"><h2 id="account-change-title" className="text-xl font-bold">Confirmar cambio</h2><p className="mt-3 text-slate-700">{change.kind === "role" ? <>¿Deseas asignar el rol <strong>{labelRole(change.value)}</strong> a {change.account.fullName}?</> : <>¿Deseas {change.value === "ACTIVE" ? "activar" : "desactivar"} la cuenta de {change.account.fullName}?</>}</p><p className="mt-2 text-sm text-slate-600">Esta acción se aplicará inmediatamente.</p>{mutationError && <div className="mt-4"><Notice>{mutationError}</Notice></div>}<div className="mt-6 flex justify-end gap-3"><Button variant="secondary" onClick={() => { setMutationError(""); setChange(null); }} disabled={pending}>Cancelar</Button><Button variant={change.kind === "status" && change.value === "DISABLED" ? "danger" : "primary"} onClick={() => void confirmChange()} disabled={pending}>{pending ? "Aplicando..." : "Confirmar"}</Button></div></div></Card></div>}
  </main>;
}
