import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { PaymentAttempt, PaymentOptions } from "../src/features/checkout/checkout-api";
import { credentials } from "./cart-fixtures";

test.use({ viewport: { width: 390, height: 844 } });

test("vuelve de Checkout Pro y acredita solo después de consultar el pago", async ({ page }, testInfo) => {
  const id = randomUUID();
  const attemptId = randomUUID();
  const orderApi = "/api/v1/orders/" + id;
  const resultPage = "/checkout/resultado/" + id;
  const checkoutUrl = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=ficticia";
  const expiresAt = new Date(Date.now() + 15 * 60_000).toISOString();
  let attempt: PaymentAttempt | null = null;
  let verified = false;
  let consultations = 0;
  let finishPreparation!: () => void;
  const preparation = new Promise<void>((resolve) => { finishPreparation = resolve; });

  await page.route("**" + orderApi, (route) => route.fulfill({ json: {
    id, status: attempt?.status === "APROBADO" ? "PAGADA" : "RESERVADA", expiresAt,
    serverNow: new Date().toISOString(), total: "19.25", subOrders: [], testPayment: null,
  } }));
  await page.route("**" + orderApi + "/payment-options", (route) => {
    const options: PaymentOptions = { methods: attempt ? [] : ["CHECKOUT_PRO", "CARD"], latestAttempt: attempt,
      checkoutPro: true, card: null };
    return route.fulfill({ json: options });
  });
  await page.route("**" + orderApi + "/payments", (route) => {
    expect(route.request().postDataJSON()).toEqual({ method: "CHECKOUT_PRO" });
    attempt = { id: attemptId, provider: "MERCADO_PAGO", method: "CHECKOUT_PRO", status: "CREADO",
      nextAction: "ABRIR_CHECKOUT_PRO", expiresAt };
    return route.fulfill({ json: attempt });
  });
  await page.route("**" + orderApi + "/payments/pro", async (route) => {
    expect(await route.request().headerValue("Idempotency-Key")).toBe(attemptId);
    expect(route.request().postDataJSON()).toEqual({ attemptId });
    await preparation;
    attempt = { ...attempt!, status: "PENDIENTE", checkoutUrl };
    return route.fulfill({ json: attempt });
  });
  await page.route((url) => url.href === checkoutUrl, (route) => route.fulfill({
    contentType: "text/html", body: '<html lang="es"><title>Checkout Pro ficticio</title><h1>Checkout Pro ficticio</h1></html>',
  }));
  await page.route("**" + orderApi + "/payments/" + attemptId + "/reconcile-pro", (route) => {
    expect(route.request().postData()).toBeNull();
    expect(new URL(route.request().url()).search).toBe("");
    consultations++;
    if (consultations === 2) return route.abort("failed");
    if (verified) attempt = { ...attempt!, status: "APROBADO", nextAction: "NINGUNA", checkoutUrl: null };
    return route.fulfill({ json: attempt });
  });

  await page.goto("/registro");
  await credentials(page, "saldo-" + id.slice(0, 8) + "@example.test");
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await page.goto("/checkout/reserva/" + id);
  const pay = page.getByRole("button", { name: "Pagar con saldo de Mercado Pago" });
  await pay.press("Enter");
  try {
    await expect(page.getByText("Actualizando pago…")).toBeVisible();
  } finally {
    finishPreparation();
  }
  await expect(page.getByRole("heading", { name: "Checkout Pro ficticio" })).toBeVisible();

  await page.goto(new URL(resultPage + "?status=approved&payment_id=999", testInfo.project.use.baseURL).href);
  await expect(page.getByRole("heading", { name: "Resultado del pago" })).toBeVisible();
  await expect(page.getByText("El pago está pendiente de confirmación.")).toBeVisible();
  await expect(page.getByText("No pudimos consultar el pago. Volvé a intentarlo.")).toBeVisible();
  await expect(page.getByText("Pago aprobado.", { exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("saldo-pendiente-movil.png"), fullPage: true });

  verified = true;
  const consult = page.getByRole("button", { name: "Consultar estado" });
  await consult.press("Enter");
  await expect(page.getByText("Pago aprobado.", { exact: true })).toBeVisible();
  await expect(page.getByRole("timer")).toHaveCount(0);
  expect(consultations).toBe(3);
  await page.screenshot({ path: testInfo.outputPath("saldo-aprobado-movil.png"), fullPage: true });
});
