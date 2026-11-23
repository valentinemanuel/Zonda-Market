import { expect, test } from "@playwright/test";

test("la tienda móvil permite navegar con teclado y respeta el movimiento reducido", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.clock.install();
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
  await expect(page.locator(".store-hero-slide[data-state=entering] .store-hero-title")).toHaveCSS("animation-name", "none");
  await page.clock.fastForward(4500);
  await expect(page.getByRole("button", { name: "Calzado", exact: true })).toHaveAttribute("aria-pressed", "true");

  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  const hero = (await page.getByLabel("Explorá Zonda Market", { exact: true }).boundingBox())!;
  expect(hero.x).toBe(0);
  expect(hero.width).toBe(390);
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

test("la portada ocupa todo el ancho y sigue rotando al usar el puntero y las flechas", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1920, height: 900 });
  await page.clock.install();
  await page.goto("/catalogo", { waitUntil: "domcontentloaded" });
  const carousel = page.getByLabel("Explorá Zonda Market", { exact: true });
  const technology = carousel.getByRole("button", { name: "Tecnología", exact: true });
  const home = carousel.getByRole("button", { name: "Hogar y decoración", exact: true });
  const next = carousel.getByRole("button", { name: "Siguiente destacado" });
  const previous = carousel.getByRole("button", { name: "Destacado anterior" });
  await expect(technology).toHaveAttribute("aria-pressed", "true");
  await expect(carousel.getByRole("button", { name: /Pausar|Reproducir/ })).toHaveCount(0);
  await expect(carousel.locator(".store-hero-progress")).toHaveCSS("animation-duration", "4.5s");
  await carousel.hover({ position: { x: 20, y: 20 } });
  await page.clock.fastForward(4500);
  await expect(home).toHaveAttribute("aria-pressed", "true");
  const incoming = carousel.locator("[data-direction='1'] [data-state=entering]");
  await expect(incoming.locator(".store-hero-actions")).toHaveCSS("animation-delay", "0.26s");
  await expect(incoming.locator(".store-hero-artwork")).toHaveCSS("animation-duration", "0.65s");
  await previous.press("Enter");
  await expect(technology).toHaveAttribute("aria-pressed", "true");
  await expect(carousel.locator("[data-direction='-1'] [data-state=entering] .store-hero-title")).toHaveCSS("animation-name", "store-text-in");
  await expect(carousel.getByRole("link", { name: "Ver categoría" })).toHaveCount(1);
  await carousel.getByRole("link", { name: "Ver categoría" }).focus();
  await page.clock.fastForward(4500);
  await expect(home).toHaveAttribute("aria-pressed", "true");
  await expect(next).toBeFocused();
  const shortcuts = page.getByRole("region", { name: "Accesos a la tienda" });
  expect((await carousel.boundingBox())!.width).toBe(await page.evaluate(() => document.body.clientWidth));
  const gutter = await page.addStyleTag({ content: "html { scrollbar-gutter: stable; }" });
  const hero = (await carousel.boundingBox())!;
  const cards = (await shortcuts.boundingBox())!;
  const content = (await incoming.boundingBox())!;
  const { availableWidth, overflows } = await page.evaluate(() => ({
    availableWidth: document.body.clientWidth,
    overflows: document.documentElement.scrollWidth > document.documentElement.clientWidth,
  }));
  expect(hero.x).toBe(0);
  expect(hero.width).toBe(availableWidth);
  expect(content.width).toBe(1280);
  expect(content.x).toBeCloseTo((availableWidth - content.width) / 2, 0);
  expect(overflows).toBe(false);
  expect(hero.y + hero.height - cards.y).toBeGreaterThan(40);
  await gutter.evaluate((style) => style.parentNode?.removeChild(style));
  await page.screenshot({ path: testInfo.outputPath("portada-carrusel-y-accesos.png"), animations: "disabled" });
});

test("salta directamente a una página y conserva la búsqueda al avanzar", async ({ page }) => {
  await page.route((url) => url.pathname === "/api/v1/listings", (route) => route.fulfill({ json: {
    content: [], totalPages: 25, totalElements: 300, page: Number(new URL(route.request().url()).searchParams.get("page")) || 0,
    facets: [], sellers: [],
  } }));
  await page.goto("/catalogo?q=auriculares");
  const pagination = page.getByRole("navigation", { name: "Páginas del catálogo" });
  await pagination.getByRole("link", { name: "Página 4", exact: true }).press("Enter");
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === "auriculares" && url.searchParams.get("page") === "3");
  await expect(pagination.getByRole("link", { name: "Página 4", exact: true })).toHaveAttribute("aria-current", "page");
  await pagination.getByRole("link", { name: "Siguiente" }).press("Enter");
  await expect(pagination.getByRole("link", { name: "Página 5", exact: true })).toHaveAttribute("aria-current", "page");
  const jump = pagination.getByRole("spinbutton", { name: "Ir a página" });
  await jump.fill("17");
  await jump.press("Enter");
  await expect(pagination.getByRole("link", { name: "Página 17", exact: true })).toHaveAttribute("aria-current", "page");
});
