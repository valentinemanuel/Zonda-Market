import { expect, test } from "@playwright/test";

test("navega y conserva el carrito sin API", async ({ page }) => {
  const apiRequests: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.startsWith("/api/")) apiRequests.push(request.url());
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Aplicar filtros" })).toHaveCSS("background-color", "rgb(188, 61, 8)");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Saltar al contenido" })).toBeFocused();
  const search = page.getByRole("searchbox", { name: "Buscar productos" });
  await search.fill("lampara");
  await search.press("Enter");
  await expect(page.getByText("1 producto", { exact: true })).toBeFocused();
  await page.getByRole("link", { name: /Lámpara de mesa/ }).press("Enter");
  await expect(page.getByRole("heading", { name: "Lámpara de mesa" })).toBeVisible();
  await page.getByLabel("Altura").selectOption("35 cm");
  await page.getByRole("button", { name: "Agregar al carrito" }).press("Enter");
  await page.getByRole("link", { name: "Ver carrito" }).press("Enter");
  const quantity = page.getByRole("combobox", { name: "Cantidad de Lámpara de mesa, 35 cm" });
  await quantity.selectOption("2");
  await page.reload();
  await expect(quantity).toHaveValue("2");
  await expect(page.getByText("Subtotal")).toContainText("96.000,00");
  await page.getByRole("button", { name: /Quitar Lámpara de mesa/ }).press("Enter");
  await expect(page.getByText("Tu carrito está vacío.")).toBeVisible();
  expect(apiRequests).toEqual([]);
});

test("abre enlaces directos y cubre el vacío y la foto caída en móvil", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.route("https://images.pexels.com/**", (route) => route.abort());
  await page.goto("/publicaciones/taza");
  await expect(page.getByRole("heading", { name: "Taza de cerámica" })).toBeVisible();
  await expect(page.getByText("Imagen no disponible")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);
  await page.goto("/publicaciones/inexistente");
  await expect(page.getByRole("heading", { name: "No encontramos esa publicación" })).toBeVisible();
  await page.getByRole("link", { name: "Volver al catálogo" }).click();
  await page.getByLabel("Categoría").selectOption("Tecnología");
  await page.getByRole("searchbox").fill("maceta");
  await page.getByRole("button", { name: "Aplicar filtros" }).click();
  await expect(page.getByText(/No encontramos productos/)).toBeVisible();
  await page.getByRole("link", { name: "Limpiar filtros" }).click();
  await expect(page.getByText("12 productos", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);
});
