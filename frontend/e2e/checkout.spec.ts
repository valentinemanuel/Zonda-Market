import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, publish } from "./cart-fixtures";
import { linkFromMail } from "./mailpit";
import { productPhoto } from "./product-photo";

const quotePath = "/api/v1/checkout/quote";
const confirmPath = "/api/v1/checkout/confirm";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 12_000 });

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
  await expect(page.getByText("$ 371,15", { exact: true })).toBeVisible();
  const timer = page.getByRole("timer");
  await expect(timer).toHaveText(/^1[0-5]:\d\d$/);
  const firstTick = await timer.innerText();
  await expect(timer).not.toHaveText(firstTick);
  await page.screenshot({ path: testInfo.outputPath("checkout-reserva-movil.png"), fullPage: true });

  await page.goto("/checkout");
  await expect(page).toHaveURL(origin + "/checkout/reserva/" + originalId);
  await expect(page.getByText("Productos reservados", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "Volver al carrito" }).press("Enter");
  await expect(page.getByRole("link", { name: "Ver mi reserva" })).toHaveAttribute("href", "/checkout/reserva/" + originalId);
  await expect(page.getByText("Incluido en tu reserva.")).toHaveCount(2);
  await expect(page.getByRole("button", { name: /^Quitar / })).toHaveCount(0);
});
