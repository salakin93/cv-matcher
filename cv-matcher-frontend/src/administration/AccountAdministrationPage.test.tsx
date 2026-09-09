import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountAdministrationPage } from "./AccountAdministrationPage";

const authenticatedRequest = vi.fn();

vi.mock("../auth/AuthProvider", () => ({ useAuth: () => ({ authenticatedRequest }) }));

const account = { id: "target-id", fullName: "Bruno Reclutador", email: "bruno@example.test", role: "RECRUITER", status: "ACTIVE", emailVerifiedAt: "2026-09-08T12:00:00Z", forcePasswordChange: false, updatedAt: "2026-09-08T12:00:00Z" };
const response = { items: [account], page: 0, size: 20, totalItems: 1, totalPages: 1 };

describe("AccountAdministrationPage", () => {
  beforeEach(() => {
    authenticatedRequest.mockReset();
    authenticatedRequest.mockResolvedValue(response);
  });

  it("renderiza la tabla aprobada, aplica filtros y reinicia la página", async () => {
    render(<AccountAdministrationPage />);
    await screen.findByText("bruno@example.test");
    expect(screen.queryByText("target-id")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Filtrar por rol"), { target: { value: "ADMIN" } });
    await waitFor(() => expect(authenticatedRequest).toHaveBeenLastCalledWith("/admin/users?page=0&size=20&role=ADMIN"));
  });

  it("describe la cuenta y acción antes de enviar una mutación", async () => {
    render(<AccountAdministrationPage />);
    await screen.findByText("Bruno Reclutador");
    fireEvent.click(screen.getByRole("button", { name: "Cambiar rol" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("asignar el rol Administrador a Bruno Reclutador");
    expect(authenticatedRequest).toHaveBeenCalledTimes(1);
  });
});
