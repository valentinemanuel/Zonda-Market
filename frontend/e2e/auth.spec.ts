import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

test("muestra el ingreso y el registro con cabecera propia y enlaces activos en el pie de tienda", async ({ page, context }, testInfo) => {
  await page.route("**/api/v1/auth/oauth/google", (route) => route.fulfill({ json: { enabled: true } }));
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/ingresar");
  const header = page.getByRole("banner");
  await expect(header.getByRole("link", { name: "Zonda Market" })).toBeVisible();
  await expect(header.getByRole("searchbox")).toHaveCount(0);
  await expect(page.getByText("Envíos con seguimiento", { exact: true })).toBeVisible();
  const google = page.getByRole("link", { name: "Continuar con Google" });
  await expect(google).toHaveAttribute("href", "/oauth2/authorization/google");
  await expect(google.locator("img")).toHaveAttribute("src", "/brand/google-g.svg");
  await expect(page.getByLabel("Correo electrónico")).toHaveAttribute("autocomplete", "username");
  await expect(page.getByLabel("Contraseña")).toHaveAttribute("autocomplete", "current-password");
  await expect(page.getByRole("checkbox", { name: "Recordarme" })).not.toBeChecked();
  await page.screenshot({ path: testInfo.outputPath("ingreso-1440.png") });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("link", { name: "Crear una cuenta" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Crear cuenta" })).toBeVisible();
  await expect(page.getByLabel("Contraseña")).toHaveAttribute("autocomplete", "new-password");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("registro-390.png"), fullPage: true });

  const email = `recordarme-${randomUUID()}@example.test`;
  const password = "clave-ficticia-de-prueba";
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill(password);
  await page.getByRole("button", { name: "Crear cuenta" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await page.goto("/ingresar");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill(password);
  const remember = page.getByRole("checkbox", { name: "Recordarme" });
  await remember.press("Space");
  await expect(remember).toBeChecked();
  await page.getByRole("button", { name: "Iniciar sesión" }).press("Enter");
  await expect(page).toHaveURL(/\/catalogo$/);
  const cookie = (await context.cookies()).find(({ name }) => name === "__Host-zonda-refresh");
  expect(cookie?.expires).toBeGreaterThan(Date.now() / 1000);

  const footer = page.getByRole("contentinfo");
  await expect(footer.getByText("Operativo", { exact: true })).toBeVisible();
  await expect(footer.getByRole("link", { name: "Compartir en WhatsApp" })).toHaveAttribute("href", /^https:\/\/wa.me\//);
  await expect(footer.getByRole("list", { name: "Medios de pago" }).getByRole("listitem")).toHaveCount(4);
  await page.setViewportSize({ width: 1440, height: 900 });
  await footer.screenshot({ path: testInfo.outputPath("footer-1440.png") });
  await footer.getByRole("link", { name: "Categorías populares" }).press("Enter");
  await expect(page).toHaveURL(/#categorias-populares$/);
  await expect(page.getByRole("heading", { name: "Explorá por categorías populares" })).toBeInViewport();
  await footer.getByRole("link", { name: "Ofertas" }).press("Enter");
  await expect(page.getByText(/Productos hasta/)).toBeVisible();
  for (const name of ["Guía para vendedores", "Centro de ayuda", "Términos y condiciones", "Privacidad"]) {
    await footer.getByRole("link", { name, exact: true }).press("Enter");
    await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  }
});

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
  await expect(page).toHaveURL(/\/catalogo$/);
  await expect(page.getByRole("heading", { name: "Catálogo" })).toBeVisible();
  let releaseAccount!: () => void;
  const loadingAccount = new Promise<void>((resolve) => { releaseAccount = resolve; });
  await page.route("**/api/v1/me", async (route) => {
    await loadingAccount;
    await route.fulfill({ status: 503 });
  }, { times: 1 });
  await page.getByRole("banner").getByRole("link", { name: "Mi cuenta", exact: true }).press("Enter");
  try {
    await expect(page.getByRole("status").filter({ hasText: "Cargando tu cuenta…" })).toBeVisible();
  } finally {
    releaseAccount();
  }
  await expect(page.getByRole("alert")).toContainText("No pudimos cargar tu cuenta.");
  await page.screenshot({ path: testInfo.outputPath("cuenta-error-movil.png") });
  await page.getByRole("button", { name: "Reintentar" }).press("Enter");
  await expect(page.getByText(email)).toBeVisible();
  await page.getByRole("main").getByRole("link", { name: "Mis órdenes" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mis órdenes" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Aún no realizaste ninguna compra" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Ir al catálogo" })).toHaveAttribute("href", "/catalogo");
});
