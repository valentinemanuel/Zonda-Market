import { expect, test } from "@playwright/test";
import { purchaseFixture } from "./purchase-fixture";

test("busca, filtra y repite una compra sin duplicar el carrito si se pierde la respuesta", async ({ page, request, baseURL }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const { order, alpha, beta, headers, approve } = await purchaseFixture(page, request, new URL(baseURL!).origin, "historial");
  const shortId = "#" + order.id.slice(0, 8);
  const id = "Orden " + shortId;
  await page.goto("/mis-ordenes?filtro=PAGADAS");
  await expect(page).toHaveURL(/\/cuenta\/ordenes\?filtro=PAGADAS$/);
  const accountNav = page.getByRole("navigation", { name: "Secciones de Mi cuenta" });
  await expect(accountNav.getByRole("link", { name: "Mis órdenes" })).toHaveAttribute("aria-current", "page");
  await accountNav.getByRole("link", { name: "Perfil y seguridad" }).press("Enter");
  await page.getByRole("link", { name: "Historial de compras" }).press("Enter");
  await expect(page).toHaveURL(/\/cuenta\/ordenes$/);
  await expect(accountNav.getByRole("link", { name: "Mis direcciones" })).toBeVisible();
  const card = page.getByRole("article", { name: id });
  await expect(card.getByText("Reservada", { exact: true })).toBeVisible();
  await expect(card.getByRole("button", { name: "Volver a comprar" })).toHaveCount(0);
  await approve();
  await page.reload();

  const search = page.getByRole("searchbox", { name: "Buscar en tus órdenes" });
  await search.fill(beta.title);
  await search.press("Enter");
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === beta.title);
  await expect(card).toBeVisible();
  await expect(search).toBeFocused();
  await search.fill(shortId);
  await search.press("Enter");
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === shortId);
  await expect(card).toBeVisible();
  await page.getByRole("button", { name: "Entregadas" }).press("Enter");
  await expect(page.getByRole("heading", { name: "No encontramos órdenes con esos filtros" })).toBeVisible();
  const preparing = page.getByRole("button", { name: "En preparación" });
  await preparing.press("Enter");
  await expect(preparing).toBeFocused();
  await expect(preparing).toHaveAttribute("aria-pressed", "true");
  await expect(card.getByText("Pagada", { exact: true })).toBeVisible();
  await expect(card.getByText("2 unidades", { exact: true })).toBeVisible();
  await expect(card.locator("img")).toHaveCount(2);
  await expect.poll(() => card.locator("img").evaluateAll((images) =>
    images.every((image) => image instanceof HTMLImageElement && image.naturalWidth > 0))).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("historial-390.png"), animations: "disabled", fullPage: true });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: testInfo.outputPath("historial-1440.png"), animations: "disabled" });

  await card.getByRole("link", { name: "Ver detalle" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Detalle de la orden" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Resumen de compra" })).toBeVisible();
  await expect(page.getByText("Total pagado", { exact: true })).toBeVisible();
  await expect(page.getByText("En preparación", { exact: true })).toHaveCount(2);
  await page.goBack();
  await expect(preparing).toHaveAttribute("aria-pressed", "true");
  await expect(search).toHaveValue(shortId);

  await page.route("**/api/v1/cart/reorders/" + order.id, async (route) => {
    const committed = await route.fetch();
    expect(committed.ok()).toBe(true);
    await route.abort();
  }, { times: 1 });
  const reorder = card.getByRole("button", { name: "Volver a comprar" });
  await reorder.press("Enter");
  await expect(card.getByRole("alert")).toContainText("No pudimos confirmar la recompra");
  await reorder.press("Enter");
  await expect(card.getByRole("status")).toHaveText("Productos agregados al carrito.");
  const stored = await request.get("/api/v1/cart", { headers });
  expect(stored.ok()).toBe(true);
  expect((await stored.json()).itemCount).toBe(2);
  await card.getByRole("link", { name: "Ver carrito" }).press("Enter");
  await expect(page.getByRole("link", { name: alpha.title, exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: beta.title, exact: true })).toBeVisible();
});
