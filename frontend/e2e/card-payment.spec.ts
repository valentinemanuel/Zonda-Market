import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type { PaymentAttempt, PaymentOptions } from "../src/features/checkout/checkout-api";
import { credentials } from "./cart-fixtures";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 10_000 });

test("recupera un envío de tarjeta sin respuesta y solo vuelve a ofrecer el formulario tras el rechazo", async ({ page }, testInfo) => {
  const email = "tarjeta-" + randomUUID().slice(0, 8) + "@example.test";
  const orderId = randomUUID();
  const orderPath = "/api/v1/orders/" + orderId;
  const expiry = new Date(Date.now() + 15 * 60_000).toISOString();
  const card = { publicKey: "publica-ficticia", amount: "19.25", payerEmail: email };
  let attempt: PaymentAttempt | null = null;
  let submissions = 0;
  let consultations = 0;

  await page.route("https://sdk.mercadopago.com/js/v2", async (route) => {
    await route.fulfill({ contentType: "text/javascript", body: `
      window.MercadoPago = class {
        constructor(key) {
          if (key !== 'publica-ficticia') throw new Error('Clave ficticia incorrecta');
        }
        bricks() {
          return { create: async (name, id, settings) => {
            if (name !== 'cardPayment' || settings.initialization.amount !== 19.25) throw new Error('Configuración incorrecta');
            const methods = settings.customization.paymentMethods.types.included.join(',');
            if (methods !== 'credit_card,debit_card') throw new Error('Medios incorrectos');
            const container = document.getElementById(id);
            const field = document.createElement('input');
            field.setAttribute('aria-label', 'Documento de prueba');
            const pay = document.createElement('button');
            pay.textContent = 'Enviar pago ficticio';
            pay.addEventListener('click', () => {
              const data = {
                token: 'token_ficticio_tarjeta', payment_method_id: 'debvisa', issuer_id: '33',
                transaction_amount: 1, installments: 1, payer: { identification: { type: 'DNI', number: field.value } },
              };
              const additional = { paymentTypeId: 'debit_card' };
              void Promise.all([settings.callbacks.onSubmit(data, additional), settings.callbacks.onSubmit(data, additional)]);
            });
            container.append(field, pay);
            settings.callbacks.onReady();
            return { unmount: () => container.replaceChildren() };
          } };
        }
      };
    ` });
  });
  await page.route("**" + orderPath, (route) => route.fulfill({ json: {
    id: orderId, status: attempt?.status === "APROBADO" ? "PAGADA" : "RESERVADA",
    serverNow: new Date().toISOString(), expiresAt: expiry, total: "19.25", subOrders: [], testPayment: null,
  } }));
  await page.route("**" + orderPath + "/payment-options", (route) => {
    const options: PaymentOptions = {
      methods: !attempt || attempt.status === "RECHAZADO" ? ["CARD"] : [],
      latestAttempt: attempt,
      card,
    };
    return route.fulfill({ json: options });
  });
  await page.route("**" + orderPath + "/payments", (route) => {
    expect(route.request().postDataJSON()).toEqual({ method: "CARD" });
    attempt = { id: randomUUID(), provider: "MERCADO_PAGO", method: "CARD", status: "CREADO",
      nextAction: "INGRESAR_TARJETA", expiresAt: expiry };
    return route.fulfill({ json: attempt });
  });
  let release!: () => void;
  const hold = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**" + orderPath + "/payments/card", async (route) => {
    submissions++;
    expect(await route.request().headerValue("Idempotency-Key")).toBe(attempt?.id);
    expect(route.request().postDataJSON()).toEqual({
      attemptId: attempt?.id, token: "token_ficticio_tarjeta", paymentMethodId: "debvisa", paymentType: "debit_card",
      installments: 1, payer: { email, identification: { type: "DNI", number: "12345678" } },
    });
    if (submissions === 1) {
      attempt = { ...attempt!, status: "RESULTADO_DESCONOCIDO", nextAction: "CONSULTAR" };
      await hold;
      await route.abort("failed");
      return;
    }
    attempt = { ...attempt!, status: "APROBADO", nextAction: "NINGUNA" };
    await route.fulfill({ json: attempt });
  });
  await page.route("**" + orderPath + "/payments/*/reconcile-card", (route) => {
    consultations++;
    expect(route.request().url()).toContain(attempt!.id);
    expect(route.request().postData()).toBeNull();
    attempt = consultations === 1
      ? { ...attempt!, status: "PENDIENTE", nextAction: "CONSULTAR" }
      : { ...attempt!, status: "RECHAZADO", nextAction: "ELEGIR_MEDIO" };
    return route.fulfill({ json: attempt });
  });

  await page.goto("/registro");
  await credentials(page, email);
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await page.goto("/checkout/reserva/" + orderId);
  const chooseCard = page.getByRole("button", { name: "Pagar con tarjeta" });
  const document = page.getByRole("textbox", { name: "Documento de prueba" });
  const send = page.getByRole("button", { name: "Enviar pago ficticio" });
  await chooseCard.press("Enter");
  await document.fill("12345678");
  await document.press("Tab");
  await expect(send).toBeFocused();
  await page.keyboard.press("Enter");
  try {
    await expect(page.getByText("Enviando el pago con tarjeta…", { exact: true })).toBeVisible();
    await expect(send).toBeDisabled();
    await send.evaluate((button: HTMLButtonElement) => button.click());
    expect(submissions).toBe(1);
  } finally {
    release();
  }
  const uncertain = page.getByText("El envío quedó sin respuesta. El intento sigue activo.");
  await expect(uncertain).toBeVisible();
  await expect(send).toHaveCount(0);
  await expect(chooseCard).toHaveCount(0);
  expect(submissions).toBe(1);
  await page.reload();
  await expect(uncertain).toBeVisible();
  expect(await page.evaluate(() => window.document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("tarjeta-resultado-incierto-movil.png"), fullPage: true });

  const consult = page.getByRole("button", { name: "Consultar estado" });
  await consult.press("Enter");
  await expect(page.getByText("El pago está pendiente de confirmación.")).toBeFocused();
  await expect(consult).toBeEnabled();
  await consult.press("Enter");
  const rejected = page.getByText("Pago rechazado.", { exact: true });
  await expect(rejected).toBeFocused();
  await expect(chooseCard).toBeEnabled();
  await chooseCard.press("Enter");
  await document.fill("12345678");
  await send.press("Enter");
  await expect(send).toBeDisabled();
  await expect(send).toBeVisible();
  await expect(page.getByText("Pago aprobado.", { exact: true })).toBeVisible();
  await expect(page.getByRole("timer")).toHaveCount(0);
  expect(submissions).toBe(2);
  await page.screenshot({ path: testInfo.outputPath("tarjeta-aprobada-movil.png"), fullPage: true });
  await page.getByRole("link", { name: "Volver a Pago" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Detalle del pago" })).toBeVisible();
  await expect(chooseCard).toHaveCount(0);
  await page.getByRole("link", { name: "Ver confirmación" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Resultado del pago" })).toBeVisible();
});
