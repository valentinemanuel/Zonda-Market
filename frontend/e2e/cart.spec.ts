import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, publish } from "./cart-fixtures";
import { productPhoto } from "./product-photo";

const cart = "/api/v1/cart";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 12_000 });

test("persiste dos vendedores, maneja un conflicto y permite quitar una línea archivada", async ({
  page, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  await page.goto("/catalogo");
  const photo = await productPhoto(page, "#2563eb");
  const first = await publish(request, origin, photo, "Alfa", 100.25);
  const second = await publish(request, origin, photo, "Beta", 250.90);
  const email = `comprador-carrito-${randomUUID().slice(0, 8)}@example.test`;
  const firstPath = "/publicaciones/" + first.slug;

  await page.goto(firstPath);
  await page.getByRole("link", { name: "Ingresá para agregar al carrito" }).press("Enter");
  await page.getByRole("link", { name: "Crear una cuenta" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Crear cuenta" })).toBeVisible();
  await credentials(page, email);
  await expect(page).toHaveURL(origin + firstPath);
  const add = page.getByRole("button", { name: "Agregar al carrito" });
  await add.press("Enter");
  await expect(page.getByRole("link", { name: "Carrito, 1 unidad" })).toBeVisible();

  await page.goto("/publicaciones/" + second.slug);
  await add.press("Enter");
  await page.getByRole("link", { name: "Carrito, 2 unidades" }).press("Enter");
  await expect(page.getByRole("heading", { name: first.seller })).toBeVisible();
  await expect(page.getByRole("heading", { name: second.seller })).toBeVisible();
  await expect(page.getByText("$ 351,15", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("$ 351,15", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("carrito-dos-vendedores-movil.png"), fullPage: true });

  const device = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await device.goto(origin + "/carrito");
    await device.getByRole("link", { name: "Iniciar sesión" }).press("Enter");
    await expect(device.getByRole("heading", { name: "Iniciar sesión" })).toBeVisible();
    await credentials(device, email);
    await expect(device).toHaveURL(origin + "/carrito");
    await expect(device.getByText("$ 351,15", { exact: true })).toBeVisible();

    const quantity = page.getByRole("spinbutton", { name: new RegExp(first.title) });
    const deviceQuantity = device.getByRole("spinbutton", { name: new RegExp(first.title) });
    await deviceQuantity.fill("3");
    const itemPath = cart + "/items/" + first.skuId;
    let release!: () => void;
    let sent!: () => void;
    const blocked = new Promise<void>((resolve) => { release = resolve; });
    const intercepted = new Promise<void>((resolve) => { sent = resolve; });
    await device.route("**" + itemPath, async (route) => {
      sent();
      await blocked;
      await route.continue();
    }, { times: 1 });
    const conflict = device.waitForResponse((response) => response.url().endsWith(itemPath));
    await deviceQuantity.press("Enter");
    await intercepted;
    try {
      await quantity.fill("2");
      await quantity.press("Tab");
      await expect(page.getByRole("button", { name: "Guardar" }).first()).toBeFocused();
      await page.keyboard.press("Enter");
      await expect(page.getByText("Cantidad actualizada.")).toBeVisible();
      await expect(page.getByText("$ 451,40", { exact: true })).toBeVisible();
    } finally {
      release();
    }
    expect((await conflict).status()).toBe(409);
    await expect(device.getByRole("alert")).toContainText("El carrito cambió en otra sesión.");
    await expect(deviceQuantity).toHaveValue("2");
    await device.screenshot({ path: testInfo.outputPath("carrito-conflicto-movil.png"), fullPage: true });
  } finally {
    await device.close();
  }

  expect((await request.patch(first.privatePath + "/skus/" + first.skuId, {
    headers: first.headers, data: { expectedVersion: 0, price: 120.25 },
  })).status()).toBe(200);
  expect((await request.post(second.privatePath + "/archive", { headers: second.headers })).status()).toBe(200);
  await page.reload();
  await expect(page.getByText("La publicación ya no está disponible.")).toBeVisible();
  await expect(page.getByText(/Cambió el precio por unidad.*120,25/)).toBeVisible();
  await expect(page.getByText("$ 240,50", { exact: true }).last()).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("carrito-precio-y-archivado-movil.png"), fullPage: true });
  await page.getByRole("button", { name: new RegExp("Quitar " + second.title) }).press("Enter");
  await expect(page.getByText("Producto quitado del carrito.")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Mi carrito" })).toBeFocused();
  await expect(page.getByRole("heading", { name: "Productos no disponibles" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Carrito, 2 unidades" })).toBeVisible();
});

test("anuncia carga y permite reintentar hasta mostrar el carrito vacío", async ({ page }, testInfo) => {
  await page.goto("/registro");
  await credentials(page, `carrito-vacio-${randomUUID().slice(0, 8)}@example.test`);
  await expect(page).toHaveURL(/\/cuenta$/);

  let release!: () => void;
  const paused = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**" + cart, async (route) => {
    await paused;
    await route.fulfill({ status: 503 });
  });
  await page.goto("/carrito");
  try {
    await expect(page.getByText("Cargando carrito…")).toBeVisible();
  } finally {
    release();
  }
  await expect(page.getByRole("alert")).toHaveText("No pudimos cargar el carrito.");
  await page.screenshot({ path: testInfo.outputPath("carrito-error-movil.png"), fullPage: true });
  await page.unroute("**" + cart);
  await page.getByRole("button", { name: "Reintentar" }).press("Enter");
  await expect(page.getByText("Tu carrito está vacío.")).toBeVisible();
});
