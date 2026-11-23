import { expect, test, type APIRequestContext } from "@playwright/test";
import { credentials } from "./cart-fixtures";
import { purchaseFixture } from "./purchase-fixture";

const inbox = "/api/v1/me/notifications";
const mailpit = process.env.ZONDA_MAILPIT_URL ?? "http://127.0.0.1:8025";
type Mail = { ID: string; Subject: string; To: { Address: string }[] };

async function messagesFor(request: APIRequestContext, recipients: string[]) {
  const response = await request.get(mailpit + "/api/v1/messages?limit=50");
  expect(response.ok()).toBe(true);
  const { messages } = await response.json() as { messages: Mail[] };
  return messages.filter(({ To, Subject }) => To.some(({ Address }) => recipients.includes(Address))
    && ["Pago aprobado", "Pedido despachado", "Entrega confirmada"].includes(Subject));
}

test.use({ viewport: { width: 390, height: 844 } });

test("notifica por destinatario, conserva la lectura ante un fallo y abre el hito privado", async (
  { page, browser, request, baseURL }, testInfo,
) => {
  const origin = new URL(baseURL!).origin;
  await page.goto("/notificaciones");
  await expect(page.getByRole("link", { name: "Iniciar sesión para ver tus notificaciones" })).toBeVisible();
  await expect(page.getByRole("button", { name: /^Notificaciones/ })).toHaveCount(0);
  const { order, alpha, beta, buyer, headers, approve } = await purchaseFixture(page, request, origin, "notificaciones");
  const [first] = order.subOrders;
  const target = `/checkout/reserva/${order.id}#suborden-${first.id}`;
  await page.goto("/notificaciones");
  await expect(page.getByText("Todavía no tenés notificaciones.")).toBeVisible();

  await approve();
  await approve();
  const shipPath = `/api/v1/seller/sub-orders/${first.id}/ship`;
  for (let i = 0; i < 2; i++) {
    expect((await request.post(shipPath, { headers: alpha.headers, data: {
      carrier: "ANDREANI", trackingCode: "360000000000000", expectedVersion: 0,
    } })).status()).toBe(200);
  }
  await page.goto("/catalogo");
  const bell = page.getByRole("button", { name: "Notificaciones, 2 sin leer" });
  await expect(bell).toBeVisible({ timeout: 15_000 });
  await bell.press("Enter");
  const panel = page.getByRole("dialog", { name: "Notificaciones recientes" });
  await expect(panel.getByRole("link", { name: /Pedido despachado/ })).toBeVisible();
  await expect(page).toHaveURL(origin + "/catalogo");
  await page.screenshot({ path: testInfo.outputPath("notificaciones-en-catalogo.png") });
  await page.keyboard.press("Escape");
  await expect(panel).toBeHidden();
  await expect(bell).toBeFocused();
  await bell.click();
  await page.getByRole("searchbox", { name: "Buscar en la tienda" }).click();
  await expect(panel).toBeHidden();
  await bell.press("Enter");
  await panel.getByRole("link", { name: "Ver todas mis notificaciones" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Notificaciones", exact: true })).toBeVisible();
  const shipped = page.getByRole("listitem").filter({ has: page.getByRole("heading", { name: "Pedido despachado" }) });
  const mark = shipped.getByRole("button", { name: /Marcar como leída/ });
  const follow = shipped.getByRole("link", { name: /Ver compra/ });
  await follow.focus();
  await page.keyboard.press("Tab");
  await expect(mark).toBeFocused();
  let release!: () => void;
  const hold = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/me/notifications/*/read", async (route) => {
    await hold;
    await route.fulfill({ status: 503 });
  }, { times: 1 });
  try {
    await page.keyboard.press("Enter");
    await expect(shipped.getByRole("button", { name: /Guardando/ })).toBeFocused();
    await expect(page.getByRole("button", { name: "Notificaciones, 1 sin leer" })).toBeVisible();
  } finally {
    release();
  }
  await expect(shipped.getByRole("alert")).toContainText("No pudimos marcar la notificación");
  await expect(bell).toBeVisible();
  await expect(mark).toBeFocused();
  await page.screenshot({ path: testInfo.outputPath("notificaciones-lectura-reintento.png"), fullPage: true });
  await page.keyboard.press("Enter");
  await expect(shipped.getByRole("button", { name: "Leída Pedido despachado" })).toBeFocused();
  await expect(page.getByRole("button", { name: "Notificaciones, 1 sin leer" })).toBeVisible();
  await page.reload();
  await expect(shipped.getByRole("button", { name: "Leída Pedido despachado" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("notificaciones-compra-y-despacho.png"), fullPage: true });

  const payment = page.getByRole("listitem").filter({ has: page.getByRole("heading", { name: "Pago aprobado" }) });
  const readOnOpen = page.waitForResponse((response) =>
    response.url().endsWith("/read") && response.request().method() === "PATCH");
  await payment.getByRole("link", { name: /Ver compra/ }).press("Enter");
  await expect(page).toHaveURL(origin + "/checkout/reserva/" + order.id);
  expect((await readOnOpen).ok()).toBe(true);
  await expect(page.getByRole("button", { name: "Notificaciones", exact: true })).toBeVisible();
  await page.goto("/notificaciones");
  await expect(payment.getByRole("button", { name: "Leída Pago aprobado" })).toBeVisible();

  await follow.press("Enter");
  await expect(page).toHaveURL(origin + target);
  await expect(page.locator("#suborden-" + first.id)).toBeFocused();
  await page.getByRole("button", { name: "Confirmar recepción" }).press("Enter");
  await expect(page.getByText("Recepción confirmada", { exact: true })).toBeVisible();

  const recipients = [buyer, alpha.email, beta.email];
  await expect.poll(async () => {
    const messages = await messagesFor(request, recipients);
    return Object.fromEntries(recipients.map((recipient) => [recipient, messages
      .filter(({ To }) => To.some(({ Address }) => Address === recipient)).map(({ Subject }) => Subject).sort()]));
  }, { timeout: 15_000 }).toEqual({
    [buyer]: ["Pago aprobado", "Pedido despachado"],
    [alpha.email]: ["Entrega confirmada", "Pago aprobado"],
    [beta.email]: ["Pago aprobado"],
  });
  const messages = await messagesFor(request, recipients);
  for (const message of messages) {
    const detail = await request.get(`${mailpit}/api/v1/message/${message.ID}`);
    const { Text } = await detail.json() as { Text: string };
    expect(Text).not.toContain("Calle Ficticia");
    expect(Text).not.toContain("360000000000000");
    if (message.Subject === "Pedido despachado") expect(Text).toContain(target);
  }

  const seller = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    const sellerTarget = origin + "/vendedor/pedidos?suborden=" + first.id;
    await seller.goto(sellerTarget);
    await expect(seller.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
    await credentials(seller, alpha.email);
    await expect(seller).toHaveURL(sellerTarget);
    await expect(seller.getByText("Entregada", { exact: true })).toBeVisible();
    await seller.goto("/catalogo");
    await seller.getByRole("button", { name: "Notificaciones, 2 sin leer" }).press("Enter");
    const sellerPanel = seller.getByRole("dialog", { name: "Notificaciones recientes" });
    await expect(sellerPanel.getByRole("link", { name: /Entrega confirmada/ })).toBeVisible();
    await expect(sellerPanel.getByRole("link", { name: /Pago aprobado/ })).toBeVisible();
    await expect(sellerPanel.getByRole("link", { name: /Pedido despachado/ })).toHaveCount(0);
    const readFromBell = seller.waitForResponse((response) =>
      response.url().endsWith("/read") && response.request().method() === "PATCH");
    await sellerPanel.getByRole("link", { name: /Entrega confirmada/ }).click();
    await expect(seller).toHaveURL(sellerTarget);
    await expect(sellerPanel).toBeHidden();
    expect((await readFromBell).ok()).toBe(true);
    await seller.reload();
    await expect(seller.getByRole("button", { name: "Notificaciones, 1 sin leer" })).toBeVisible();
    const persisted = await request.get(inbox, { headers });
    expect(persisted.status()).toBe(200);
    expect((await persisted.json()).unreadCount).toBe(0);
  } finally {
    await seller.close();
  }
});
