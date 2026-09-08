import { StrictMode } from "react";
import { render, waitFor } from "@testing-library/react";
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
});
