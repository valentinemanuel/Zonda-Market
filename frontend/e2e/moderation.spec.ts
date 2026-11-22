import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { credentials, password } from "./cart-fixtures";
import { sql } from "./compose-fixture";
import { purchaseFixture } from "./purchase-fixture";

test.use({ viewport: { width: 390, height: 844 } });

test("modera una publicación y suspende al vendedor conservando la compra y su auditoría", async ({
  page: buyerPage, browser, request, baseURL,
}, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const cookieHeaders = { Origin: origin, "X-Zonda-CSRF": "1" };
  const { alpha, beta, order, approve } = await purchaseFixture(buyerPage, request, origin, "moderacion");
  await approve();

  const adminEmail = `admin-e601-${randomUUID()}@example.test`;
  expect((await request.post("/api/v1/auth/register", {
    headers: cookieHeaders, data: { email: adminEmail, password },
  })).status()).toBe(201);
  expect(await sql(`UPDATE zonda_market.user_account SET role = 'ADMIN' WHERE email = '${adminEmail}' RETURNING email`))
    .toContain(adminEmail);

  const sellerPage = await browser.newPage();
  const adminPage = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await sellerPage.goto(origin + "/ingresar");
    await credentials(sellerPage, alpha.email);
    await expect(sellerPage).toHaveURL(origin + "/catalogo");
    await sellerPage.goto(origin + "/admin");
    await expect(sellerPage.getByRole("alert")).toHaveText("Necesitás una cuenta administradora para entrar.");
    for (const resource of ["listings", "users"]) {
      const denied = await request.get("/api/v1/admin/" + resource, { headers: alpha.headers });
      expect(denied.status()).toBe(403);
      expect((await denied.json()).code).toBe("ACCESS_DENIED");
    }

    await adminPage.goto(origin + "/ingresar");
    await credentials(adminPage, adminEmail);
    await expect(adminPage).toHaveURL(origin + "/catalogo");
    await adminPage.goto(origin + "/cuenta");
    const link = adminPage.getByRole("link", { name: "Moderación" });
    let release!: () => void;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    await adminPage.route("**/api/v1/admin/listings?*", async (route) => {
      await pending;
      await route.abort("failed");
    }, { times: 1 });
    try {
      await link.press("Enter");
      await expect(adminPage.getByRole("status").filter({ hasText: "Cargando resultados…" })).toBeVisible();
    } finally {
      release();
    }
    await expect(adminPage.getByRole("alert")).toHaveText("No pudimos cargar los resultados.");
    await adminPage.getByRole("button", { name: "Reintentar" }).press("Enter");
    const listingSearch = adminPage.getByLabel("Título o correo del vendedor");
    const filter = adminPage.getByRole("button", { name: "Filtrar" });
    await listingSearch.fill("Sin resultados " + randomUUID());
    await filter.press("Enter");
    await expect(adminPage.getByText("No hay resultados para estos filtros.")).toBeVisible();
    await listingSearch.fill(alpha.title);
    await filter.press("Enter");
    const listing = adminPage.getByRole("row", { name: new RegExp(alpha.title) });
    await expect(listing).toContainText("Visible en catálogo");
    expect((await request.get("/api/v1/listings/" + alpha.slug)).status()).toBe(200);

    const hide = listing.getByRole("button", { name: "Ocultar publicación" });
    await hide.press("Enter");
    const reason = adminPage.getByLabel("Motivo obligatorio");
    await expect(reason).toBeFocused();
    await adminPage.getByRole("button", { name: "Cancelar" }).press("Enter");
    await expect(hide).toBeFocused();
    await adminPage.keyboard.press("Enter");
    await reason.fill("Revisión ficticia de la ficha");
    await adminPage.keyboard.press("Tab");
    await expect(adminPage.getByRole("button", { name: "Confirmar cambio" })).toBeFocused();
    await adminPage.keyboard.press("Enter");
    await expect(listing).toContainText("Oculta por moderación");
    const listingId = alpha.privatePath.split("/").at(-1);
    expect((await request.get("/api/v1/listings/" + alpha.slug)).status()).toBe(404);
    expect((await request.get(`/api/v1/listings/${listingId}/questions`)).status()).toBe(404);
    expect((await request.get("/api/v1/listings/" + beta.slug)).status()).toBe(200);
    expect((await request.post(alpha.privatePath + "/archive", { headers: alpha.headers })).status()).toBe(200);
    expect((await request.post(alpha.privatePath + "/publish", { headers: alpha.headers })).status()).toBe(200);
    expect((await request.get("/api/v1/listings/" + alpha.slug)).status()).toBe(404);

    await listing.getByRole("button", { name: "Ver detalle" }).press("Enter");
    await expect(adminPage.getByText("Revisión ficticia de la ficha")).toBeVisible();
    await expect(adminPage.getByText("Requiere seguimiento")).toBeVisible();
    await expect(adminPage.getByText(new RegExp(adminEmail))).toBeVisible();
    await adminPage.screenshot({ path: testInfo.outputPath("moderacion-publicacion-movil.png"), fullPage: true });

    await adminPage.getByRole("link", { name: "Cuentas" }).press("Enter");
    await adminPage.getByLabel("Nombre o correo").fill(adminEmail);
    await adminPage.getByRole("button", { name: "Filtrar" }).press("Enter");
    const ownRow = adminPage.getByRole("row", { name: new RegExp(adminEmail) });
    await expect(ownRow).toBeVisible();
    await expect(ownRow.getByRole("button", { name: "Suspender cuenta" })).toHaveCount(0);
    await adminPage.getByLabel("Nombre o correo").fill(alpha.email);
    await adminPage.getByRole("button", { name: "Filtrar" }).press("Enter");
    const seller = adminPage.getByRole("row", { name: new RegExp(alpha.email) });
    await seller.getByRole("button", { name: "Suspender cuenta" }).press("Enter");
    await adminPage.getByLabel("Motivo obligatorio").fill("Cuenta ficticia en revisión");
    await adminPage.getByRole("button", { name: "Confirmar cambio" }).press("Enter");
    await expect(seller).toContainText("Suspendida");
    await seller.getByRole("button", { name: "Ver detalle" }).press("Enter");
    await expect(adminPage.getByText("Cuenta ficticia en revisión")).toBeVisible();
    await expect(adminPage.getByText(new RegExp(order.id))).toBeVisible();
    expect(await adminPage.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
    await adminPage.screenshot({ path: testInfo.outputPath("moderacion-seguimiento-movil.png"), fullPage: true });

    expect((await request.get("/api/v1/me", { headers: alpha.headers })).status()).toBe(401);
    expect((await sellerPage.request.post(origin + "/api/v1/auth/refresh", { headers: cookieHeaders })).status()).toBe(401);
    expect((await sellerPage.request.post(origin + "/api/v1/auth/login", {
      headers: cookieHeaders, data: { email: alpha.email, password },
    })).status()).toBe(401);

    await buyerPage.goto("/mis-ordenes");
    await buyerPage.getByRole("link", { name: "Ver orden " + order.id }).press("Enter");
    await expect(buyerPage.getByText(alpha.title, { exact: true })).toBeVisible();
    await expect(buyerPage.getByText(beta.title, { exact: true })).toBeVisible();

    await seller.getByRole("button", { name: "Reactivar cuenta" }).press("Enter");
    await adminPage.getByLabel("Motivo obligatorio").fill("Revisión de cuenta completada");
    await adminPage.getByRole("button", { name: "Confirmar cambio" }).press("Enter");
    await expect(seller).toContainText("Activa");
    expect((await request.get("/api/v1/listings/" + alpha.slug)).status()).toBe(404);
    await sellerPage.goto(origin + "/ingresar");
    await credentials(sellerPage, alpha.email);
    await expect(sellerPage).toHaveURL(origin + "/catalogo");

    await adminPage.getByRole("link", { name: "Publicaciones" }).press("Enter");
    await listingSearch.fill(alpha.title);
    await adminPage.getByLabel("Estado").selectOption("HIDDEN");
    await filter.press("Enter");
    await listing.getByRole("button", { name: "Restaurar publicación" }).press("Enter");
    await adminPage.getByLabel("Motivo obligatorio").fill("Revisión de publicación completada");
    await adminPage.getByRole("button", { name: "Confirmar cambio" }).press("Enter");
    await expect(adminPage.getByText("No hay resultados para estos filtros.")).toBeVisible();
    expect((await request.get("/api/v1/listings/" + alpha.slug)).status()).toBe(200);
  } finally {
    await sellerPage.close();
    await adminPage.close();
  }
});
