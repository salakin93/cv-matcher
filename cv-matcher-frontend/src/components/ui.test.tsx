import { render, screen } from "@testing-library/react";
import { Button, Notice } from "./ui";

describe("componentes base", () => {
  it("expone un botón accesible", () => {
    render(<Button>Continuar</Button>);
    expect(screen.getByRole("button", { name: "Continuar" })).toBeEnabled();
  });

  it("anuncia errores de forma accesible", () => {
    render(<Notice>No se pudo completar la solicitud.</Notice>);
    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo completar la solicitud.");
  });
});
