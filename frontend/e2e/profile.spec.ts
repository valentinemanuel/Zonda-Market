import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";

test.use({
  baseURL: process.env.ZONDA_SMOKE_URL ?? "http://localhost:3000",
  viewport: { width: 390, height: 844 },
});

test("edita perfil y direcciones y activa un vendedor verificado con teclado en móvil", async (
  { page, request, browser }, testInfo,
) => {
  const suffix = randomUUID().slice(0, 10);
  const email = `perfil-${suffix}@example.test`;
  const slug = `taller-${suffix}`;
  await page.goto("/registro");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill("contraseña-ficticia-e104");
  await page.getByLabel("Contraseña").press("Enter");
  await expect(page.getByText("Verificá tu correo para activar tu perfil de vendedor.")).toBeVisible();

  const name = page.getByLabel("Nombre de perfil");
  await name.fill("Lucía Ficticia");
  await name.press("Tab");
  await expect(page.getByRole("button", { name: "Guardar nombre" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status")).toHaveText("Nombre guardado.");
  await expect(page.getByRole("link", { name: "Perfil y seguridad" })).toHaveAttribute("aria-current", "page");
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.screenshot({ path: testInfo.outputPath("cuenta-escritorio.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });

  let release!: () => void;
  const waiting = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/me/addresses", async (route) => {
    await waiting;
    await route.fulfill({ status: 503 });
  }, { times: 1 });
  await page.getByRole("main").getByRole("link", { name: "Mis direcciones" }).press("Enter");
  try {
    await expect(page.getByRole("status")).toHaveText("Cargando direcciones…");
  } finally {
    release();
  }
  await expect(page.getByRole("alert")).toHaveText("No pudimos cargar tus direcciones.");
  await page.screenshot({ path: testInfo.outputPath("direcciones-error-movil.png") });
  await page.getByRole("button", { name: "Reintentar" }).press("Enter");
  await expect(page.getByText("Todavía no guardaste direcciones.")).toBeVisible();
  await expect(page.getByRole("main").getByRole("link", { name: "Mis direcciones" })).toHaveAttribute("aria-current", "page");

  const street = page.getByLabel("Calle y número");
  await page.getByRole("button", { name: "Agregar dirección" }).press("Enter");
  await expect(street).toBeFocused();
  await street.fill("San Martín 200");
  await street.press("Tab");
  await expect(page.getByLabel("Localidad")).toBeFocused();
  await page.getByLabel("Localidad").fill("Mendoza");
  await page.getByLabel("Provincia").selectOption("AR-M");
  await page.getByLabel("Código postal").fill("5500");
  await page.getByRole("button", { name: "Guardar dirección" }).press("Enter");
  await expect(page.getByRole("status")).toHaveText("Dirección agregada.");

  await page.getByRole("button", { name: "Editar San Martín 200" }).press("Enter");
  await expect(street).toBeFocused();
  await expect(street).toHaveValue("San Martín 200");
  await street.fill("San Martín 202");
  await page.getByRole("button", { name: "Guardar dirección" }).press("Enter");
  await expect(page.getByRole("status")).toHaveText("Dirección actualizada.");
  await expect(page.getByRole("button", { name: "Eliminar San Martín 202" })).toBeVisible();
  await page.setViewportSize({ width: 320, height: 820 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(320);
  await page.screenshot({ path: testInfo.outputPath("direccion-guardada-movil.png"), fullPage: true });
  await page.getByRole("button", { name: "Eliminar San Martín 202" }).press("Enter");
  await page.getByRole("button", { name: "Confirmar eliminación" }).press("Enter");
  await expect(page.getByText("Todavía no guardaste direcciones.")).toBeVisible();

  await page.getByRole("link", { name: "Perfil y seguridad" }).press("Enter");
  await expect(name).toHaveValue("Lucía Ficticia");
  await page.getByRole("button", { name: "Enviar enlace de verificación" }).press("Enter");
  await page.goto((await linkFromMail(request, email, "/verificar-correo")).href);
  await page.getByRole("button", { name: "Confirmar correo" }).press("Enter");
  await page.getByRole("link", { name: "Ir a Mi cuenta" }).press("Enter");

  await page.getByLabel("Tipo de vendedor").selectOption("COMERCIO");
  await page.getByLabel("Nombre público").fill("Taller ficticio");
  const slugInput = page.getByLabel("Identificador público");
  await slugInput.fill(slug);
  await page.getByLabel("Descripción (opcional)").fill("Objetos de prueba.");
  const activate = page.getByRole("button", { name: "Activar perfil de vendedor" });
  await page.route("**/api/v1/me/seller-profile", (route) => route.fulfill({
    status: 409,
    contentType: "application/problem+json",
    body: JSON.stringify({ code: "SELLER_SLUG_IN_USE" }),
  }), { times: 1 });
  await activate.press("Enter");
  await expect(page.getByRole("alert")).toHaveText("Ese identificador ya está en uso. Elegí otro.");
  await expect(slugInput).toHaveValue(slug);
  await page.screenshot({ path: testInfo.outputPath("slug-ocupado-movil.png") });

  const refresh = page.waitForResponse((response) => response.url().endsWith("/api/v1/auth/refresh"));
  await activate.press("Enter");
  expect(await (await refresh).json()).toMatchObject({ roles: ["SELLER"] });
  await expect(page.getByRole("heading", { name: "Panel vendedor" })).toBeVisible();
  const publicLink = page.getByRole("link", { name: "Ver ficha pública" });
  await expect(publicLink).toHaveAttribute("href", `/vendedores/${slug}`);

  const visitor = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await visitor.goto(new URL(`/vendedores/${slug}`, page.url()).href);
    await expect(visitor.getByRole("heading", { name: "Taller ficticio" })).toBeVisible();
    await expect(visitor.getByText("Todavía no tiene reseñas.")).toBeVisible();
    await expect(visitor.getByText(email)).toHaveCount(0);
    expect(await visitor.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await visitor.screenshot({ path: testInfo.outputPath("ficha-publica-movil.png") });
  } finally {
    await visitor.close();
  }

  await page.getByRole("link", { name: "Volver a Mi cuenta" }).press("Enter");
  await expect(page.getByRole("link", { name: "Panel vendedor" })).toBeVisible();
});
