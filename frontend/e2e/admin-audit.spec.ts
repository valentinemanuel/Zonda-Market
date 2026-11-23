import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("busca un reclamo, sigue su devolución y recupera una notificación fallida con teclado", async ({
  page: buyer, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const fixture = await purchaseFixture(buyer, request, origin, "auditoria", async (_request, email) => {
    expect(await sql(`UPDATE zonda_market.user_account SET email_verified = TRUE WHERE email = '${email}' RETURNING email`))
      .toContain(email);
  });
  await fixture.approve();
  const sub = fixture.order.subOrders[0];
  const opened = await request.post(`/api/v1/me/sub-orders/${sub.id}/disputes`, {
    headers: fixture.headers, data: { reason: "Prueba de auditoría", description: "Contacto ficticio agente@example.test" },
  });
  expect(opened.status()).toBe(201);
  const { id: dispute } = await opened.json();

  const email = `admin-e605-${randomUUID()}@example.test`;
  const registration = await request.post("/api/v1/auth/register", {
    headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email, password },
  });
  expect(registration.status()).toBe(201);
  expect(await sql(`UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${email}' RETURNING email`)).toContain(email);
  const headers = { Authorization: "Bearer " + (await registration.json()).accessToken };
  expect((await request.post(`/api/v1/admin/disputes/${dispute}/assign`, { headers })).status()).toBe(200);
  const decision = await request.post(`/api/v1/admin/disputes/${dispute}/resolve`, {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
    data: { outcome: "REEMBOLSO", amount: "5.00", reason: "Motivo privado de resolución", expectedVersion: 1 },
  });
  expect(decision.status()).toBe(200);

  const incident = randomUUID();
  const originalTrace = randomUUID();
  await sql(`INSERT INTO zonda_market.payment_webhook_inbox (id, topic, notification_id, resource_id, request_id,
    trace_id, received_at, next_attempt_at, attempts, finished_at, failure_code)
    VALUES ('${incident}', 'ORDER', '${incident}', 'referencia-ficticia-reservada', '${incident}',
        '${originalTrace}', now(), now(), 11, now(), 'PROVIDER_NOT_CONFIGURED')`);
  const auditPath = "/api/v1/admin/audit-logs";
  const retryPath = `/api/v1/admin/payment-incidents/${incident}/reconcile`;
  expect((await request.get(auditPath, { headers: fixture.headers })).status()).toBe(403);
  expect((await request.post(retryPath, { headers: { ...fixture.headers, "Idempotency-Key": randomUUID() }, data: { reason: "Prueba" } })).status()).toBe(403);
  await expect.poll(async () => {
    const response = await request.get(`${auditPath}?resource=${dispute}`, { headers });
    expect(response.status()).toBe(200);
    return (await response.json()).items.some((event: { result: string }) => event.result === "CONFIRMADA");
  }, { timeout: 15_000 }).toBe(true);

  const admin = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await admin.goto(origin + "/ingresar");
    await credentials(admin, email);
    await expect(admin).toHaveURL(origin + "/catalogo");
    await admin.goto(origin + "/admin/auditoria");
    await admin.getByLabel("ID del recurso").fill(dispute);
    await admin.getByRole("button", { name: "Buscar" }).press("Enter");
    await expect(admin).toHaveURL(origin + "/admin/auditoria?resource=" + dispute);
    const table = admin.getByRole("region", { name: "Eventos de auditoría" });
    await expect(table.getByRole("row", { name: /Reclamo resuelto/ })).toBeVisible();
    await expect(table.getByRole("row", { name: /Estado de devolución Confirmada/ })).toBeVisible();
    await table.focus();
    await table.press("ArrowRight");
    await expect.poll(() => table.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
    expect(await admin.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await admin.screenshot({ path: testInfo.outputPath("auditoria-reclamo-movil.png"), fullPage: true });
    const audit = await request.get(`${auditPath}?resource=${dispute}&size=1`, { headers });
    expect((await audit.json()).hasNext).toBe(true);
    expect(await (await request.get(`${auditPath}?resource=${dispute}`, { headers })).text()).not.toMatch(/agente@example.test|Motivo privado de resolución|local-refund-/);

    await admin.getByRole("link", { name: "Incidencias", exact: true }).click();
    const card = admin.getByRole("listitem").filter({ hasText: incident });
    await expect(card.getByText("Fallida")).toBeVisible();
    const recover = card.getByRole("button", { name: "Recuperar incidencia" });
    await recover.press("Enter");
    const dialog = admin.getByRole("dialog", { name: "Recuperar incidencia" });
    const reason = dialog.getByLabel("Motivo de recuperación");
    await expect(reason).toBeFocused();
    await admin.keyboard.press("Escape");
    await expect(recover).toBeFocused();
    await admin.keyboard.press("Enter");
    const confirm = dialog.getByRole("button", { name: "Confirmar recuperación" });
    await confirm.press("Enter");
    await expect(reason).toBeFocused();
    await reason.fill("Verificar notificación con datos ficticios");
    await admin.screenshot({ path: testInfo.outputPath("recuperacion-incidencia-movil.png"), fullPage: true });
    await confirm.press("Enter");
    await expect(dialog).toHaveCount(0);
    await admin.getByRole("link", { name: "Consultar la traza de recuperación" }).click();
    await expect(admin.getByRole("row", { name: /Recuperación solicitada En cola/ })).toBeVisible();
    const correlated = await request.get(`${auditPath}?traceId=${originalTrace}`, { headers });
    const events = (await correlated.json()).items;
    expect(events.map((event: { action: string }) => event.action)).toContain("INCIDENT_RECONCILE_REQUESTED");
    expect(JSON.stringify(events)).not.toMatch(/referencia-ficticia-reservada|Verificar notificación con datos ficticios/);
  } finally {
    await admin.close();
  }
});
