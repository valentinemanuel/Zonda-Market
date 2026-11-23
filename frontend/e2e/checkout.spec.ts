import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, publish } from "./cart-fixtures";
import { compose, sql } from "./compose-fixture";
import { linkFromMail } from "./mailpit";
import { productPhoto } from "./product-photo";
import { purchaseCartFixture } from "./purchase-fixture";

const quotePath = "/api/v1/checkout/quote";
const confirmPath = "/api/v1/checkout/confirm";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 12_000 });

test("comprar ahora cotiza una variante, confirma y conserva el carrito", async ({ page, request, baseURL, context }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const { alpha, beta, headers } = await purchaseCartFixture(page, request, origin, "directa", async (_request, email) => {
    await sql(`UPDATE zonda_market.user_account SET email_verified = TRUE WHERE email = '${email}'`);
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/publicaciones/" + alpha.slug);
  await expect(page.getByRole("button", { name: "Agregar al carrito" })).toBeEnabled();
  await page.getByRole("link", { name: "Comprar ahora" }).press("Enter");
  await expect(page).toHaveURL(origin + "/checkout?sku=" + alpha.skuId);
  const summary = page.getByRole("complementary", { name: "Resumen de compra" });
  await expect(summary.getByText(alpha.title)).toBeVisible();
  await expect(summary.getByText(beta.title)).toHaveCount(0);
  await expect(summary).toHaveCSS("position", "sticky");
  await expect(summary).toHaveCSS("top", "24px");
  await expect(summary.locator("img")).toHaveCount(1);
  await page.getByRole("button", { name: "Cotizar compra" }).press("Enter");
  await expect(summary.getByText("Total a pagar").locator("..")).toContainText("$ 100,00");
  await page.screenshot({ path: testInfo.outputPath("compra-directa-envio-1440.png"), fullPage: true });
  await page.getByRole("button", { name: "Confirmar reserva" }).press("Enter");
  await expect(summary.getByRole("timer")).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(summary).toHaveCSS("position", "static");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.getByRole("link", { name: "Volver a Envío" }).press("Enter");
  await page.getByRole("button", { name: "Cancelar reserva y volver" }).press("Enter");
  await expect(page).toHaveURL(origin + "/checkout?sku=" + alpha.skuId);
  await page.getByRole("button", { name: "Cotizar compra" }).press("Enter");
  await page.getByRole("button", { name: "Confirmar reserva" }).press("Enter");
  await page.getByRole("button", { name: "Simular aprobación" }).press("Enter");
  await expect(page.getByRole("heading", { name: "¡Gracias por tu compra!" })).toBeVisible();
  await expect(summary.getByRole("timer")).toHaveCount(0);
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.getByRole("button", { name: "Copiar número" }).press("Enter");
  await expect(page.getByText("Número de orden copiado.")).toBeVisible();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(page.url().split("/").at(-1));
  const stored = await request.get("/api/v1/cart", { headers });
  expect(stored.ok()).toBe(true);
  expect((await stored.json()).itemCount).toBe(2);
  await expect(page.getByRole("link", { name: "Ver en Mis órdenes" })).toHaveAttribute("href", "/mis-ordenes");
  await expect(page.getByRole("link", { name: "Seguir comprando", exact: true })).toHaveAttribute("href", "/catalogo");
  await page.screenshot({ path: testInfo.outputPath("compra-directa-confirmacion-390.png"), fullPage: true });
});

test("volver a Envío confirma la liberación y permite reservar de nuevo", async ({ page, request, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  await purchaseCartFixture(page, request, origin, "regreso", async (_request, email) => {
    await sql(`UPDATE zonda_market.user_account SET email_verified = TRUE WHERE email = '${email}'`);
  });
  await page.goto("/checkout");
  async function reserve() {
    await page.getByRole("button", { name: "Cotizar compra" }).press("Enter");
    await page.getByRole("button", { name: "Confirmar reserva" }).press("Enter");
    await expect(page.getByRole("timer")).toBeVisible();
    return page.url();
  }
  const original = await reserve();
  const back = page.getByRole("link", { name: "Volver a Envío" });
  const dialog = page.getByRole("dialog", { name: "¿Volver a Envío?" });
  await back.press("Enter");
  await expect(dialog.getByRole("button", { name: "Seguir en Pago" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(back).toBeFocused();
  await expect(page.getByRole("timer")).toBeVisible();
  await page.goBack();
  await expect(dialog).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("volver-a-envio-movil.png"), fullPage: true });
  await dialog.getByRole("button", { name: "Cancelar reserva y volver" }).press("Enter");
  await expect(page).toHaveURL(origin + "/checkout");
  await expect(page.getByRole("heading", { name: "Confirmar compra" })).toBeVisible();
  expect(await reserve()).not.toBe(original);
});

test("cotiza dos vendedores y recupera la reserva tras perder la respuesta", async ({ page, request, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  await page.goto("/registro");
  const photo = await productPhoto(page, "#2563eb");
  const first = await publish(request, origin, photo, "Alfa", 100.25);
  const second = await publish(request, origin, photo, "Beta", 250.90);
  const email = `checkout-${randomUUID().slice(0, 8)}@example.test`;

  await expect(page.getByRole("heading", { name: "Crear cuenta" })).toBeVisible();
  const registered = page.waitForResponse("**/api/v1/auth/register");
  await credentials(page, email);
  const session = await registered;
  expect(session.status()).toBe(201);
  const headers = { Authorization: "Bearer " + (await session.json()).accessToken };
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();

  await page.goto("/checkout");
  await expect(page.getByText("Verificá tu correo para confirmar la compra.")).toBeVisible();
  expect((await request.post("/api/v1/auth/email-verification/request", { data: { email } })).status()).toBe(202);
  const verification = await linkFromMail(request, email, "/verificar-correo");
  const token = new URLSearchParams(verification.hash.slice(1)).get("token");
  expect((await request.post("/api/v1/auth/email-verification/confirm", { data: { token } })).status()).toBe(204);

  const items = "/api/v1/cart/items/";
  expect((await request.put(items + first.skuId, { headers, data: { quantity: 1, expectedVersion: 0 } })).status()).toBe(200);
  expect((await request.put(items + second.skuId, { headers, data: { quantity: 1, expectedVersion: 1 } })).status()).toBe(200);
  await page.reload();
  await expect(page.getByText("Agregá una dirección de entrega para continuar.")).toBeVisible();

  const location = { city: "La Plata", provinceCode: "AR-B", postalCode: "1900", country: "AR" };
  for (const street of ["Calle Ficticia 100", "Calle Reserva 200"]) {
    expect((await request.post("/api/v1/me/addresses", { headers, data: { street, ...location } })).status()).toBe(201);
  }
  await page.goto("/carrito");
  await page.getByRole("link", { name: "Continuar al checkout" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Confirmar compra" })).toBeVisible();

  const address = page.getByLabel("Elegí una dirección");
  await address.press("End");
  await expect(address.locator("option:checked")).toHaveText(/Calle Reserva 200/);
  await address.press("Tab");
  await expect(page.getByRole("button", { name: "Cotizar compra" })).toBeFocused();

  let release!: () => void;
  const hold = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**" + quotePath, async (route) => {
    await hold;
    await route.continue();
  }, { times: 1 });
  await page.keyboard.press("Enter");
  try {
    await expect(page.getByRole("status")).toHaveText("Consultando importe y stock vigentes…");
  } finally {
    release();
  }
  await expect(page.getByText("Cotización lista. El total es $ 351,15.")).toBeVisible();
  await expect(page.getByRole("heading", { name: first.seller })).toBeVisible();
  await expect(page.getByRole("heading", { name: second.seller })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("checkout-dos-vendedores-movil.png"), fullPage: true });

  expect((await request.patch(first.privatePath + "/skus/" + first.skuId, {
    headers: first.headers, data: { expectedVersion: 0, price: 120.25 },
  })).status()).toBe(200);
  const confirmation = page.getByRole("button", { name: "Confirmar reserva" });
  await confirmation.press("Enter");
  await expect(page.getByRole("alert")).toContainText("Cambió el precio o la dirección.");
  await page.screenshot({ path: testInfo.outputPath("checkout-recotizar-movil.png"), fullPage: true });
  await page.getByRole("button", { name: "Cotizar compra" }).press("Enter");
  await expect(page.getByText("Cotización lista. El total es $ 371,15.")).toBeVisible();

  let originalId: string | undefined;
  let originalKey: string | undefined;
  await page.route("**" + confirmPath, async (route) => {
    originalKey = await route.request().headerValue("Idempotency-Key") ?? undefined;
    const original = await route.fetch();
    expect(original.status()).toBe(200);
    originalId = (await original.json()).id;
    await route.abort("failed");
  }, { times: 1 });
  await confirmation.press("Enter");
  await expect(page.getByRole("alert")).toContainText("No pudimos completar la solicitud.");
  expect(originalKey).toBeTruthy();
  const retry = page.waitForRequest("**" + confirmPath);
  await confirmation.press("Enter");
  expect(await (await retry).headerValue("Idempotency-Key")).toBe(originalKey);
  await expect(page).toHaveURL(origin + "/checkout/reserva/" + originalId);
  await expect(page.getByText("Productos reservados", { exact: true })).toBeVisible();
  await expect(page.getByText(/Calle Reserva 200/)).toBeVisible();
  await expect(page.getByText("Total a pagar", { exact: true }).locator("..")).toContainText("$ 371,15");
  const timer = page.getByRole("timer");
  await expect(timer).toHaveText(/^1[0-5]:\d\d$/);
  const firstTick = await timer.innerText();
  await expect(timer).not.toHaveText(firstTick);
  await page.screenshot({ path: testInfo.outputPath("checkout-reserva-movil.png"), fullPage: true });

  const chooseCard = page.getByRole("button", { name: "Pago con tarjeta de crédito o débito" });
  const choosePro = page.getByRole("button", { name: "Elegir saldo de Mercado Pago (simulado)" });
  await expect(chooseCard).toBeEnabled();
  await chooseCard.focus();
  await page.keyboard.press("Tab");
  await expect(choosePro).toBeFocused();
  await page.keyboard.press("Enter");
  const uncertain = page.getByText("El envío quedó sin respuesta. El intento sigue activo.");
  await expect(uncertain).toBeVisible();
  await expect(uncertain).toBeFocused();
  await expect(chooseCard).toHaveCount(0);
  await expect(choosePro).toHaveCount(0);
  await page.reload();
  await expect(uncertain).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("checkout-intento-incierto-movil.png"), fullPage: true });
  const consult = page.getByRole("button", { name: "Consultar estado" });
  await page.route("**/payment-options", (route) => route.abort("failed"), { times: 1 });
  await consult.press("Enter");
  await expect(page.getByRole("alert")).toHaveText("No pudimos consultar los medios de pago.");
  await consult.press("Enter");
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect(uncertain).toBeVisible();
  await page.getByRole("button", { name: "Marcar rechazado" }).press("Enter");
  await expect(page.getByText("Pago rechazado.", { exact: true })).toBeVisible();
  await expect(chooseCard).toBeEnabled();
  await expect(choosePro).toBeEnabled();
  await page.screenshot({ path: testInfo.outputPath("checkout-intento-rechazado-movil.png"), fullPage: true });

  await page.goto("/checkout");
  await expect(page).toHaveURL(origin + "/checkout/reserva/" + originalId);
  await expect(page.getByText("Productos reservados", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "Volver al carrito" }).press("Enter");
  await expect(page.getByRole("link", { name: "Ver mi reserva" })).toHaveAttribute("href", "/checkout/reserva/" + originalId);
  await expect(page.getByText("Incluido en tu reserva.")).toHaveCount(2);
  await expect(page.getByRole("button", { name: /^Quitar / })).toHaveCount(0);

  // Only the order created by this test is aged, while no worker is running.
  expect(originalId).toMatch(/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/);
  await compose("stop", "backend");
  try {
    await sql(`
      UPDATE zonda_market.checkout_order SET created_at = now() - interval '16 minutes',
        expires_at = now() - interval '1 minute' WHERE id = '${originalId}';
      UPDATE zonda_market.stock_reservation SET created_at = now() - interval '16 minutes',
        expires_at = now() - interval '1 minute' WHERE order_id = '${originalId}';
    `);
    expect(await sql(`SELECT status FROM zonda_market.checkout_order WHERE id = '${originalId}';`)).toBe("RESERVADA");
  } finally {
    await compose("start", "backend");
  }
  await expect.poll(() => sql(`SELECT status FROM zonda_market.checkout_order WHERE id = '${originalId}';`),
    { timeout: 35_000 }).toBe("EXPIRADA");
  await expect.poll(async () => (await request.get("/actuator/health/readiness")).status(), { timeout: 20_000 }).toBe(200);

  await page.goto("/checkout/reserva/" + originalId);
  await expect(page.getByText("La reserva venció.")).toBeVisible();
  await expect(page.getByText("Tus productos siguen en el carrito.")).toBeVisible();
  await expect(page.getByText("Cancelada", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("timer")).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("checkout-reserva-vencida-movil.png"), fullPage: true });
  await page.getByRole("main").getByRole("link", { name: "Mis órdenes" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mis órdenes" })).toBeVisible();
  await expect(page.getByText("Vencida", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("checkout-historial-movil.png"), fullPage: true });
  await page.getByRole("article", { name: "Orden #" + originalId!.slice(0, 8) })
    .getByRole("link", { name: "Ver detalle" }).press("Enter");
  await page.getByRole("link", { name: "Volver a cotizar" }).press("Enter");
  await page.getByRole("button", { name: "Cotizar compra" }).press("Enter");
  await expect(page.getByText("Cotización lista. El total es $ 371,15.")).toBeVisible();
  await page.getByRole("button", { name: "Confirmar reserva" }).press("Enter");
  const approve = page.getByRole("button", { name: "Simular aprobación" });
  await expect(approve).toBeVisible();
  let send!: () => void;
  const pending = new Promise<void>((resolve) => { send = resolve; });
  await page.route("**/test-fixtures/orders/*/payment-outcome", async (route) => {
    await pending;
    const saved = await route.fetch();
    expect(saved.status()).toBe(200);
    await route.abort("failed");
  }, { times: 1 });
  await approve.press("Enter");
  try {
    await expect(page.getByText("Registrando resultado…")).toBeVisible();
  } finally {
    send();
  }
  await expect(page.getByRole("alert")).toContainText("No pudimos registrar el resultado.");
  await page.screenshot({ path: testInfo.outputPath("checkout-pago-respuesta-perdida-movil.png"), fullPage: true });
  await approve.press("Enter");
  await expect(page.getByText("Pago de prueba aprobado.", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Carrito", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("Pago de prueba aprobado.", { exact: true })).toBeVisible();
  await expect(page.getByRole("timer")).toHaveCount(0);
  await expect(approve).toHaveCount(0);
  await expect(page.getByText("En preparación", { exact: true })).toHaveCount(2);
  const references = await page.getByText(/^Suborden /).allTextContents();
  expect(new Set(references).size).toBe(2);
  await expect(page.getByText("Total pagado", { exact: true }).locator("..")).toContainText("$ 371,15");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("checkout-pago-aprobado-movil.png"), fullPage: true });
  await expect(page.getByRole("heading", { name: "¡Gracias por tu compra!" })).toBeVisible();
  await page.getByRole("link", { name: "Ver en Mis órdenes" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mis órdenes" })).toBeVisible();
  await expect(page.getByText("Pagada", { exact: true })).toBeVisible();
});
