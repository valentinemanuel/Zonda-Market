import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";

test.use({
  baseURL: process.env.ZONDA_SMOKE_URL ?? "http://localhost:3000",
  viewport: { width: 390, height: 844 },
});

test("crea y reabre un borrador con dos variantes y se recupera de errores en móvil", async ({ page, request }, testInfo) => {
  const suffix = randomUUID().slice(0, 8);
  const email = "catalogo-" + suffix + "@example.test";
  await page.goto("/registro");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill("contraseña-ficticia-e201");
  await page.getByLabel("Contraseña").press("Enter");
  await page.getByRole("button", { name: "Enviar enlace de verificación" }).press("Enter");
  await page.goto((await linkFromMail(request, email, "/verificar-correo")).href);
  await page.getByRole("button", { name: "Confirmar correo" }).press("Enter");
  await page.getByRole("link", { name: "Ir a Mi cuenta" }).press("Enter");

  await page.getByLabel("Tipo de vendedor").selectOption("PERSONAL");
  await page.getByLabel("Nombre público").fill("Local ficticio");
  await page.getByLabel("Identificador público").fill("local-" + suffix);
  await page.getByRole("button", { name: "Activar perfil de vendedor" }).press("Enter");

  let releaseLoad!: () => void;
  const loading = new Promise<void>((resolve) => { releaseLoad = resolve; });
  await page.route("**/api/v1/seller/listings", async (route) => {
    await loading;
    await route.fulfill({ status: 503 });
  }, { times: 1 });
  await page.getByRole("link", { name: "Mis publicaciones" }).press("Enter");
  try {
    await expect(page.getByRole("status")).toHaveText("Cargando publicaciones…");
  } finally {
    releaseLoad();
  }
  await expect(page.getByRole("alert")).toHaveText("No pudimos cargar las publicaciones o sus categorías.");
  await page.getByRole("button", { name: "Reintentar" }).press("Enter");
  await expect(page.getByText("Todavía no creaste publicaciones.")).toBeVisible();

  await page.getByRole("link", { name: "Crear publicación" }).press("Enter");
  await page.getByLabel("Categoría").selectOption({ label: "Indumentaria" });
  await page.getByLabel("Título").press("Tab");
  await expect(page.getByLabel("Categoría")).toBeFocused();
  await page.getByLabel("Título").fill("Remera de prueba");
  await page.getByLabel("Descripción").fill("Una remera ficticia de algodón.");
  await page.getByRole("button", { name: "Agregar Material" }).press("Enter");
  await expect(page.getByLabel("Valor 1")).toBeFocused();
  await page.getByLabel("Valor 1").fill("algodón");
  await page.getByRole("button", { name: "Agregar característica", exact: true }).press("Enter");
  await page.getByLabel("Característica 2", { exact: true }).fill("Material");
  await page.getByLabel("Valor 2").fill("Lino");
  await page.getByRole("button", { name: "Crear borrador" }).press("Enter");
  await expect(page.getByRole("alert")).toHaveText("Cada característica debe tener un nombre distinto.");
  await page.getByRole("button", { name: "Quitar característica 2" }).press("Enter");

  let releaseSave!: () => void;
  const saving = new Promise<void>((resolve) => { releaseSave = resolve; });
  await page.route("**/api/v1/seller/listings", async (route) => {
    await saving;
    await route.continue();
  }, { times: 1 });
  const create = page.getByRole("button", { name: "Crear borrador" });
  const created = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/seller/listings") && response.request().method() === "POST");
  await create.press("Enter");
  try {
    await expect(page.getByRole("status")).toHaveText("Guardando…");
    await expect(create).toBeDisabled();
  } finally {
    releaseSave();
  }

  const sheetTab = page.getByRole("tab", { name: "Producto" });
  const variantsTab = page.getByRole("tab", { name: "Opciones de venta" });
  await expect(page.getByRole("heading", { name: "Remera de prueba" })).toBeVisible();
  await sheetTab.press("ArrowRight");
  await expect(variantsTab).toBeFocused();
  await expect(variantsTab).toHaveAttribute("aria-selected", "true");

  async function variant(code: string, color: string, size: string) {
    await page.getByLabel("Código interno (SKU)").fill(code);
    await page.getByLabel("Color", { exact: true }).selectOption(color);
    await page.getByLabel("Talle", { exact: true }).selectOption(size);
    await page.getByLabel("Precio (ARS)").fill("12490,50");
    await page.getByLabel("Stock inicial").fill("7");
    await page.getByRole("button", { name: "Agregar opción" }).press("Enter");
  }

  const firstCode = "negra-" + suffix;
  const secondCode = "azul-" + suffix;
  await variant(firstCode, "Negro", "M");
  const firstRow = page.getByRole("row", { name: new RegExp(firstCode, "i") });
  await expect(firstRow).toContainText("Color Negro · Talle M");

  await variant(secondCode, "Negro", "M");
  await expect(page.getByRole("alert")).toHaveText("Esa combinación de atributos ya existe en esta publicación.");
  await expect(page.getByLabel("Código interno (SKU)")).toHaveValue(secondCode);
  await page.screenshot({ path: testInfo.outputPath("combinacion-duplicada-movil.png"), fullPage: true });
  await page.getByLabel("Color", { exact: true }).selectOption("Azul");
  await page.getByLabel("Talle", { exact: true }).selectOption("L");
  await page.getByRole("button", { name: "Agregar opción" }).press("Enter");
  await expect(page.getByRole("row", { name: new RegExp(secondCode, "i") })).toContainText("Color Azul · Talle L");

  const createdResponse = await created;
  const token = await createdResponse.request().headerValue("Authorization");
  const createdListing = await createdResponse.json();
  expect(createdListing.specs).toEqual({ Material: "algodón" });
  const seeded = await request.patch("/api/v1/seller/listings/" + createdListing.id, {
    headers: { Authorization: token! },
    data: { expectedVersion: 0, specs: { Material: "algodón", Dimensiones: { ancho: 42, incluyeFunda: false } } },
  });
  expect(seeded.ok()).toBeTruthy();

  await page.getByRole("link", { name: "Volver a Publicaciones" }).press("Enter");
  await page.getByRole("link", { name: "Ajustar stock", exact: true }).press("Enter");
  await expect(page.getByRole("heading", { name: "Inventario" })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Ajustar stock de " + firstCode.toUpperCase() })).toBeVisible();
  await expect(page.getByRole("button", { name: "Ajustar stock de " + secondCode.toUpperCase() })).toBeVisible();
  await page.getByRole("link", { name: "Ver publicaciones", exact: true }).press("Enter");
  await page.getByRole("link", { name: "Remera de prueba" }).press("Enter");
  const grouped = page.getByText("Ver valores agrupados", { exact: true });
  await expect(grouped).toBeVisible();
  await grouped.press("Enter");
  await expect(page.getByText("ancho", { exact: true })).toBeVisible();
  await expect(page.locator("input[value='algodón']")).toBeVisible();
  await page.getByRole("button", { name: "Agregar Marca" }).press("Enter");
  await page.getByLabel("Valor 3").fill("Taller Sur");
  await page.screenshot({ path: testInfo.outputPath("caracteristicas-movil.png"), fullPage: true });
  const staleTab = await page.context().newPage();
  await staleTab.goto(page.url());
  await expect(staleTab.getByLabel("Título")).toHaveValue("Remera de prueba");

  await page.getByLabel("Título").fill("Remera actualizada");
  const edited = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/seller/listings/" + createdListing.id) && response.request().method() === "PATCH");
  await page.getByRole("button", { name: "Guardar ficha" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Remera actualizada" })).toBeVisible();
  expect((await (await edited).json()).specs).toEqual({
    Material: "algodón", Marca: "Taller Sur", Dimensiones: { ancho: 42, incluyeFunda: false },
  });

  await staleTab.getByLabel("Título").fill("Edición obsoleta");
  await staleTab.getByRole("button", { name: "Guardar ficha" }).press("Enter");
  await expect(staleTab.getByRole("alert")).toHaveText("Hay cambios más recientes. Recargá la publicación para seguir editando.");
  await expect(staleTab.getByLabel("Título")).toHaveValue("Edición obsoleta");
  await staleTab.getByRole("button", { name: "Recargar publicación" }).press("Enter");
  await expect(staleTab.getByLabel("Título")).toHaveValue("Remera actualizada");
  await staleTab.close();

  await variantsTab.press("Enter");
  await page.getByRole("button", { name: "Editar " + firstCode.toUpperCase() }).press("Enter");
  await expect(page.getByRole("heading", { name: "Editar " + firstCode.toUpperCase() })).toBeFocused();
  await expect(page.getByLabel("Stock inicial")).toHaveCount(0);
  await page.getByLabel("Talle", { exact: true }).selectOption("S");
  await page.getByLabel("Precio (ARS)").fill("12900");
  await page.getByRole("button", { name: "Guardar opción" }).press("Enter");
  await expect(firstRow).toContainText("Color Negro · Talle S");
  await expect(firstRow).toContainText("12900,00");
  await expect(firstRow.getByText("7 en stock", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("variantes-guardadas-movil.png"), fullPage: true });
});
