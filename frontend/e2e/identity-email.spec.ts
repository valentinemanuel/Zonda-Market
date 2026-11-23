import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";

test.use({
  baseURL: process.env.ZONDA_SMOKE_URL ?? "http://localhost:3000",
  viewport: { width: 390, height: 844 },
});

test("verifica correo y restablece la contraseña desde Mailpit con teclado en móvil", async (
  { page, request }, testInfo,
) => {
  const email = `correo-${randomUUID()}@example.test`;
  const oldPassword = "contraseña-ficticia-original";
  const newPassword = "contraseña-ficticia-nueva";
  await page.goto("/registro");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill(oldPassword);
  await page.getByLabel("Contraseña").press("Enter");

  const sendVerification = page.getByRole("button", { name: "Enviar enlace de verificación" });
  await sendVerification.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status")).toHaveText("Revisá tu correo para continuar con la verificación.");

  const verification = await linkFromMail(request, email, "/verificar-correo");
  expect(verification.origin, "El enlace de verificación debe conservar el origen de la sesión").toBe(new URL(page.url()).origin);
  await page.goto(verification.href);
  await expect(page.getByRole("heading", { name: "Verificar correo" })).toBeVisible();
  await page.getByRole("button", { name: "Confirmar correo" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status")).toHaveText("Tu correo está verificado.");
  await expect.poll(() => new URL(page.url()).hash).toBe("");
  await page.getByRole("link", { name: "Ir a Mi cuenta" }).press("Enter");
  await expect(page.getByText("Verificado", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("correo-verificado-movil.png") });

  await page.goto("/ingresar");
  await page.getByRole("link", { name: "Olvidé mi contraseña" }).press("Enter");
  const recoveryEmail = page.getByLabel("Correo electrónico");
  const sendRecovery = page.getByRole("button", { name: "Enviar enlace de recuperación" });
  await sendRecovery.press("Enter");
  await expect(recoveryEmail).toBeFocused();
  await recoveryEmail.fill(email);

  let release!: () => void;
  const holdRecovery = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/auth/password-reset/request", async (route) => {
    await holdRecovery;
    await route.fulfill({ status: 503 });
  }, { times: 1 });
  await page.keyboard.press("Tab");
  await expect(sendRecovery).toBeFocused();
  await page.keyboard.press("Enter");
  try {
    await expect(page.getByRole("button", { name: "Enviando…" })).toBeDisabled();
    await page.screenshot({ path: testInfo.outputPath("recuperacion-cargando.png") });
  } finally {
    release();
  }
  await expect(page.getByRole("alert")).toHaveText("No pudimos enviar la solicitud. Volvé a intentar.");
  await sendRecovery.press("Enter");
  await expect(page.getByRole("status")).toContainText("Si ese correo tiene una cuenta con contraseña");

  const reset = await linkFromMail(request, email, "/restablecer-clave");
  await page.goto(reset.href);
  const password = page.getByLabel("Nueva contraseña");
  await password.fill(newPassword);
  await password.press("Tab");
  const savePassword = page.getByRole("button", { name: "Guardar contraseña" });
  await expect(savePassword).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status")).toContainText("Contraseña actualizada");

  await page.goto(reset.href);
  await password.fill("otra-contraseña-ficticia");
  await password.press("Enter");
  await expect(page.getByRole("alert")).toHaveText("Este enlace venció o ya no está disponible.");
  await expect(page.getByRole("link", { name: "Pedir un enlace nuevo" })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("enlace-reutilizado-movil.png") });

  await page.goto("/cuenta");
  await expect(page.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill(oldPassword);
  await page.getByLabel("Contraseña").press("Enter");
  await expect(page.getByRole("alert")).toContainText("El correo o la contraseña son incorrectos.");
  await page.getByLabel("Contraseña").fill(newPassword);
  await page.getByLabel("Contraseña").press("Enter");
  await expect(page).toHaveURL(/\/catalogo$/);
  await page.getByRole("link", { name: "Mi cuenta", exact: true }).press("Enter");
  await expect(page.getByText("Verificado", { exact: true })).toBeVisible();
});
