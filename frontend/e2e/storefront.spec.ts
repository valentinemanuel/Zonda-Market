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
  const categories = navigation.getByRole("button", { name: "Categorías", exact: true });
  await categories.press("Enter");
  const allCategories = navigation.getByRole("link", { name: "Todas las categorías" });
  await expect(allCategories).toBeVisible();
  await allCategories.press("Escape");
  await expect(allCategories).toBeHidden();
  await expect(categories).toBeFocused();

  await page.getByRole("button", { name: "Siguiente destacado" }).press("Enter");
  await expect(page.getByRole("button", { name: "Hogar y decoración", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(page.locator(".store-hero-slide[data-state=entering]")).toHaveCSS("animation-name", "none");
  await expect(page.getByRole("button", { name: "Pausar destacados" })).toHaveCount(0);

  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
});

test("la búsqueda de la cabecera conserva el término y recupera un error de catálogo", async ({ page }) => {
  await page.goto("/catalogo");
  await expect(page.getByRole("heading", { name: "Filtros", exact: true })).toHaveCount(0);
  const term = "sin coincidencias ui01";
  const request = (url: URL) => url.pathname === "/api/v1/listings" && url.searchParams.get("q") === term;
  await page.route(request, (route) => route.fulfill({ status: 503 }), { times: 1 });

  const search = page.getByRole("searchbox", { name: "Buscar en la tienda" });
  await search.fill(term);
  await search.press("Enter");
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === term);
  await expect(page.getByRole("heading", { name: "Filtros", exact: true })).toBeVisible();
  await expect(page.getByRole("alert")).toContainText("No pudimos cargar el catálogo");
  await page.getByRole("button", { name: "Reintentar catálogo" }).press("Enter");
  await expect(page.getByText("No encontramos publicaciones con esos filtros.")).toBeVisible();
  await expect(search).toHaveValue(term);
});

test("categorías cierra al pulsar fuera y los filtros aparecen al elegir una categoría", async ({ page }) => {
  await page.goto("/catalogo");
  const navigation = page.getByRole("navigation", { name: "Secciones de la tienda" });
  const categories = navigation.getByRole("button", { name: "Categorías", exact: true });
  const panel = page.getByRole("dialog", { name: "Categorías del catálogo" });
  await categories.click();
  await expect(panel).toBeVisible();
  const search = page.getByRole("searchbox", { name: "Buscar en la tienda" });
  await search.click();
  await expect(panel).toBeHidden();
  await expect(search).toBeFocused();
  await categories.click();
  await panel.getByRole("link", { name: "Tecnología", exact: true }).click();
  await expect(panel).toBeHidden();
  await expect(page.getByRole("heading", { name: "Filtros", exact: true })).toBeVisible();
});

test("la portada rota, permite pausar y cambia de dirección al retroceder", async ({ page }, testInfo) => {
  await page.clock.install();
  await page.goto("/catalogo");
  const carousel = page.getByLabel("Explorá Zonda Market", { exact: true });
  const technology = carousel.getByRole("button", { name: "Tecnología", exact: true });
  const home = carousel.getByRole("button", { name: "Hogar y decoración", exact: true });
  await expect(technology).toHaveAttribute("aria-pressed", "true");
  await expect(carousel.getByRole("button", { name: "Pausar destacados" })).toBeVisible();
  await page.clock.fastForward(6100);
  await expect(home).toHaveAttribute("aria-pressed", "true");
  await expect(carousel.locator("[data-direction='1'] [data-state=entering]")).toHaveCSS("animation-name", "store-slide-in");
  await carousel.getByRole("button", { name: "Pausar destacados" }).press("Enter");
  await page.clock.fastForward(6100);
  await expect(home).toHaveAttribute("aria-pressed", "true");
  await carousel.getByRole("button", { name: "Destacado anterior" }).press("Enter");
  await expect(technology).toHaveAttribute("aria-pressed", "true");
  await expect(carousel.locator("[data-direction='-1'] [data-state=entering]")).toHaveCSS("animation-name", "store-slide-in");
  await expect(carousel.getByRole("link", { name: "Ver categoría" })).toHaveCount(1);
  const shortcuts = page.getByRole("region", { name: "Accesos a la tienda" });
  const hero = (await carousel.boundingBox())!;
  const cards = (await shortcuts.boundingBox())!;
  expect(hero.y + hero.height - cards.y).toBeGreaterThan(40);
  await page.screenshot({ path: testInfo.outputPath("portada-carrusel-y-accesos.png"), animations: "disabled" });
});
