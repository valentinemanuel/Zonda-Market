import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";
import { productPhoto } from "./product-photo";

test.use({
  baseURL: process.env.ZONDA_SMOKE_URL ?? "http://localhost:3000",
  viewport: { width: 390, height: 844 },
});

test("ajusta stock, recupera una respuesta perdida y actualiza la ficha pública", async ({ page, request, browser }, testInfo) => {
  const suffix = randomUUID().slice(0, 8);
  const email = "stock-" + suffix + "@example.test";
  const code = "STOCK-" + suffix.toUpperCase();
  await page.goto("/registro");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill("contraseña-ficticia-e203");
  await page.getByLabel("Contraseña").press("Enter");
  await page.getByRole("button", { name: "Enviar enlace de verificación" }).press("Enter");
  await page.goto((await linkFromMail(request, email, "/verificar-correo")).href);
  await page.getByRole("button", { name: "Confirmar correo" }).press("Enter");
  await page.getByRole("link", { name: "Ir a Mi cuenta" }).press("Enter");
  await page.getByLabel("Tipo de vendedor").selectOption("PERSONAL");
  await page.getByLabel("Nombre público").fill("Vendedor de prueba");
  await page.getByLabel("Identificador público").fill("stock-" + suffix);
  await page.getByRole("button", { name: "Activar perfil de vendedor" }).press("Enter");
  await page.getByRole("link", { name: "Mis publicaciones" }).press("Enter");
  await page.getByRole("link", { name: "Crear publicación" }).press("Enter");
  await page.getByLabel("Categoría").selectOption({ label: "Indumentaria" });
  await page.getByLabel("Título").fill("Remera de inventario");
  await page.getByLabel("Descripción").fill("Fixture de stock.");
  await page.getByRole("button", { name: "Crear borrador" }).press("Enter");

  await page.getByRole("tab", { name: "Opciones de venta" }).press("Enter");
  await page.getByLabel("Código interno (SKU)").fill(code);
  await page.getByLabel("Color", { exact: true }).selectOption("Azul");
  await page.getByLabel("Talle", { exact: true }).selectOption("M");
  await page.getByLabel("Precio (ARS)").fill("10000");
  await page.getByLabel("Stock inicial").fill("7");
  await page.getByRole("button", { name: "Agregar opción" }).press("Enter");
  await expect(page.getByRole("row", { name: new RegExp(code) })).toBeVisible();

  await page.getByRole("tab", { name: "Fotos" }).press("Enter");
  await page.getByLabel("Archivo de imagen").setInputFiles({
    name: "stock.png", mimeType: "image/png", buffer: await productPhoto(page, "#245fb9"),
  });
  await page.getByRole("button", { name: "Subir imagen" }).press("Enter");
  await expect(page.getByText("Imagen agregada.")).toBeVisible();
  await page.getByRole("tab", { name: "Vista previa" }).press("Enter");
  await page.getByRole("button", { name: "Publicar ficha" }).press("Enter");
  const link = page.getByRole("link", { name: "Ver ficha pública" });
  await expect(link).toBeVisible();
  const publicUrl = new URL((await link.getAttribute("href"))!, page.url()).href;

  await page.getByRole("link", { name: "Volver a Publicaciones" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mis publicaciones" })).toBeVisible();
  await expect(page.getByText("7 unidades", { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 1360, height: 920 });
  await page.screenshot({ path: testInfo.outputPath("mis-publicaciones-escritorio.png"), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("link", { name: "Ajustar stock", exact: true }).press("Enter");
  await expect(page.getByRole("heading", { name: "Ajustar stock" })).toBeFocused();
  await expect(page.getByRole("dialog", { name: "Ajustar stock" })).toBeVisible();

  const guest = await browser.newPage();
  const stale = await page.context().newPage();
  try {
    await guest.goto(publicUrl);
    await expect(guest.getByText("Disponible", { exact: true })).toBeVisible();
    await stale.goto(page.url());
    const available = (tab: typeof page) => tab.getByRole("group", { name: "Stock disponible" });
    await expect(available(stale)).toContainText("7");
    await expect(page.getByText("Todavía no hay movimientos.")).toBeVisible();
    const delta = page.getByLabel("Cantidad de unidades");
    const reason = page.getByLabel("Motivo del ajuste");
    const save = page.getByRole("button", { name: "Guardar ajuste" });
    await delta.fill("5");
    await delta.press("Tab");
    await expect(reason).toBeFocused();
    await reason.fill("Reposición");
    await expect(page.getByText("12 u.", { exact: true })).toBeVisible();

    let sentKey: string | undefined;
    let firstId: string | undefined;
    await page.route("**/stock-adjustments", async (route) => {
      sentKey = (await route.request().allHeaders())["idempotency-key"];
      const committed = await route.fetch();
      if (committed.ok()) firstId = (await committed.json()).id;
      await route.abort("failed");
    }, { times: 1 });
    await save.press("Enter");
    await expect(page.getByRole("alert")).toHaveText("No pudimos guardar los cambios. Volvé a intentar.");
    await expect(delta).toHaveValue("5");
    expect(sentKey).toBeTruthy();
    expect(firstId).toBeTruthy();
    const retry = page.waitForResponse((response) => response.url().endsWith("/stock-adjustments"));
    await save.press("Enter");
    const repeated = await retry;
    expect(repeated.status()).toBe(200);
    expect((await repeated.request().allHeaders())["idempotency-key"]).toBe(sentKey);
    expect(await repeated.json()).toMatchObject({ id: firstId, afterStock: 12 });
    await expect(available(page)).toContainText("12");
    await expect(page.getByText("Reposición", { exact: true })).toHaveCount(1);

    await page.getByRole("radio", { name: "Sumar" }).press("ArrowRight");
    await expect(page.getByRole("radio", { name: "Descontar" })).toBeChecked();
    await delta.fill("2");
    await reason.fill("Corrección de inventario");
    await save.press("Enter");
    await expect(available(page)).toContainText("10");
    await expect(page.getByText("De 12 a 10", { exact: true })).toBeVisible();

    await stale.getByLabel("Cantidad de unidades").fill("1");
    await stale.getByLabel("Motivo del ajuste").fill("Conteo diferido");
    await stale.getByRole("button", { name: "Guardar ajuste" }).press("Enter");
    await expect(stale.getByRole("alert")).toContainText("Hay cambios más recientes");
    await stale.getByRole("button", { name: "Recargar stock" }).press("Enter");
    await expect(available(stale)).toContainText("10");
    await expect(stale.getByRole("alert")).toHaveCount(0);

    await delta.fill("11");
    await reason.fill("Descuento excesivo");
    await save.press("Enter");
    await expect(page.getByRole("alert")).toHaveText("El ajuste dejaría el stock disponible por debajo de cero.");
    await expect(available(page)).toContainText("10");
    await page.screenshot({ path: testInfo.outputPath("stock-insuficiente-movil.png") });

    await delta.fill("10");
    await reason.fill("Inventario agotado");
    await save.press("Enter");
    await expect(available(page)).toContainText("0");
    await expect(page.getByText("Inventario agotado", { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await page.screenshot({ path: testInfo.outputPath("movimientos-stock-movil.png") });
    await guest.reload();
    await expect(guest.getByText("Sin stock", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByRole("searchbox", { name: "Buscar por producto o código" })).toBeFocused();
    await page.getByRole("combobox", { name: "Filtrar por disponibilidad" }).selectOption("empty");
    const adjust = page.getByRole("button", { name: "Ajustar stock de " + code });
    await expect(adjust).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath("inventario-movil.png"), fullPage: true });
    await adjust.press("Enter");
    await expect(page.getByRole("dialog")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(adjust).toBeFocused();
  } finally {
    await stale.close();
    await guest.close();
  }
});
