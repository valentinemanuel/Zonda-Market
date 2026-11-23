import { expect, test } from "@playwright/test";
import { credentials } from "./cart-fixtures";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("registra un despacho y confirma solo esa entrega con teclado en móvil", async ({ page, browser, request, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const { order, alpha, beta, approve } = await purchaseFixture(page, request, origin);
  await approve();

  await page.goto("/mis-ordenes");
  await page.getByRole("article", { name: "Orden #" + order.id.slice(0, 8) })
    .getByRole("link", { name: "Ver detalle" }).press("Enter");
  await expect(page.getByText("En preparación", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("button", { name: "Confirmar recepción" })).toHaveCount(0);

  const seller = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await seller.goto(origin + "/ingresar");
    await credentials(seller, alpha.email);
    await expect(seller).toHaveURL(origin + "/catalogo");
    await seller.goto(origin + "/vendedor");
    const inbox = seller.getByRole("link", { name: "Pedidos recibidos" });
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    await seller.route("**/api/v1/seller/orders?*", async (route) => {
      await pending;
      await route.abort("failed");
    }, { times: 1 });
    try {
      await inbox.press("Enter");
      await expect(seller.getByRole("status").filter({ hasText: "Cargando pedidos…" })).toBeVisible();
    } finally {
      release();
    }
    await expect(seller.getByRole("alert")).toHaveText("No pudimos cargar tus pedidos.");
    await seller.getByRole("button", { name: "Reintentar" }).press("Enter");
    await expect(seller.getByRole("heading", { name: alpha.title, exact: true })).toBeVisible();
    await expect(seller.getByText(beta.title, { exact: true })).toHaveCount(0);
    const filter = seller.getByLabel("Estado de la suborden");
    await filter.selectOption("ENTREGADA");
    await expect(seller.getByText("No hay subórdenes en esta página.")).toBeVisible();
    await filter.selectOption("PREPARANDO");

    const open = seller.getByRole("button", { name: "Registrar despacho" });
    await open.press("Enter");
    const modal = seller.getByRole("dialog", { name: "Registrar despacho" });
    const carrier = modal.getByLabel("Transportista");
    const tracking = modal.getByLabel("Código de seguimiento");
    await expect(carrier).toBeFocused();
    await seller.keyboard.press("Escape");
    await expect(modal).toHaveCount(0);
    await expect(open).toBeFocused();
    await seller.keyboard.press("Enter");
    await carrier.selectOption("ANDREANI");
    await seller.keyboard.press("Tab");
    await expect(tracking).toBeFocused();
    await tracking.fill("360000000000000");
    await seller.keyboard.press("Tab");
    const submit = modal.getByRole("button", { name: "Confirmar despacho" });
    await expect(submit).toBeFocused();
    await seller.route("**/api/v1/seller/sub-orders/*/ship", async (route) => {
      expect((await route.fetch()).status()).toBe(200);
      await route.abort("failed");
    }, { times: 1 });
    await seller.keyboard.press("Enter");
    await expect(modal.getByRole("alert")).toContainText("No pudimos registrar el despacho.");
    await expect(tracking).toHaveValue("360000000000000");
    await seller.screenshot({ path: testInfo.outputPath("despacho-reintento-movil.png"), fullPage: true });
    await submit.press("Enter");
    await expect(modal).toHaveCount(0);
    await expect(seller.getByText("Despacho registrado correctamente.")).toBeFocused();
    await filter.selectOption("DESPACHADA");
    await expect(seller.getByRole("status").filter({ hasText: /^Despachada$/ })).toBeVisible();
    expect(await seller.evaluate(() => document.documentElement.scrollWidth)).toBe(390);

    await page.reload();
    await expect(page.getByText("En preparación", { exact: true })).toHaveCount(1);
    const link = page.getByRole("link", { name: /Consultar en Andreani/ });
    await expect(link).toHaveAttribute("href", "https://andreani.com/envio/360000000000000");
    await link.focus();
    await page.keyboard.press("Tab");
    const confirm = page.getByRole("button", { name: "Confirmar recepción" });
    await expect(confirm).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByText("Recepción confirmada", { exact: true })).toBeVisible();
    await expect(page.getByText("En preparación", { exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByText("Entregada", { exact: true })).toBeVisible();
    await expect(page.getByRole("region", { name: "Seguimiento del envío" }).locator("time")).toHaveCount(2);
    await expect(confirm).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await page.screenshot({ path: testInfo.outputPath("entrega-dos-vendedores-movil.png"), fullPage: true });
  } finally {
    await seller.close();
  }
});
