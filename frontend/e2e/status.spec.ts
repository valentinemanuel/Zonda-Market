import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { compose, sql } from "./compose-fixture";

const readinessPath = "/actuator/health/readiness";

test("diagnóstico, caída de PostgreSQL, recuperación y persistencia", async ({ page, request }, testInfo) => {
  const statusResponse = await request.get("/api/v1/status");
  expect(statusResponse.status()).toBe(200);
  const status = await statusResponse.json();
  expect(status).toEqual({ status: "UP", version: expect.any(String) });
  const initialReadiness = await request.get(readinessPath);
  expect(initialReadiness.status()).toBe(200);
  expect(await initialReadiness.json()).toEqual({ status: "UP" });

  // The JVM startup log names the applied option. /proc/1 reports the live JVM RSS.
  expect(await compose("logs", "backend")).toContain("Picked up JAVA_TOOL_OPTIONS: -Xmx384m");
  const memory = await compose("exec", "-T", "backend", "sh", "-c", "grep '^VmRSS:' /proc/1/status");
  expect(memory).toMatch(/^VmRSS:\s+[1-9]\d*\s+kB$/);
  console.log(`JVM con -Xmx384m. ${memory}`);

  await page.goto("/estado");
  await expect(page.getByRole("heading", { name: "Estado del sistema" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText(/Sistema operativo/);
  await expect(page.getByText(status.version, { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("estado-operativo.png"), fullPage: true });

  const table = `zonda_market.smoke_${randomUUID().replaceAll("-", "")}`;
  await sql(`CREATE TABLE ${table} (marker text PRIMARY KEY); INSERT INTO ${table} VALUES ('e0-01');`);
  try {
    await compose("stop", "postgres");
    await expect.poll(async () => (await request.get(readinessPath)).status()).toBe(503);
    const failedReadiness = await request.get(readinessPath);
    expect(failedReadiness.status()).toBe(503);
    expect(await failedReadiness.json()).toEqual({ status: "DOWN" });
    expect((await request.get("/api/v1/status")).status()).toBe(200);

    const refresh = page.getByRole("button", { name: "Actualizar" });
    await refresh.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("alert")).toContainText("La base de datos no está disponible");
    await expect(page.getByRole("button", { name: "Reintentar" })).toBeEnabled();
    await page.screenshot({ path: testInfo.outputPath("estado-sin-postgres.png"), fullPage: true });
    console.log("Readiness con PostgreSQL detenido: HTTP 503 y cuerpo mínimo.");
  } finally {
    // Replacing PostgreSQL proves the marker lives in the named volume.
    await compose("up", "-d", "--no-deps", "--force-recreate", "postgres");
    await expect.poll(async () => (await request.get(readinessPath)).status(), { timeout: 45_000 }).toBe(200);
    try {
      expect(await sql(`SELECT marker FROM ${table};`)).toBe("e0-01");
    } finally {
      await sql(`DROP TABLE IF EXISTS ${table};`);
    }
  }

  await page.getByRole("button", { name: "Reintentar" }).click();
  await expect(page.getByRole("status")).toHaveText(/Sistema operativo/);
  await expect(page.getByRole("alert")).toHaveCount(0);
  console.log("Readiness tras recrear PostgreSQL: HTTP 200. El volumen conserva el dato de prueba.");
});
