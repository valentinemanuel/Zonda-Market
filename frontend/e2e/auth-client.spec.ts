import { expect, test } from "@playwright/test";
import { authenticate, authenticatedRequest } from "../src/features/auth/session-api";

test("dos respuestas 401 comparten un refresh y reintentan una vez", async () => {
  const originalFetch = globalThis.fetch;
  const attempts: string[] = [];
  let renewals = 0;
  let completeRejections!: () => void;
  const rejected = new Promise<void>((resolve) => { completeRejections = resolve; });

  globalThis.fetch = async (input, init) => {
    if (input === "/api/v1/auth/login") {
      return Response.json({ accessToken: "original" });
    }
    if (input === "/api/v1/auth/refresh") {
      renewals++;
      await rejected;
      return Response.json({ accessToken: "renovado" });
    }
    const token = new Headers(init?.headers).get("Authorization") ?? "";
    attempts.push(token);
    if (attempts.length === 2) {
      completeRejections();
    }
    return token === "Bearer renovado" ? Response.json({}) : new Response(null, { status: 401 });
  };
  try {
    await authenticate("login", { email: "comprador@example.test", password: "clave-ficticia" });
    const results = await Promise.all([
      authenticatedRequest("/api/v1/me"),
      authenticatedRequest("/api/v1/me"),
    ]);
    expect(results.map(({ status }) => status)).toEqual([200, 200]);
    expect(renewals).toBe(1);
    expect(attempts).toEqual(["Bearer original", "Bearer original", "Bearer renovado", "Bearer renovado"]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
