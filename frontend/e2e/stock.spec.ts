import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";

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

  await page.getByRole("tab", { name: "Variantes" }).press("Enter");
  await page.getByLabel("Código SKU").fill(code);
  await page.getByLabel("Color", { exact: true }).selectOption("Azul");
  await page.getByLabel("Talle", { exact: true }).selectOption("M");
  await page.getByLabel("Precio (ARS)").fill("10000");
  await page.getByLabel("Stock inicial").fill("7");
  await page.getByRole("button", { name: "Agregar variante" }).press("Enter");
  const stockAction = page.getByRole("button", { name: "Ajustar stock de " + code });
  await expect(stockAction).toBeVisible();

  await page.getByRole("tab", { name: "Imágenes" }).press("Enter");
  const picture = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 8;
    canvas.height = 8;
    canvas.getContext("2d")!.fillRect(0, 0, 8, 8);
    return canvas.toDataURL("image/png").split(",")[1];
  });
  await page.getByLabel("Archivo de imagen").setInputFiles({
    name: "stock.png", mimeType: "image/png", buffer: Buffer.from(picture, "base64"),
  });
  await page.getByRole("button", { name: "Subir imagen" }).press("Enter");
  await expect(page.getByText("Imagen agregada.")).toBeVisible();
  await page.getByRole("tab", { name: "Vista previa" }).press("Enter");
  await page.getByRole("button", { name: "Publicar ficha" }).press("Enter");
  const link = page.getByRole("link", { name: "Ver ficha pública" });
  await expect(link).toBeVisible();
  const publicUrl = new URL((await link.getAttribute("href"))!, page.url()).href;

  const guest = await browser.newPage();
  const stale = await page.context().newPage();
  try {
    await guest.goto(publicUrl);
    await expect(guest.getByText("Disponible", { exact: true })).toBeVisible();
    await stale.goto(page.url());
    await stale.getByRole("tab", { name: "Variantes" }).press("Enter");
    await stale.getByRole("button", { name: "Ajustar stock de " + code }).press("Enter");
    await expect(stale.getByText("Stock disponible: 7")).toBeVisible();

    await page.getByRole("tab", { name: "Variantes" }).press("Enter");
    await stockAction.press("Enter");
    await expect(page.getByRole("heading", { name: "Stock de " + code })).toBeFocused();
    await expect(page.getByText("Todavía no hay movimientos.")).toBeVisible();
    const delta = page.getByLabel("Ajuste en unidades");
    const reason = page.getByLabel("Motivo del ajuste");
    const save = page.getByRole("button", { name: "Guardar ajuste" });
    await delta.fill("5");
    await delta.press("Tab");
    await expect(reason).toBeFocused();
    await reason.fill("Reposición");

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
    await expect(page.getByText("Stock disponible: 12")).toBeVisible();
    await expect(page.getByText("Reposición", { exact: true })).toHaveCount(1);

    await delta.fill("-2");
    await reason.fill("Corrección de inventario");
    await save.press("Enter");
    await expect(page.getByText("Stock disponible: 10")).toBeVisible();
    await expect(page.getByText("De 12 a 10", { exact: true })).toBeVisible();

    await stale.getByLabel("Ajuste en unidades").fill("1");
    await stale.getByLabel("Motivo del ajuste").fill("Conteo diferido");
    await stale.getByRole("button", { name: "Guardar ajuste" }).press("Enter");
    await expect(stale.getByRole("alert")).toContainText("Hay cambios más recientes");
    await stale.getByRole("button", { name: "Recargar stock" }).press("Enter");
    await expect(stale.getByText("Stock disponible: 10")).toBeVisible();
    await expect(stale.getByRole("alert")).toHaveCount(0);

    await delta.fill("-11");
    await reason.fill("Descuento excesivo");
    await save.press("Enter");
    await expect(page.getByRole("alert")).toHaveText("El ajuste dejaría el stock disponible por debajo de cero.");
    await expect(page.getByText("Stock disponible: 10")).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath("stock-insuficiente-movil.png"), fullPage: true });

    await delta.fill("-10");
    await reason.fill("Inventario agotado");
    await save.press("Enter");
    await expect(page.getByText("Stock disponible: 0")).toBeVisible();
    await expect(page.getByText("Inventario agotado", { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await page.screenshot({ path: testInfo.outputPath("movimientos-stock-movil.png"), fullPage: true });
    await guest.reload();
    await expect(guest.getByText("Sin stock", { exact: true })).toBeVisible();
  } finally {
    await stale.close();
    await guest.close();
  }
});
