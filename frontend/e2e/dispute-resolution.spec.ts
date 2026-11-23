import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 8_000 });

test("simula el saldo y concilia una devolución parcial visible para ambas partes", async ({
  page: buyer, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const fixture = await purchaseFixture(buyer, request, origin, "devolucion", async (_request, email) => {
    expect(await sql(`UPDATE zonda_market.user_account SET email_verified = TRUE WHERE email = '${email}' RETURNING email`))
      .toContain(email);
  });
  await fixture.approve();
  const sub = fixture.order.subOrders.find(({ group }) => group.sellerName === fixture.alpha.seller)!;
  const open = await request.post("/api/v1/me/sub-orders/" + sub.id + "/disputes", {
    headers: fixture.headers, data: { reason: "Devolución de prueba", description: "Descripción ficticia" },
  });
  expect(open.status()).toBe(201);
  const { id } = await open.json();

  const adminEmail = "admin-e604-" + randomUUID() + "@example.test";
  const registration = await request.post("/api/v1/auth/register", {
    headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email: adminEmail, password },
  });
  expect(registration.status()).toBe(201);
  expect(await sql(`UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${adminEmail}' RETURNING email`))
    .toContain(adminEmail);
  const headers = { Authorization: "Bearer " + (await registration.json()).accessToken };
  expect((await request.post(`/api/v1/admin/disputes/${id}/assign`, { headers })).status()).toBe(200);

  const admin = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await admin.goto(origin + "/ingresar");
    await credentials(admin, adminEmail);
    await expect(admin).toHaveURL(origin + "/catalogo");
    await admin.goto(origin + "/reclamos/" + id);
    const button = admin.getByRole("button", { name: "Resolver reclamo" });
    await button.press("Enter");
    const dialog = admin.getByRole("dialog", { name: "Resolver reclamo" });
    await expect(dialog.getByLabel("Resultado")).toBeFocused();
    await admin.keyboard.press("Escape");
    await expect(button).toBeFocused();
    await admin.keyboard.press("Enter");
    await expect(dialog.getByText("Disponible para devolver")).toBeVisible();
    await dialog.getByLabel("Resultado").selectOption("REEMBOLSO");
    const amount = dialog.getByLabel("Monto en pesos (obligatorio)");
    await amount.fill("150,00");
    const reason = dialog.getByLabel("Motivo de la resolución (obligatorio)");
    await reason.fill("Acuerdo de devolución parcial");
    const submit = dialog.getByRole("button", { name: "Confirmar resolución" });
    await submit.press("Enter");
    await expect(amount).toBeFocused();
    expect(await amount.evaluate((input: HTMLInputElement) => input.validationMessage)).toContain("supera el saldo");
    await amount.fill("25,00");
    await admin.screenshot({ path: testInfo.outputPath("resolucion-saldo-movil.png"), fullPage: true });
    await submit.press("Enter");
    await expect(admin.getByText("Devolución confirmada", { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(admin.getByText(/Referencia de prueba local-refund-/)).toBeVisible();
    expect(await admin.evaluate(() => document.documentElement.scrollWidth)).toBe(390);

    await buyer.bringToFront();
    await buyer.goto(origin + "/reclamos/" + id);
    await expect(buyer.getByText("Devolución confirmada", { exact: true })).toBeVisible();
    await expect(buyer.getByText("Acuerdo de devolución parcial")).toBeVisible();
    await buyer.screenshot({ path: testInfo.outputPath("devolucion-confirmada-movil.png"), fullPage: true });
    const metrics = await request.get("/api/v1/seller/metrics", { headers: fixture.alpha.headers });
    expect(metrics.status()).toBe(200);
    expect((await metrics.json()).refunds).toBe("25.00");
    const seller = await request.get("/api/v1/disputes/" + id, { headers: fixture.alpha.headers });
    expect(seller.status()).toBe(200);
    expect((await seller.json()).resolution.status).toBe("CONFIRMADA");
  } finally {
    await admin.close();
  }
});
