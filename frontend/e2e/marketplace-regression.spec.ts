import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { Order } from "../src/features/checkout/checkout-api";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";
import { purchaseCartFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 }, locale: "es-AR" });

test("mantiene una compra de dos vendedores desde checkout hasta una devolución parcial", async ({
  page: buyer, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const { alpha, beta, addressId, headers } = await purchaseCartFixture(buyer, request, origin, "regresion");
  const inventory = () => sql(`
    SELECT concat_ws('|', count(*), sum(available_stock), sum(reserved_stock))
    FROM zonda_market.listing_sku WHERE id IN ('${alpha.skuId}', '${beta.skuId}')
  `);
  let order!: Order;

  await test.step("Reserva, pago de prueba y replay sin consumir stock dos veces", async () => {
    await buyer.goto("/carrito");
    await buyer.getByRole("link", { name: "Continuar al checkout" }).press("Enter");
    const address = buyer.getByLabel("Elegí una dirección");
    await address.selectOption(addressId);
    await address.press("Tab");
    await expect(buyer.getByRole("button", { name: "Cotizar compra" })).toBeFocused();
    await buyer.keyboard.press("Enter");
    await expect(buyer.getByText("Cotización lista. El total es $ 250,00.")).toBeVisible();
    await expect(buyer.getByRole("heading", { name: alpha.seller })).toBeVisible();
    await expect(buyer.getByRole("heading", { name: beta.seller })).toBeVisible();
    await buyer.getByRole("button", { name: "Confirmar reserva" }).press("Enter");
    await expect(buyer.getByText("Productos reservados", { exact: true })).toBeVisible();
    expect(await inventory()).toBe("2|8|2");

    const [approved] = await Promise.all([
      buyer.waitForResponse((response) => response.url().endsWith("/payment-outcome") && response.request().method() === "POST"),
      buyer.getByRole("button", { name: "Simular aprobación" }).press("Enter"),
    ]);
    expect(approved.status()).toBe(200);
    order = await approved.json();
    expect(order.status).toBe("PAGADA");
    expect(order.subOrders).toHaveLength(2);
    await expect(buyer.getByText("Pago de prueba aprobado.", { exact: true })).toBeVisible();
    const replay = await request.post(approved.url(), { headers, data: approved.request().postDataJSON() });
    expect(replay.status()).toBe(200);
    expect((await replay.json()).id).toBe(order.id);
    expect(await inventory()).toBe("2|8|0");
    expect(await sql(`SELECT count(*) FROM zonda_market.checkout_payment_outcome WHERE order_id = '${order.id}'`)).toBe("1");
  });

  const own = order.subOrders.find(({ group }) => group.sellerName === alpha.seller)!;
  const other = order.subOrders.find(({ group }) => group.sellerName === beta.seller)!;
  const ownCard = buyer.locator("#suborden-" + own.id);
  const otherCard = buyer.locator("#suborden-" + other.id);

  await test.step("Despacho propio y recepción de una sola suborden", async () => {
    const foreign = await request.post(`/api/v1/seller/sub-orders/${own.id}/ship`, {
      headers: beta.headers, data: { carrier: "ANDREANI", trackingCode: "AJENO-E701", expectedVersion: 0 },
    });
    expect(foreign.status()).toBe(404);
    const seller = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: "es-AR" });
    try {
      await seller.goto(origin + "/ingresar");
      await credentials(seller, alpha.email);
      await expect(seller).toHaveURL(origin + "/catalogo");
      await seller.goto(origin + "/vendedor/pedidos?suborden=" + own.id);
      await seller.getByRole("button", { name: "Registrar despacho" }).press("Enter");
      const dialog = seller.getByRole("dialog", { name: "Registrar despacho" });
      await expect(dialog.getByLabel("Transportista")).toBeFocused();
      await dialog.getByLabel("Transportista").selectOption("ANDREANI");
      await dialog.getByLabel("Código de seguimiento").fill("E701-DESPACHO");
      await dialog.getByRole("button", { name: "Confirmar despacho" }).press("Enter");
      await expect(seller.getByText("Despacho registrado correctamente.")).toBeFocused();
    } finally {
      await seller.close();
    }
    await buyer.reload();
    await ownCard.getByRole("button", { name: "Confirmar recepción" }).press("Enter");
    await expect(ownCard.getByText("Recepción confirmada", { exact: true })).toBeVisible();
    await expect(otherCard.getByText("En preparación", { exact: true })).toBeVisible();
    await expect(otherCard.getByRole("button", { name: "Confirmar recepción" })).toHaveCount(0);
  });

  await test.step("Reclamo privado, mediación y ajuste confirmado", async () => {
    await ownCard.getByRole("button", { name: "Abrir un reclamo" }).press("Enter");
    const reason = ownCard.getByLabel(/Motivo/);
    await expect(reason).toBeFocused();
    await reason.fill("Daño en la entrega de prueba");
    await ownCard.getByLabel(/Qué pasó/).fill("Una pieza llegó dañada en la entrega ficticia.");
    await ownCard.getByRole("button", { name: "Confirmar reclamo" }).press("Enter");
    await expect(buyer.getByRole("heading", { name: "Daño en la entrega de prueba" })).toBeVisible();
    const disputeUrl = new URL(buyer.url());
    const dispute = disputeUrl.pathname.split("/").at(-1);
    expect(dispute).toMatch(/^[0-9a-f-]{36}$/);
    const detail = "/api/v1/disputes/" + dispute;
    expect((await request.get(detail, { headers: beta.headers })).status()).toBe(404);

    const email = `admin-e701-${randomUUID()}@example.test`;
    const registered = await request.post("/api/v1/auth/register", {
      headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email, password },
    });
    expect(registered.status()).toBe(201);
    expect(await sql(`
      WITH promoted AS (
        UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${email}' AND role = 'USER' RETURNING role
      )
      SELECT role FROM promoted
    `)).toBe("ADMIN");
    expect((await request.post(`/api/v1/admin/disputes/${dispute}/assign`, { headers: alpha.headers })).status()).toBe(403);

    const admin = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: "es-AR" });
    try {
      await admin.goto(origin + "/ingresar");
      await credentials(admin, email);
      await expect(admin).toHaveURL(origin + "/catalogo");
      await admin.goto(disputeUrl.href);
      await admin.getByRole("button", { name: "Tomar para mediación" }).press("Enter");
      await admin.getByRole("button", { name: "Resolver reclamo" }).press("Enter");
      const dialog = admin.getByRole("dialog", { name: "Resolver reclamo" });
      const outcome = dialog.getByLabel("Resultado");
      await expect(outcome).toBeFocused();
      await outcome.selectOption("REEMBOLSO");
      await dialog.getByLabel(/Monto en pesos/).fill("25,00");
      await dialog.getByLabel(/Motivo de la resolución/).fill("Acuerdo parcial ficticio");
      const confirm = dialog.getByRole("button", { name: "Confirmar resolución" });
      await expect(confirm).toBeEnabled();
      await confirm.press("Enter");
      await expect(admin.getByText("Devolución confirmada", { exact: true })).toBeVisible({ timeout: 15_000 });
      expect(await admin.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
      await admin.screenshot({ path: testInfo.outputPath("regresion-mediacion-movil.png"), fullPage: true });
    } finally {
      await admin.close();
    }

    await buyer.reload();
    await expect(buyer.getByText("Devolución confirmada", { exact: true })).toBeVisible();
    const authorized = await request.get(detail, { headers: alpha.headers });
    expect(authorized.status()).toBe(200);
    expect((await authorized.json()).resolution).toMatchObject({ status: "CONFIRMADA", amount: "25.00", simulated: true });
    const updated = await request.get("/api/v1/orders/" + order.id, { headers });
    expect(updated.status()).toBe(200);
    expect(await updated.json()).toMatchObject({
      status: "REEMBOLSO_PARCIAL",
      subOrders: expect.arrayContaining([
        expect.objectContaining({ id: other.id, status: "PREPARANDO", shipment: null }),
        expect.objectContaining({ id: own.id, status: "ENTREGADA" }),
      ]),
    });
    const sellerMetrics = await request.get("/api/v1/seller/metrics", { headers: alpha.headers });
    expect(sellerMetrics.status()).toBe(200);
    expect((await sellerMetrics.json()).refunds).toBe("25.00");
    expect(await sql(`
      SELECT concat_ws('|', count(*), sum(refund.merchandise_refunded + refund.shipping_refunded))
      FROM zonda_market.checkout_sub_order_refund refund
      JOIN zonda_market.checkout_sub_order sub ON sub.id = refund.sub_order_id
      WHERE sub.order_id = '${order.id}' AND sub.id = '${own.id}'
    `)).toBe("1|25.00");
    expect(await inventory()).toBe("2|8|0");
  });
});
