import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 8_000 });

test("abre y toma un reclamo privado y recupera un mensaje con teclado", async ({
  page: buyerPage, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const purchase = await purchaseFixture(buyerPage, request, origin, "reclamo", async (_request, email) => {
    expect(await sql(`UPDATE zonda_market.user_account SET email_verified = TRUE WHERE email = '${email}' RETURNING email`))
      .toContain(email);
  });
  await purchase.approve();
  const { order, alpha, beta } = purchase;
  const sub = order.subOrders.find(({ group }) => group.sellerName === alpha.seller)!;
  await buyerPage.goto("/mis-ordenes");
  await buyerPage.getByRole("article", { name: "Orden #" + order.id.slice(0, 8) })
    .getByRole("link", { name: "Ver detalle" }).press("Enter");
  const card = buyerPage.locator("#suborden-" + sub.id);
  const open = card.getByRole("button", { name: "Abrir un reclamo" });
  await open.press("Enter");
  const reasonField = card.getByLabel("Motivo (obligatorio)");
  await expect(reasonField).toBeFocused();
  await card.getByRole("button", { name: "Cancelar" }).press("Enter");
  await expect(open).toBeFocused();
  await buyerPage.keyboard.press("Enter");
  const reason = "Consulta privada " + randomUUID().slice(0, 8);
  await reasonField.fill(reason);
  await buyerPage.keyboard.press("Tab");
  await expect(card.getByLabel("Qué pasó (obligatorio)")).toBeFocused();
  await buyerPage.keyboard.type("Necesito ayuda con este pedido.");
  await buyerPage.keyboard.press("Tab");
  await expect(card.getByRole("button", { name: "Confirmar reclamo" })).toBeFocused();
  await buyerPage.keyboard.press("Enter");
  await expect(buyerPage.getByRole("heading", { name: "Reclamo privado" })).toBeFocused();
  await expect(buyerPage.getByText("No hay mensajes en esta página.")).toBeVisible();
  const id = new URL(buyerPage.url()).pathname.split("/").at(-1)!;
  const api = "/api/v1/disputes/" + id;
  expect((await request.get(api, { headers: beta.headers })).status()).toBe(404);
  const blocked = await request.post("/api/v1/seller/sub-orders/" + sub.id + "/ship", {
    headers: alpha.headers, data: { carrier: "ANDREANI", trackingCode: "PRUEBA-E603", expectedVersion: 0 },
  });
  expect(blocked.status()).toBe(409);
  expect((await blocked.json()).code).toBe("SUB_ORDER_DISPUTED");

  const sellerPage = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const adminPage = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await sellerPage.bringToFront();
    await sellerPage.goto(origin + "/ingresar");
    await credentials(sellerPage, alpha.email);
    await expect(sellerPage).toHaveURL(origin + "/catalogo");
    await expect.poll(async () => (await (await request.get("/api/v1/me/notifications", { headers: alpha.headers })).json())
      .content.some((event: { type: string; resourceId: string }) => event.type === "DISPUTE_OPENED" && event.resourceId === id))
      .toBe(true);
    await sellerPage.goto(origin + "/notificaciones");
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    await sellerPage.route("**" + api + "?*", async (route) => {
      await pending;
      await route.abort("failed");
    }, { times: 1 });
    try {
      await sellerPage.getByRole("link", { name: /Ver reclamo/ }).press("Enter");
      await expect(sellerPage.getByText("Cargando reclamo…")).toBeVisible();
    } finally {
      release();
    }
    await expect(sellerPage.getByRole("alert")).toHaveText("No pudimos cargar el reclamo.");
    await sellerPage.getByRole("button", { name: "Reintentar" }).press("Enter");
    await expect(sellerPage.getByRole("heading", { name: reason })).toBeVisible();

    const body = "<img src=x onerror=alert(1)> Revisaremos el pedido.";
    const message = sellerPage.getByLabel("Mensaje (obligatorio)");
    await message.fill(body);
    await sellerPage.route("**/api/v1/disputes/*/messages", async (route) => {
      const committed = await route.fetch();
      expect(committed.status()).toBe(201);
      await route.abort("failed");
    }, { times: 1 });
    const send = sellerPage.getByRole("button", { name: "Enviar mensaje" });
    await send.press("Enter");
    await expect(sellerPage.getByRole("alert")).toHaveText("No pudimos enviar el mensaje. Volvé a intentar.");
    await expect(message).toHaveValue(body);
    await sellerPage.screenshot({ path: testInfo.outputPath("reclamo-reintento-movil.png"), fullPage: true });
    await send.press("Enter");
    await expect(sellerPage.getByText("Mensaje enviado.")).toBeVisible();
    await expect(sellerPage.getByText(body, { exact: true })).toHaveCount(1);
    await expect(sellerPage.locator('img[src="x"]')).toHaveCount(0);
    expect(await sellerPage.evaluate(() => document.documentElement.scrollWidth)).toBe(390);

    const admin = "admin-reclamo-" + randomUUID() + "@example.test";
    expect((await request.post("/api/v1/auth/register", {
      headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email: admin, password },
    })).status()).toBe(201);
    expect(await sql(`UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${admin}' RETURNING email`)).toContain(admin);
    await adminPage.bringToFront();
    await adminPage.goto(origin + "/ingresar");
    await credentials(adminPage, admin);
    await expect(adminPage).toHaveURL(origin + "/catalogo");
    await adminPage.goto(origin + "/admin/reclamos");
    await adminPage.getByLabel("Estado", { exact: true }).selectOption("ABIERTA");
    await adminPage.getByLabel("Antigüedad").selectOption("7");
    await adminPage.getByRole("button", { name: "Filtrar" }).press("Enter");
    await adminPage.getByRole("link", { name: "Ver reclamo " + reason }).press("Enter");
    await adminPage.getByRole("button", { name: "Tomar para mediación" }).press("Enter");
    await expect(adminPage.getByText("En mediación", { exact: true })).toBeVisible();
    await expect(adminPage.getByText(body, { exact: true })).toBeVisible();
    await adminPage.screenshot({ path: testInfo.outputPath("reclamo-mediacion-movil.png"), fullPage: true });
    await buyerPage.bringToFront();
    await buyerPage.reload();
    await expect(buyerPage.getByText("En mediación", { exact: true })).toBeVisible();
    await expect(buyerPage.getByText(body, { exact: true })).toBeVisible();
  } finally {
    await sellerPage.close();
    await adminPage.close();
  }
});
