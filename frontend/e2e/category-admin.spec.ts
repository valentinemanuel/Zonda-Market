import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("edita el árbol y las tasas en móvil con teclado y recupera errores", async ({ page, request, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const adminEmail = `admin-e602-${randomUUID()}@example.test`;
  const registered = await request.post("/api/v1/auth/register", {
    headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email: adminEmail, password },
  });
  expect(registered.status()).toBe(201);
  expect(await sql(`UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${adminEmail}' RETURNING email`))
    .toContain(adminEmail);
  const headers = { Authorization: "Bearer " + (await registered.json()).accessToken };
  const endpoint = "/api/v1/admin/categories";
  const suffix = randomUUID().slice(0, 8);
  const root = { name: "Audio " + suffix, slug: "audio-" + suffix, parentId: null, commissionBps: 0, attributeSchema: {} };
  const rootResponse = await request.post(endpoint, { headers, data: root });
  expect(rootResponse.status()).toBe(201);
  const { id: rootId } = await rootResponse.json();

  await page.goto("/ingresar");
  await credentials(page, adminEmail);
  await expect(page).toHaveURL(origin + "/catalogo");
  await page.goto("/cuenta");
  await page.getByRole("link", { name: "Moderación" }).press("Enter");

  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  await page.route("**/api/v1/admin/categories", async (route) => {
    await pending;
    await route.abort("failed");
  }, { times: 1 });
  try {
    await page.getByRole("main").getByRole("link", { name: "Categorías", exact: true }).press("Enter");
    await expect(page.getByText("Cargando categorías…")).toBeVisible();
  } finally {
    release();
  }
  await expect(page.getByRole("alert")).toHaveText("No pudimos cargar las categorías.");
  await page.getByRole("button", { name: "Reintentar" }).press("Enter");

  await page.getByRole("button", { name: `Archivar ${root.name}` }).press("Enter");
  await expect(page.getByRole("heading", { name: `Archivar ${root.name}` })).toBeFocused();
  await page.getByRole("button", { name: "Cancelar" }).press("Enter");
  await expect(page.getByRole("button", { name: `Archivar ${root.name}` })).toBeFocused();

  await page.getByRole("button", { name: "Nueva categoría" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Nueva categoría" })).toBeFocused();
  await page.keyboard.press("Tab");
  const name = page.getByLabel("Nombre (obligatorio)");
  await expect(name).toBeFocused();
  const child = "Parlantes " + suffix;
  await name.fill(child);
  const slug = page.getByLabel("Slug (obligatorio)");
  await slug.fill(root.slug);
  await page.getByLabel("Categoría superior").selectOption(rootId);
  await page.getByLabel("Comisión en puntos básicos").fill("1250");
  await expect(page.getByText(/Tasa ingresada: 12,5\s*%/)).toBeVisible();
  await page.getByRole("button", { name: "Agregar atributo" }).press("Enter");
  await page.getByLabel("Nombre del atributo").fill("color");
  await page.getByLabel("Valores permitidos").fill("Negro\nBlanco");
  await expect(page.getByText("Negro, Blanco")).toBeVisible();

  const save = page.getByRole("button", { name: "Guardar categoría" });
  await save.press("Enter");
  await expect(page.getByRole("alert").filter({ hasText: "Ese slug ya está en uso." })).toBeVisible();
  await expect(name).toHaveValue(child);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("categorias-conflicto-movil.png"), fullPage: true });
  await slug.fill("parlantes-" + suffix);
  await save.press("Enter");
  await expect(page.getByText(`${child}: cambios guardados.`)).toBeVisible();
  const childSummary = page.getByText(child, { exact: true }).locator("..");
  await expect(childSummary).toContainText(/12,5\s*% de comisión/);
  await expect(page.getByRole("heading", { name: "Árbol de categorías" })).toBeFocused();
  await page.screenshot({ path: testInfo.outputPath("categorias-arbol-movil.png"), fullPage: true });

  await page.getByRole("button", { name: `Archivar ${root.name}` }).press("Enter");
  await expect(page.getByRole("button", { name: "Confirmar archivo" })).toBeDisabled();
  await page.getByRole("button", { name: "Cancelar" }).press("Enter");

  await page.getByRole("button", { name: `Editar ${child}` }).press("Enter");
  const rows = await (await request.get(endpoint, { headers })).json();
  const saved = rows.find((row: { name: string }) => row.name === child);
  const concurrentEdit = await request.patch(endpoint + "/" + saved.id, {
    headers, data: {
      name: saved.name, slug: saved.slug, parentId: saved.parentId, commissionBps: 1500,
      expectedVersion: saved.version, attributeSchema: saved.attributeSchema,
    },
  });
  expect(concurrentEdit.status()).toBe(200);
  await save.press("Enter");
  await expect(page.getByRole("alert").filter({ hasText: "La categoría cambió en otra sesión." })).toBeVisible();
  await page.getByRole("button", { name: "Cargar datos actuales" }).press("Enter");
  await expect(page.getByLabel("Comisión en puntos básicos")).toHaveValue("1500");
  await page.getByLabel("Categoría superior").selectOption("");
  await page.getByLabel("Comisión en puntos básicos").fill("2000");
  await save.press("Enter");
  await expect(childSummary).toContainText(/20\s*% de comisión/);

  await page.getByRole("button", { name: `Archivar ${child}` }).press("Enter");
  await page.getByRole("button", { name: "Confirmar archivo" }).press("Enter");
  await expect(page.getByText(`${child}: categoría archivada.`)).toBeVisible();
  await page.getByText(/Archivadas \(\d+\)/).press("Enter");
  await expect(page.getByText(child + " · parlantes-" + suffix)).toBeVisible();
});
