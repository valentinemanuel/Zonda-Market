import { expect, test } from "@playwright/test";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("califica una entrega con teclado y muestra su reseña como texto en la reputación pública", async (
  { page, browser, request, baseURL }, testInfo,
) => {
  const origin = new URL(baseURL!).origin;
  const { order, alpha, headers, approve } = await purchaseFixture(page, request, origin, "resenas");
  await approve();
  const [first] = order.subOrders;
  const reservation = "/checkout/reserva/" + order.id;
  const reviewPath = "/api/v1/me/sub-orders/" + first.id + "/review";
  await page.goto(reservation);
  await expect(page.getByText("En preparación", { exact: true })).toHaveCount(2);
  const form = page.getByRole("form", { name: "Calificá esta entrega" });
  await expect(form).toHaveCount(0);
  expect((await request.post("/api/v1/seller/sub-orders/" + first.id + "/ship", {
    headers: alpha.headers, data: { carrier: "ANDREANI", trackingCode: "360000000000000", expectedVersion: 0 },
  })).status()).toBe(200);
  await page.reload();
  await expect(page.getByText("Despachada", { exact: true })).toBeVisible();
  await expect(form).toHaveCount(0);
  await page.getByRole("button", { name: "Confirmar recepción" }).press("Enter");
  await expect(form).toBeVisible();
  await expect(page.getByText("En preparación", { exact: true })).toHaveCount(1);

  const firstStar = form.getByRole("radio", { name: "1 estrella", exact: true });
  const fiveStars = form.getByRole("radio", { name: "5 estrellas", exact: true });
  await firstStar.focus();
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
  await expect(fiveStars).toBeChecked();
  await expect(fiveStars).toBeFocused();
  await page.keyboard.press("Tab");
  const comment = form.getByLabel("Comentario (opcional)");
  const literal = "<strong>Entrega de prueba</strong>";
  await expect(comment).toBeFocused();
  await comment.fill(literal);
  await page.keyboard.press("Tab");
  const submit = form.getByRole("button", { name: "Publicar reseña" });
  await expect(submit).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("calificar-entrega-movil.png"), fullPage: true });
  await page.route("**" + reviewPath, async (route) => {
    expect((await route.fetch()).status()).toBe(201);
    await route.abort("failed");
  }, { times: 1 });
  await page.keyboard.press("Enter");
  await expect(form.getByRole("alert")).toContainText("No pudimos publicar la reseña.");
  await expect(fiveStars).toBeChecked();
  await expect(comment).toHaveValue(literal);
  await page.screenshot({ path: testInfo.outputPath("calificar-reintento-movil.png"), fullPage: true });
  await submit.press("Enter");
  const posted = page.getByText("Reseña publicada.", { exact: true });
  await expect(posted).toBeFocused();
  await expect(form).toHaveCount(0);
  await page.reload();
  await expect(posted).toBeVisible();
  expect((await request.post(reviewPath, { headers, data: { rating: 4 } })).status()).toBe(409);

  const visitor = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await visitor.goto(origin + "/publicaciones/" + alpha.slug);
    await expect(visitor.getByText("5 de 5 · 1 reseña", { exact: true })).toBeVisible();
    await visitor.route("**/api/v1/sellers/*/reviews?*", (route) => route.fulfill({ status: 503 }), { times: 1 });
    await visitor.getByRole("link", { name: alpha.seller, exact: true }).press("Enter");
    await expect(visitor.getByRole("heading", { name: "Reputación" })).toBeVisible();
    await expect(visitor.getByRole("alert")).toHaveText("No pudimos cargar las reseñas.");
    await visitor.getByRole("button", { name: "Reintentar" }).press("Enter");
    const list = visitor.getByRole("list", { name: "Reseñas del vendedor" });
    await expect(list.getByText(literal, { exact: true })).toBeVisible();
    await expect(list.locator("strong")).toHaveCount(0);
    await expect(list.getByRole("heading", { name: /^Comprador [0-9A-F]{8}$/ })).toBeVisible();
    await expect(list.getByRole("listitem")).toHaveCount(1);
    expect(await visitor.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await visitor.screenshot({ path: testInfo.outputPath("reputacion-publica-movil.png"), fullPage: true });
  } finally {
    await visitor.close();
  }
});
