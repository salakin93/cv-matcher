import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VacancyEditorPage } from "./VacancyManagementPage";

const authenticatedRequest = vi.fn();
vi.mock("../auth/AuthProvider", () => ({ useAuth: () => ({ authenticatedRequest }) }));

describe("VacancyEditorPage", () => {
  beforeEach(() => authenticatedRequest.mockReset());
  it("mantiene requisitos ordenables y fechas Bolivia en el formulario", () => {
    render(<MemoryRouter initialEntries={["/vacantes/nueva"]}><Routes><Route path="/vacantes/nueva" element={<VacancyEditorPage />} /></Routes></MemoryRouter>);
    expect(screen.getByLabelText("Fecha inicial (Bolivia)")).toHaveAttribute("type", "date");
    fireEvent.click(screen.getByRole("button", { name: "Añadir requisito" }));
    expect(screen.getAllByRole("button", { name: "Subir" })[1]).toBeEnabled();
    fireEvent.click(screen.getAllByRole("button", { name: "Subir" })[1]);
    expect(screen.getAllByLabelText(/Peso del requisito/)).toHaveLength(2);
  });
});
