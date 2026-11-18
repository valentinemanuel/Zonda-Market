import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

test("registra, restaura dos pestañas y cierra la sesión con teclado en móvil", async ({ page, context }, testInfo) => {
  const email = `comprador-${randomUUID()}@example.test`;
  const password = "clave-ficticia-de-prueba";
  await page.setViewportSize({ width: 390, height: 844 });

  await page.goto("/cuenta");
  await expect(page.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
  await page.getByRole("link", { name: "Crear una cuenta" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Crear cuenta" })).toBeVisible();

  const emailInput = page.getByLabel("Correo electrónico");
  const passwordInput = page.getByLabel("Contraseña");
  await page.getByRole("button", { name: "Crear cuenta" }).click();
  await expect(emailInput).toBeFocused();
  await page.keyboard.type(email);
  await page.keyboard.press("Tab");
  await expect(passwordInput).toBeFocused();
  await page.keyboard.type(password);
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await expect(page.getByText(email)).toBeVisible();
  await expect(page.getByText("Pendiente de verificación")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("cuenta-movil.png") });

  const cookie = (await context.cookies()).find(({ name }) => name === "__Host-zonda-refresh");
  expect(cookie).toMatchObject({ httpOnly: true, secure: true, sameSite: "Strict" });
  const secondTab = await context.newPage();
  await Promise.all([page.reload(), secondTab.goto("/cuenta")]);
  await expect(page.getByText(email)).toBeVisible();
  await expect(secondTab.getByText(email)).toBeVisible();

  await page.getByRole("button", { name: "Cerrar sesión" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
  await expect(secondTab.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
  expect((await context.cookies()).some(({ name }) => name === "__Host-zonda-refresh")).toBe(false);

  await emailInput.fill(email);
  await passwordInput.fill("contraseña-incorrecta");
  let release!: () => void;
  const holdLogin = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/auth/login", async (route) => {
    await holdLogin;
    await route.continue();
  }, { times: 1 });
  await passwordInput.press("Enter");
  try {
    await expect(page.getByRole("button", { name: "Enviando…" })).toBeDisabled();
    await page.screenshot({ path: testInfo.outputPath("ingreso-cargando.png") });
  } finally {
    release();
  }
  await expect(page.getByRole("alert")).toHaveText("El correo o la contraseña son incorrectos.");
  await page.screenshot({ path: testInfo.outputPath("ingreso-error.png") });

  await passwordInput.fill(password);
  await passwordInput.press("Enter");
  await expect(page.getByText(email)).toBeVisible();
});
