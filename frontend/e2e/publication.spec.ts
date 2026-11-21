import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";
import { productPhoto } from "./product-photo";

test.use({
  baseURL: process.env.ZONDA_SMOKE_URL ?? "http://localhost:3000",
  viewport: { width: 390, height: 844 },
});

test("adjunta imágenes, publica una ficha navegable y la archiva desde el panel", async ({ page, request, browser }, testInfo) => {
  const suffix = randomUUID().slice(0, 8);
  const email = "imagenes-" + suffix + "@example.test";
  await page.goto("/registro");
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill("contraseña-ficticia-e202");
  await page.getByLabel("Contraseña").press("Enter");
  await page.getByRole("button", { name: "Enviar enlace de verificación" }).press("Enter");
  await page.goto((await linkFromMail(request, email, "/verificar-correo")).href);
  await page.getByRole("button", { name: "Confirmar correo" }).press("Enter");
  await page.getByRole("link", { name: "Ir a Mi cuenta" }).press("Enter");
  await page.getByLabel("Tipo de vendedor").selectOption("PERSONAL");
  await page.getByLabel("Nombre público").fill("Taller de prueba");
  await page.getByLabel("Identificador público").fill("galeria-" + suffix);
  await page.getByRole("button", { name: "Activar perfil de vendedor" }).press("Enter");

  await page.getByRole("link", { name: "Mis publicaciones" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Todavía no creaste publicaciones." })).toBeVisible();
  await page.getByRole("link", { name: "Crear publicación" }).press("Enter");
  await page.getByLabel("Categoría").selectOption({ label: "Indumentaria" });
  await page.getByLabel("Título").fill("Remera ilustrada");
  await page.getByLabel("Descripción").fill("Remera ficticia de algodón para la prueba de catálogo.");
  await page.getByRole("button", { name: "Crear borrador" }).press("Enter");

  const previewTab = page.getByRole("tab", { name: "Vista previa" });
  const imagesTab = page.getByRole("tab", { name: "Fotos" });
  await previewTab.press("Enter");
  const incomplete = page.waitForResponse((response) => response.url().endsWith("/publish"));
  await page.getByRole("button", { name: "Publicar ficha" }).press("Enter");
  expect((await incomplete).status()).toBe(409);
  await expect(page.getByRole("alert")).toHaveText("Completá la ficha, una variante vigente y al menos una imagen para publicar.");

  await page.getByRole("button", { name: "Agregar imágenes" }).press("Enter");
  await expect(imagesTab).toBeFocused();
  const file = page.getByLabel("Archivo de imagen");
  const upload = page.getByRole("button", { name: "Subir imagen" });
  await expect(page.getByText("Todavía no agregaste imágenes.")).toBeVisible();
  await file.setInputFiles({ name: "falsa.png", mimeType: "image/png", buffer: Buffer.from("archivo ficticio") });
  const invalid = page.waitForResponse((response) => response.url().endsWith("/images") && response.request().method() === "POST");
  await upload.press("Enter");
  const invalidResponse = await invalid;
  expect(invalidResponse.status()).toBe(400);
  await expect(page.getByRole("alert")).toHaveText("Elegí una imagen JPEG, PNG o WebP válida.");
  await page.screenshot({ path: testInfo.outputPath("imagen-invalida-movil.png"), fullPage: true });

  const authorization = await invalidResponse.request().headerValue("Authorization");
  const oversized = await request.post(invalidResponse.url(), {
    headers: { Authorization: authorization! },
    multipart: { file: { name: "grande.png", mimeType: "image/png", buffer: Buffer.alloc(5 * 1024 * 1024 + 1) } },
  });
  expect(oversized.status()).toBe(413);
  expect(await oversized.json()).toMatchObject({ code: "IMAGE_TOO_LARGE" });

  async function attach(name: string, alt: string, tint: string) {
    await file.setInputFiles({ name, mimeType: "image/png", buffer: await productPhoto(page, tint) });
    await page.getByLabel("Texto alternativo para la imagen nueva").fill(alt);
    await upload.press("Enter");
    await expect(page.getByRole("img", { name: alt, exact: true })).toBeVisible();
  }

  await attach("frente.png", "Remera azul", "#245fb9");
  await attach("segunda.png", "Remera violeta", "#7a4e96");
  await page.getByRole("button", { name: "Subir posición de imagen 2" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Imagen 1", exact: true })).toBeFocused();
  await expect(page.getByRole("img", { name: "Remera violeta" })).toBeVisible();
  const firstImage = page.getByRole("heading", { name: "Imagen 1", exact: true }).locator("..");
  await firstImage.getByLabel("Texto alternativo", { exact: true }).fill("Vista violeta");
  await firstImage.getByRole("button", { name: "Guardar texto" }).press("Enter");
  await expect(page.getByRole("img", { name: "Vista violeta" })).toBeVisible();

  await imagesTab.press("ArrowLeft");
  await expect(page.getByRole("tab", { name: "Opciones de venta" })).toBeFocused();
  async function addSku(color: string, price: string, stock: string) {
    await page.getByLabel("Código interno (SKU)").fill(color + "-" + suffix);
    await page.getByLabel("Color", { exact: true }).selectOption(color);
    await page.getByLabel("Talle", { exact: true }).selectOption("M");
    await page.getByLabel("Precio (ARS)").fill(price);
    await page.getByLabel("Stock inicial").fill(stock);
    await page.getByRole("button", { name: "Agregar opción" }).press("Enter");
    await expect(page.getByRole("row", { name: new RegExp(color + "-" + suffix, "i") })).toBeVisible();
  }
  await addSku("Azul", "12900,50", "5");
  await addSku("Rojo", "15000", "0");

  await previewTab.press("Enter");
  await page.getByRole("button", { name: "Publicar ficha" }).press("Enter");
  await expect(page.getByRole("status")).toHaveText("Publicación visible.");
  const href = await page.getByRole("link", { name: "Ver ficha pública" }).getAttribute("href");
  const publicUrl = new URL(href!, page.url()).href;
  const guest = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await guest.goto(publicUrl);
    await expect(guest.getByRole("heading", { name: "Remera ilustrada" })).toBeVisible();
    await expect(guest.getByRole("link", { name: "Taller de prueba" })).toBeVisible();
    const mainImage = guest.getByRole("img", { name: "Vista violeta" });
    await expect.poll(() => mainImage.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);
    await guest.getByRole("button", { name: "Ver imagen 2 · Remera azul" }).press("Enter");
    await expect(guest.getByRole("img", { name: "Remera azul" })).toBeVisible();
    await expect(guest.getByText("$ 12.900,50", { exact: true })).toBeVisible();
    await guest.getByLabel("Variante").press("ArrowDown");
    await expect(guest.getByText("$ 15.000,00", { exact: true })).toBeVisible();
    await expect(guest.getByText("Sin stock", { exact: true })).toBeVisible();
    expect(await guest.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await guest.screenshot({ path: testInfo.outputPath("ficha-publicada-movil.png"), fullPage: true });

    await page.getByRole("button", { name: "Archivar publicación" }).press("Enter");
    await expect(page.getByRole("status")).toHaveText("Publicación archivada.");
    await expect(page.getByRole("link", { name: "Ver ficha pública" })).toHaveCount(0);
    await guest.reload();
    await expect(guest.getByRole("heading", { name: "Publicación no disponible" })).toBeVisible();
    await imagesTab.press("Enter");
    await expect(page.getByRole("img", { name: "Vista violeta" })).toBeVisible();
    await page.getByRole("button", { name: "Retirar imagen 1" }).press("Enter");
    await expect(file).toBeFocused();
    await expect(page.getByRole("img", { name: "Vista violeta" })).toHaveCount(0);
  } finally {
    await guest.close();
  }
});
