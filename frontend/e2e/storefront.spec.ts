import { expect, test } from "@playwright/test";

test("la tienda móvil permite navegar con teclado y respeta el movimiento reducido", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/catalogo");
  await expect(page.getByRole("heading", { name: "Catálogo", exact: true })).toBeVisible();

  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Ir al contenido" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();

  const navigation = page.getByRole("navigation", { name: "Secciones de la tienda" });
  const categories = navigation.locator("summary");
  await categories.press("Enter");
  const allCategories = navigation.getByRole("link", { name: "Todas las categorías" });
  await expect(allCategories).toBeVisible();
  await allCategories.press("Escape");
  await expect(allCategories).toBeHidden();
  await expect(categories).toBeFocused();

  await page.getByRole("button", { name: "Siguiente destacado" }).press("Enter");
  await expect(page.getByRole("button", { name: "Hogar y decoración", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".store-hero-art")).toHaveCSS("animation-name", "none");

  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
});

test("la búsqueda de la cabecera conserva el término y recupera un error de catálogo", async ({ page }) => {
  await page.goto("/catalogo");
  const term = "sin coincidencias ui01";
  const request = (url: URL) => url.pathname === "/api/v1/listings" && url.searchParams.get("q") === term;
  await page.route(request, (route) => route.fulfill({ status: 503 }), { times: 1 });

  const search = page.getByRole("searchbox", { name: "Buscar en la tienda" });
  await search.fill(term);
  await search.press("Enter");
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === term);
  await expect(page.getByRole("alert")).toContainText("No pudimos cargar el catálogo");
  await page.getByRole("button", { name: "Reintentar catálogo" }).press("Enter");
  await expect(page.getByText("No encontramos publicaciones con esos filtros.")).toBeVisible();
  await expect(search).toHaveValue(term);
});
