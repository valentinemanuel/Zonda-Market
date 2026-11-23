import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import type { Operations } from "../src/features/admin/operations-api";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("concilia una compra de dos vendedores y descarga el reporte global con teclado", async ({
  page: buyer, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const reportPath = "/api/v1/admin/dashboard";
  const exportPath = "/api/v1/admin/reports/sales.csv";
  const email = `admin-e606-${randomUUID()}@example.test`;
  const registration = await request.post("/api/v1/auth/register", {
    headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email, password },
  });
  expect(registration.status()).toBe(201);
  expect(await sql(`UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${email}' RETURNING email`)).toContain(email);
  const headers = { Authorization: "Bearer " + (await registration.json()).accessToken };
  const baseline = await request.get(reportPath, { headers });
  expect(baseline.status()).toBe(200);
  const before: Operations = await baseline.json();

  const fixture = await purchaseFixture(buyer, request, origin, "operacion", async (_request, account) => {
    expect(await sql(`UPDATE zonda_market.user_account SET email_verified = TRUE WHERE email = '${account}' RETURNING email`))
      .toContain(account);
  });
  await fixture.approve();
  await fixture.approve();
  const sub = fixture.order.subOrders.find(({ group }) => group.sellerName === fixture.alpha.seller)!;
  const sellerId = sub.group.sellerId;
  await sql(`UPDATE zonda_market.seller_profile SET display_name = '=1+1' WHERE id = '${sellerId}'`);
  const opened = await request.post(`/api/v1/me/sub-orders/${sub.id}/disputes`, {
    headers: fixture.headers, data: { reason: "Devolución de prueba", description: "Compra ficticia de dos vendedores" },
  });
  expect(opened.status()).toBe(201);
  const { id: dispute } = await opened.json();
  expect((await request.post(`/api/v1/admin/disputes/${dispute}/assign`, { headers })).status()).toBe(200);
  const resolution = await request.post(`/api/v1/admin/disputes/${dispute}/resolve`, {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
    data: { outcome: "REEMBOLSO", amount: "25.00", reason: "Acuerdo ficticio", expectedVersion: 1 },
  });
  expect(resolution.status()).toBe(200);
  await expect.poll(async () => (await (await request.get("/api/v1/disputes/" + dispute, { headers })).json()).resolution?.status,
    { timeout: 15_000 }).toBe("CONFIRMADA");

  expect((await request.get(reportPath, { headers: fixture.headers })).status()).toBe(403);
  expect((await request.get(exportPath, { headers: fixture.headers })).status()).toBe(403);

  const response = await request.get(reportPath, { headers });
  expect(response.status()).toBe(200);
  const report: Operations = await response.json();
  expect(Number(report.gmv) - Number(before.gmv)).toBeCloseTo(250, 2);
  expect(Number(report.refunds) - Number(before.refunds)).toBeCloseTo(25, 2);
  expect(report.paidOrders - before.paidOrders).toBe(1);
  expect(report.disputes - before.disputes).toBe(1);
  expect(report.openCases).toBe(before.openCases);
  expect(report.sellers.find((seller) => seller.id === sellerId)).toMatchObject({ name: "=1+1", gmv: "100.00", refunds: "25.00" });

  const admin = await browser.newPage({ viewport: { width: 390, height: 844 }, locale: "es-AR" });
  try {
    await admin.goto(origin + "/ingresar");
    await credentials(admin, email);
    await expect(admin).toHaveURL(origin + "/catalogo");
    await admin.goto(origin + "/admin/publicaciones");
    let release!: () => void;
    const hold = new Promise<void>((resolve) => { release = resolve; });
    await admin.route(/\/api\/v1\/admin\/dashboard(?:\?|$)/, async (route) => {
      await hold;
      await route.fulfill({ status: 503 });
    }, { times: 1 });
    try {
      await admin.getByRole("link", { name: "Operación global" }).press("Enter");
      await expect(admin.getByText("Cargando métricas…")).toBeVisible();
    } finally {
      release();
    }
    await expect(admin.getByRole("alert")).toHaveText("No pudimos cargar las métricas.");
    await admin.screenshot({ path: testInfo.outputPath("operacion-error-movil.png"), fullPage: true });
    await admin.getByRole("button", { name: "Reintentar" }).press("Enter");
    await expect(admin.getByRole("heading", { name: "Resumen del período" })).toBeVisible();
    await admin.getByText("Ver métricas por día", { exact: true }).press("Enter");
    await admin.keyboard.press("Tab");
    const daily = admin.getByRole("region", { name: "Métricas diarias" });
    await expect(daily).toBeFocused();
    await daily.press("ArrowRight");
    await expect.poll(() => daily.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
    expect(await admin.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await expect(admin.getByRole("rowheader", { name: "=1+1" })).toBeVisible();
    await daily.press("ArrowLeft");
    await expect.poll(() => daily.evaluate((element) => element.scrollLeft)).toBe(0);
    await admin.screenshot({ path: testInfo.outputPath("operacion-resumen-movil.png"), fullPage: true });

    const download = admin.waitForEvent("download");
    await admin.getByRole("button", { name: "Descargar CSV" }).press("Enter");
    const file = await download;
    expect(file.suggestedFilename()).toBe(`ventas-${report.from}-${report.to}.csv`);
    await file.saveAs(testInfo.outputPath("ventas.csv"));
    const csv = await readFile(testInfo.outputPath("ventas.csv"), "utf8");
    expect(csv.split("\r\n")[1].split(",").slice(8, 14)).toEqual([
      report.gmv, report.refunds, report.platformCommission, String(report.paidOrders), String(report.disputes), String(report.openCases),
    ]);
    expect(csv).toContain(`,'=1+1,100.00,25.00,`);
    expect(csv).not.toContain(",=1+1,");
    expect(csv).not.toContain(email);
    await expect(admin.getByText("Descarga iniciada.")).toBeVisible();

    const from = admin.getByLabel("Desde");
    const to = admin.getByLabel("Hasta");
    const apply = admin.getByRole("button", { name: "Aplicar período" });
    await from.fill("2000-01-02");
    await to.fill("2000-01-01");
    await apply.press("Enter");
    await expect(to).toBeFocused();
    await expect(admin.getByRole("alert")).toHaveText("La fecha Hasta debe ser igual o posterior a Desde.");
    await from.fill("2000-01-01");
    await apply.press("Enter");
    await expect(admin.getByText("No hay actividad en este período.")).toBeVisible();
  } finally {
    await admin.close();
  }
});
