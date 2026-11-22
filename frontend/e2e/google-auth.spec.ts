import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

const providerCode = "g".repeat(43);
const credentialsRejected = {
  code: "INVALID_CREDENTIALS",
  detail: "El correo o la contraseña son incorrectos.",
};

test("retorno de Google limpia el fragmento, anuncia carga y abre el catálogo en móvil", async ({ page, baseURL }, testInfo) => {
  const email = "comprador@example.test";
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/google-session-fixture", (route) => route.fulfill({
    headers: { "Set-Cookie": "__Host-zonda-refresh=sesion-anterior-ficticia; Path=/; HttpOnly; Secure; SameSite=Strict" },
    body: "Sesión de prueba",
  }));
  await page.goto("/google-session-fixture");
  await page.route("**/api/v1/auth/oauth/google", (route) => route.fulfill({ json: { enabled: true } }));
  await page.route("**/oauth2/authorization/google", (route) => route.fulfill({
    contentType: "text/html",
    headers: { "Set-Cookie": "__Host-zonda-oauth=correlacion-ficticia; Path=/; HttpOnly; Secure; SameSite=Lax" },
    body: '<a href="https://provider.example.test/authorize">Ir al proveedor de prueba</a>',
  }));
  await page.route("https://provider.example.test/authorize", (route) => route.fulfill({
    contentType: "text/html",
    body: `<a href="${new URL("/login/oauth2/code/google", baseURL)}">Confirmar en el proveedor</a>`,
  }));
  await page.route("**/login/oauth2/code/google", async (route) => {
    const cookies = (await route.request().allHeaders()).cookie;
    expect(cookies).toContain("__Host-zonda-oauth=correlacion-ficticia");
    expect(cookies).not.toContain("__Host-zonda-refresh");
    await route.fulfill({ status: 302, headers: { Location: `/oauth/retorno#code=${providerCode}` } });
  });
  await page.route("**/api/v1/me", (route) => route.fulfill({
    json: { id: "comprador", email, emailVerified: false, roles: ["USER"] },
  }));
  await page.route("**/api/v1/me/identities/google", (route) => route.fulfill({ json: { linked: true } }));
  await page.route("**/api/v1/cart", (route) => route.fulfill({
    json: { version: 0, groups: [], unavailable: [], total: "0.00", currency: "ARS", itemCount: 0 },
  }));

  let release!: () => void;
  const hold = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/auth/oauth/exchange", async (route) => {
    await hold;
    await route.fulfill({ json: { accessToken: "token-ficticio", expiresIn: 600, roles: ["USER"] } });
  });
  await page.goto("/ingresar");
  const google = page.getByRole("link", { name: "Continuar con Google" });
  await google.focus();
  await expect(google).toBeFocused();
  await google.press("Enter");
  await page.getByRole("link", { name: "Ir al proveedor de prueba" }).click();
  const exchanging = page.waitForRequest("**/api/v1/auth/oauth/exchange");
  await page.getByRole("link", { name: "Confirmar en el proveedor" }).click({ noWaitAfter: true });

  try {
    const exchange = await exchanging;
    expect(exchange.postDataJSON()).toEqual({ code: providerCode });
    expect(exchange.headers()["x-zonda-csrf"]).toBe("1");
    expect((await exchange.allHeaders()).cookie).toContain("__Host-zonda-refresh=sesion-anterior-ficticia");
    expect(new URL(page.url()).hash).toBe("");
    await expect(page.getByRole("status")).toHaveText("Confirmando acceso con Google…");
    await page.screenshot({ path: testInfo.outputPath("google-retorno-cargando.png") });
  } finally {
    release();
  }

  await expect(page).toHaveURL(/\/catalogo$/);
  await expect(page.getByRole("heading", { name: "Catálogo" })).toBeVisible();
  await page.getByRole("link", { name: "Mi cuenta", exact: true }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await expect(page.getByText("Google vinculado")).toBeVisible();
  await expect(page.getByText(email)).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("google-cuenta-movil.png") });
});

test("orienta ante correo existente y permite reintentar la vinculación con teclado", async ({ page }, testInfo) => {
  const email = `google-${randomUUID()}@example.test`;
  const password = "clave-ficticia-para-vincular";
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v1/auth/oauth/google", (route) => route.fulfill({ json: { enabled: true } }));
  await page.route("**/api/v1/auth/oauth/exchange", (route) => route.fulfill({
    status: 409, contentType: "application/problem+json", json: { code: "GOOGLE_LINK_REQUIRED" },
  }));
  await page.goto(`/oauth/retorno#code=${providerCode}`);
  await expect(page.getByRole("alert")).toContainText("Ese correo ya tiene una cuenta.");
  await page.screenshot({ path: testInfo.outputPath("google-correo-existente.png") });
  await page.goto("/ingresar");
  await page.goto("/oauth/retorno#error=denied");
  await expect(page.getByRole("alert")).toContainText("Cancelaste el acceso con Google.");
  await expect(page.getByRole("link", { name: "Volver al ingreso" })).toBeVisible();

  let linked = false;
  let providerResponse = "/oauth/retorno#error=denied";
  await page.route("**/api/v1/me/identities/google", (route) => route.fulfill({ json: { linked } }));
  await page.route("**/api/v1/me/identities/google/start", (route) => {
    expect(route.request().headers()["authorization"]).toMatch(/^Bearer /);
    expect(route.request().headers()["x-zonda-csrf"]).toBe("1");
    if (route.request().postDataJSON().password === password) {
      return route.fulfill({ json: { authorizationUrl: providerResponse } });
    }
    return route.fulfill({ status: 401, contentType: "application/problem+json", json: credentialsRejected });
  });

  await page.goto("/registro");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill(password);
  await page.getByRole("button", { name: "Crear cuenta" }).click();
  await expect(page.getByRole("heading", { name: "Seguridad de cuenta" })).toBeVisible();
  const disclosure = page.getByText("Vincular Google", { exact: true });
  await disclosure.focus();
  await disclosure.press("Enter");
  await page.keyboard.press("Tab");
  const field = page.getByLabel("Contraseña actual");
  await expect(field).toBeFocused();
  await field.fill("clave-incorrecta");
  await field.press("Enter");
  await expect(page.getByRole("alert")).toHaveText("La contraseña es incorrecta.");
  await field.fill(password);
  await field.press("Enter");
  await expect(page.getByRole("alert")).toContainText("Cancelaste el acceso con Google.");

  await page.getByRole("link", { name: "Ir a mi cuenta", exact: true }).press("Enter");
  await expect(page).toHaveURL(/\/cuenta$/);
  providerResponse = `/oauth/retorno#code=${providerCode}&mode=link`;
  await page.route("**/api/v1/auth/oauth/exchange", (route) => {
    linked = true;
    return route.fulfill({ json: { accessToken: "token-ficticio", expiresIn: 600, roles: ["USER"] } });
  });
  await page.route("**/api/v1/me", (route) => route.fulfill({
    json: { id: "comprador", email, emailVerified: false, roles: ["USER"] },
  }));
  await disclosure.press("Enter");
  await field.fill(password);
  await field.press("Enter");
  await expect(page).toHaveURL(/\/cuenta$/);
  await expect(page.getByText("Google vinculado")).toBeVisible();
});
