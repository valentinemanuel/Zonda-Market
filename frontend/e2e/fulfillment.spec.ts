import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, publish } from "./cart-fixtures";
import { linkFromMail } from "./mailpit";
import { productPhoto } from "./product-photo";

test.use({ viewport: { width: 390, height: 844 } });

test("registra un despacho y confirma solo esa entrega con teclado en móvil", async ({ page, browser, request, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  await page.goto("/registro");
  const photo = await productPhoto(page, "#2563eb");
  const alpha = await publish(request, origin, photo, "Alfa envío", 100);
  const beta = await publish(request, origin, photo, "Beta envío", 150);
  const buyer = `envio-${randomUUID().slice(0, 8)}@example.test`;
  const registered = page.waitForResponse("**/api/v1/auth/register");
  await credentials(page, buyer);
  const headers = { Authorization: "Bearer " + (await (await registered).json()).accessToken };
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  expect((await request.post("/api/v1/auth/email-verification/request", { data: { email: buyer } })).status()).toBe(202);
  const verification = await linkFromMail(request, buyer, "/verificar-correo");
  const token = new URLSearchParams(verification.hash.slice(1)).get("token");
  expect((await request.post("/api/v1/auth/email-verification/confirm", { data: { token } })).status()).toBe(204);
  const address = await request.post("/api/v1/me/addresses", {
    headers, data: { street: "Calle Ficticia 200", city: "La Plata", provinceCode: "AR-B", postalCode: "1900", country: "AR" },
  });
  expect(address.status()).toBe(201);
  for (const [expectedVersion, product] of [alpha, beta].entries()) {
    expect((await request.put("/api/v1/cart/items/" + product.skuId, {
      headers, data: { quantity: 1, expectedVersion },
    })).status()).toBe(200);
  }
  const quote = await request.post("/api/v1/checkout/quote", {
    headers, data: { addressId: (await address.json()).id, cartVersion: 2 },
  });
  expect(quote.status()).toBe(200);
  const { id: quoteId } = await quote.json();
  const reservation = await request.post("/api/v1/checkout/confirm", {
    headers: { ...headers, "Idempotency-Key": quoteId }, data: { quoteId },
  });
  expect(reservation.status()).toBe(200);
  const order = await reservation.json();
  expect((await request.post(`/api/v1/test-fixtures/orders/${order.id}/payment-outcome`, {
    headers, data: { reference: order.testPayment.reference, outcome: "APPROVED" },
  })).status()).toBe(200);

  await page.goto("/mis-ordenes");
  await page.getByRole("link", { name: "Ver orden " + order.id }).press("Enter");
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
