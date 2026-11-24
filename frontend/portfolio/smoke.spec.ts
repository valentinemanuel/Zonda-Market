import { expect, test } from "@playwright/test";

test("reutiliza la portada original y conserva el carrito sin API", async ({ page }) => {
  const apiRequests: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.startsWith("/api/")) apiRequests.push(request.url());
  });
  await page.goto("/");
  await expect(page).toHaveURL(/\/catalogo$/);
  await expect(page.getByRole("region", { name: "Explorá Zonda Market" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Explorá por categorías populares" })).toBeVisible();
  await expect(page.getByText("80 publicaciones", { exact: true })).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Ir al contenido" })).toBeFocused();
  const search = page.getByRole("searchbox", { name: "Buscar en la tienda" });
  await search.fill("lampara");
  await search.press("Enter");
  const results = page.getByRole("region", { name: "Resultados" });
  await results.locator('a[href="/publicaciones/demo-lampara-nuevo"]').press("Enter");
  await expect(page.getByRole("heading", { level: 1, name: /Lámpara de mesa/ })).toBeVisible();
  const expand = page.getByRole("button", { name: "Ampliar imagen del producto" });
  await expand.press("Enter");
  const gallery = page.getByRole("dialog", { name: "Imágenes ampliadas del producto" });
  await expect(gallery).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(gallery).toBeHidden();
  await expect(expand).toBeFocused();
  await page.getByLabel("Variante").selectOption({ index: 1 });
  await page.getByRole("button", { name: "Agregar una unidad" }).press("Enter");
  await expect(page.getByLabel("Cantidad seleccionada")).toHaveText("2");
  await page.getByRole("button", { name: "Agregar al carrito", exact: true }).press("Enter");
  await expect(page.getByRole("status").filter({ hasText: "Agregaste 2 unidades al carrito." })).toBeVisible();
  await page.getByRole("link", { name: "Ver carrito", exact: true }).press("Enter");
  await expect(page.getByRole("heading", { name: "Mi carrito" })).toBeVisible();
  const quantity = page.getByRole("spinbutton", { name: /Cantidad.*Lámpara de mesa/ });
  await expect(quantity).toHaveValue("2");
  await page.reload();
  await expect(quantity).toHaveValue("2");
  await page.getByRole("link", { name: /Continuar al checkout/ }).click();
  await expect(page.getByRole("heading", { name: "Disponible en el proyecto completo" })).toBeVisible();
  await page.goBack();
  await page.getByRole("button", { name: /Quitar Lámpara de mesa/ }).press("Enter");
  await expect(page.getByText("Tu carrito está vacío.")).toBeVisible();
  expect(apiRequests).toEqual([]);
});

test("reutiliza la ficha móvil y muestra los estados de error y vacío", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.route("https://images.pexels.com/**", (route) => route.abort());
  await page.goto("/publicaciones/taza");
  await expect(page.getByRole("heading", { level: 1, name: /Taza de cerámica/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "Ampliar imagen del producto" })
    .getByText("No pudimos cargar esta imagen.")).toBeVisible();
  await expect(page.getByRole("region", { name: "Opciones de compra" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);
  await page.goto("/publicaciones/inexistente");
  await expect(page.getByRole("heading", { name: "Publicación no disponible" })).toBeVisible();
  await page.goto("/catalogo?q=producto-inexistente-demo");
  await expect(page.getByText("No encontramos publicaciones con esos filtros.")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(375);
});
