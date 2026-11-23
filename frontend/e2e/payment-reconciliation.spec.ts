import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { PaymentOptions, Refund } from "../src/features/checkout/checkout-api";
import { credentials } from "./cart-fixtures";

test.use({ viewport: { width: 390, height: 844 } });

test("recupera una confirmación persistida en la reserva abierta y al recargar", async ({ page }, testInfo) => {
  const order = randomUUID();
  const attempt = randomUUID();
  const path = "/api/v1/orders/" + order;
  const expiresAt = new Date(Date.now() + 15 * 60_000).toISOString();
  let approved = false;
  let optionsReads = 0;
  let manualReads = 0;
  let refund: Refund | null = null;

  await page.route("**" + path, (route) => route.fulfill({ json: {
    id: order, status: approved ? "PAGADA" : "RESERVADA", expiresAt, serverNow: new Date().toISOString(),
    total: "19.25", subOrders: [], testPayment: null,
    refunds: refund ? [refund] : [],
    refundedAmount: refund?.status === "CONFIRMADA" ? refund.amount : "0.00",
  } }));
  await page.route("**" + path + "/payment-options", (route) => {
    optionsReads++;
    const options: PaymentOptions = {
      methods: [], card: null,
      latestAttempt: { id: attempt, method: "CARD", provider: "MERCADO_PAGO", expiresAt,
        status: approved ? "APROBADO" : "PENDIENTE", nextAction: approved ? "NINGUNA" : "CONSULTAR" },
    };
    return route.fulfill({ json: options });
  });
  await page.route("**" + path + "/payments/*/reconcile-card", (route) => {
    manualReads++;
    return route.fulfill({ status: 503 });
  });

  await page.goto("/registro");
  await credentials(page, "webhook-" + order.slice(0, 8) + "@example.test");
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await page.goto("/checkout/reserva/" + order);
  const pending = page.getByText("El pago está pendiente de confirmación.", { exact: true });
  await expect(pending).toBeVisible();
  await page.reload();
  await expect(pending).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("confirmacion-pendiente-movil.png"), fullPage: true });
  const orderList = page.getByRole("main").getByRole("link", { name: "Mis órdenes", exact: true });
  await orderList.focus();
  approved = true;
  await expect(page.getByText("Pagada", { exact: true })).toBeVisible();
  await expect(page.getByRole("timer")).toHaveCount(0);
  await expect(orderList).toBeFocused();
  expect(optionsReads).toBeGreaterThanOrEqual(3);
  expect(manualReads).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("confirmacion-aprobada-movil.png"), fullPage: true });

  refund = { id: randomUUID(), reason: "DUPLICADO", status: "PENDIENTE", amount: "19.25" };
  await page.reload();
  const compensation = page.getByRole("region", { name: "Devoluciones" });
  await expect(compensation.getByText("Cobro duplicado")).toBeVisible();
  await expect(compensation.getByRole("status")).toHaveText("Devolución pendiente.");
  await page.screenshot({ path: testInfo.outputPath("devolucion-pendiente-movil.png"), fullPage: true });
  refund = { ...refund, status: "CONFIRMADA" };
  await expect(compensation.getByRole("status")).toHaveText("Devolución confirmada.", { timeout: 10_000 });
  await expect(compensation).toContainText("19,25");
  await expect(page.getByText("Pagada", { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("devolucion-confirmada-movil.png"), fullPage: true });
});
