import { expect, test } from "@playwright/test";

const rootId = "e0020000-0000-4000-8000-000000000001";
const missingId = "e0020000-0000-4000-8000-000000000099";

test("muestra la categoría y los problemas REST con su referencia", async ({ page, request }, testInfo) => {
  // Keep loading visible until it is asserted so that a fast local response cannot hide it.
  let releaseRequest!: () => void;
  const loadingGate = new Promise<void>((resolve) => { releaseRequest = resolve; });
  await page.route(`**/api/v1/categories/${rootId}`, async (route) => {
    await loadingGate;
    await route.continue();
  }, { times: 1 });

  await page.goto("/estado");
  await page.getByRole("link", { name: "Explorar categoría Tecnología" }).click();
  try {
    await expect(page.getByRole("status")).toContainText("Cargando categoría");
  } finally {
    releaseRequest();
  }
  await expect(page.getByRole("heading", { name: "Tecnología" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Zonda Market" }).locator("img")).toBeVisible();
  expect((await request.get("/brand/zonda-isotipo.svg")).headers()["content-type"]).toContain("image/svg+xml");
  await page.screenshot({ path: testInfo.outputPath("categoria.png"), fullPage: true });

  for (const scenario of [
    { id: "sin-uuid", status: 400, code: "INVALID_REQUEST", heading: "Enlace de categoría inválido" },
    { id: missingId, status: 404, code: "CATEGORY_NOT_FOUND", heading: "Categoría no disponible" },
  ]) {
    const responsePromise = page.waitForResponse(`**/api/v1/categories/${scenario.id}`);
    await page.goto(`/categorias/${scenario.id}`);
    const response = await responsePromise;
    expect(response.status()).toBe(scenario.status);
    expect(response.headers()["content-type"]).toContain("application/problem+json");
    const problem = await response.json();
    expect(problem.code).toBe(scenario.code);
    expect(problem.traceId).toBe(response.headers()["x-trace-id"]);
    await expect(page.getByRole("alert").getByRole("heading")).toHaveText(scenario.heading);
    await expect(page.getByRole("alert")).toContainText(problem.traceId);
    await page.setViewportSize({ width: 360, height: 800 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`categoria-${scenario.status}.png`), fullPage: true });
    await page.getByRole("link", { name: "Volver al estado del sistema" }).focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: "Estado del sistema" })).toBeVisible();
  }

  await page.goto("/swagger-ui/index.html");
  await expect(page.getByText("/api/v1/categories/{id}", { exact: true })).toBeVisible();
});
