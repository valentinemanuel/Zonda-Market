import { expect, test } from "@playwright/test";
import { credentials } from "./cart-fixtures";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("muestra solo las ventas propias y permite filtrar y leer la tabla con teclado", async (
  { page, browser, request, baseURL }, testInfo,
) => {
  const origin = new URL(baseURL!).origin;
  const { order, alpha, headers, approve } = await purchaseFixture(page, request, origin, "ventas");
  await approve();
  await approve();
  const [own, other] = order.subOrders;
  expect((await request.get("/api/v1/seller/metrics", { headers })).status()).toBe(403);

  const seller = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await seller.goto(origin + "/ingresar");
    await credentials(seller, alpha.email);
    await expect(seller).toHaveURL(origin + "/catalogo");
    await seller.goto(origin + "/vendedor");
    let release!: () => void;
    const hold = new Promise<void>((resolve) => { release = resolve; });
    await seller.route(/\/api\/v1\/seller\/metrics(?:\?|$)/, async (route) => {
      await hold;
      await route.fulfill({ status: 503 });
    }, { times: 1 });
    try {
      await seller.getByRole("link", { name: "Ventas y neto estimado" }).press("Enter");
      await expect(seller.getByRole("status").filter({ hasText: "Cargando ventas…" })).toBeVisible();
    } finally {
      release();
    }
    await expect(seller.getByRole("alert")).toHaveText("No pudimos cargar tus ventas.");
    await seller.screenshot({ path: testInfo.outputPath("ventas-reintento-movil.png"), fullPage: true });
    await seller.getByRole("button", { name: "Reintentar" }).press("Enter");

    const summary = seller.getByRole("region", { name: "Resumen de ventas" });
    await expect(summary.getByText("$ 100,00", { exact: true })).toHaveCount(2);
    await expect(summary.getByText("$ 150,00", { exact: true })).toHaveCount(0);
    await expect(summary.getByText("En preparación · 1", { exact: true })).toBeVisible();
    const table = seller.getByRole("region", { name: "Actividad por suborden", exact: true });
    await expect(table.getByRole("link", { name: "Ver suborden " + own.id })).toBeVisible();
    await expect(table.getByRole("link", { name: "Ver suborden " + other.id })).toHaveCount(0);
    expect(await seller.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await table.focus();
    await table.press("ArrowRight");
    await expect.poll(() => table.evaluate((node) => node.scrollLeft)).toBeGreaterThan(0);
    await seller.screenshot({ path: testInfo.outputPath("ventas-resumen-movil.png"), fullPage: true });

    const from = seller.getByLabel("Desde", { exact: true });
    const to = seller.getByLabel("Hasta", { exact: true });
    const today = await to.inputValue();
    const apply = seller.getByRole("button", { name: "Aplicar período" });
    await from.fill("2000-01-02");
    await to.fill("2000-01-01");
    await apply.press("Enter");
    await expect(seller.getByRole("alert")).toHaveText("La fecha Hasta debe ser igual o posterior a Desde.");
    await expect(to).toBeFocused();
    await from.fill("2000-01-01");
    await apply.press("Enter");
    await expect(seller.getByText("No hay movimientos en este período.", { exact: true })).toBeVisible();

    await from.fill(today);
    await to.fill(today);
    await apply.press("Enter");
    await seller.getByText("Ver importes por día", { exact: true }).press("Enter");
    await expect(seller.getByRole("region", { name: "Importes diarios" }).getByText("$ 100,00", { exact: true })).toHaveCount(2);
    await expect(table.getByRole("link", { name: "Ver suborden " + own.id })).toBeVisible();
    await table.getByRole("link", { name: "Ver suborden " + own.id }).press("Enter");
    await expect(seller).toHaveURL(origin + "/vendedor/pedidos?suborden=" + own.id);
    await expect(seller.getByRole("heading", { name: alpha.title })).toBeVisible();
  } finally {
    await seller.close();
  }
});
