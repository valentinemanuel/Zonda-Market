import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import { linkFromMail } from "./mailpit";
import { productPhoto } from "./product-photo";

const password = "contraseña-ficticia-e204";
const category = "e2010000-0000-4000-8000-000000000001";
const privateListings = "/api/v1/seller/listings";

test.use({ viewport: { width: 390, height: 844 }, actionTimeout: 12_000 });

test("encuentra una variante, conserva los filtros y publica una conversación en texto plano", async ({
  page, browser, request, baseURL,
}, testInfo) => {
  const suffix = randomUUID().slice(0, 8);
  const email = `catalogo-${suffix}@example.test`;
  const seller = `catalogo-${suffix}`;
  const origin = new URL(baseURL!).origin;
  const registered = await request.post("/api/v1/auth/register", {
    headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email, password },
  });
  expect(registered.status()).toBe(201);
  const headers = { Authorization: "Bearer " + (await registered.json()).accessToken };
  expect((await request.post("/api/v1/auth/email-verification/request", { data: { email } })).status()).toBe(202);
  const link = await linkFromMail(request, email, "/verificar-correo");
  const token = new URLSearchParams(link.hash.slice(1)).get("token");
  expect((await request.post("/api/v1/auth/email-verification/confirm", { data: { token } })).status()).toBe(204);
  expect((await request.post("/api/v1/me/seller-profile", {
    headers, data: { type: "PERSONAL", displayName: "Taller de catálogo", slug: seller },
  })).status()).toBe(201);

  const title = "Camperas de prueba " + suffix;
  const listing = await request.post(privateListings, {
    headers, data: { categoryId: category, title, description: "Campera ficticia de algodón.", specs: {} },
  });
  expect(listing.status()).toBe(201);
  const { id } = await listing.json();
  const privateListing = privateListings + "/" + id;
  for (const [color, talle, price, initialStock] of [["Azul", "M", 150, 2], ["Rojo", "S", 80, 0]]) {
    expect((await request.post(privateListing + "/skus", {
      headers, data: { code: color + "-" + suffix, attributes: { color, talle }, price, initialStock },
    })).status()).toBe(201);
  }
  await page.goto("/catalogo");
  expect((await request.post(privateListing + "/images", {
    headers, multipart: { file: { name: "campera.png", mimeType: "image/png", buffer: await productPhoto(page, "#2563eb") } },
  })).status()).toBe(201);
  const published = await request.post(privateListing + "/publish", { headers });
  expect(published.status()).toBe(200);
  const { slug } = await published.json();
  const questions = "/api/v1/listings/" + id + "/questions";

  const search = page.getByRole("searchbox", { name: "Buscar publicaciones" });
  await search.fill("campera " + suffix);
  await expect(page).toHaveURL(/q=campera\+/);
  await page.getByRole("combobox", { name: "Categoría", exact: true }).selectOption(category);
  await page.getByRole("combobox", { name: "Color", exact: true }).selectOption("Rojo");
  await page.getByRole("combobox", { name: "Disponibilidad", exact: true }).selectOption("true");
  await expect(page.getByText("No encontramos publicaciones con esos filtros.")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("catalogo-sin-coincidencias.png"), fullPage: true });

  await page.getByRole("combobox", { name: "Color", exact: true }).selectOption("Azul");
  await expect(page.getByRole("status")).toHaveText("1 publicación");
  const card = page.getByRole("link", { name: new RegExp(title) });
  const catalogUrl = page.url();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({ path: testInfo.outputPath("catalogo-movil.png"), fullPage: true });
  await card.press("Enter");
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await page.getByRole("link", { name: "Volver al catálogo" }).press("Enter");
  await expect(page).toHaveURL(catalogUrl);
  await expect(page.getByRole("combobox", { name: "Color", exact: true })).toHaveValue("Azul");
  await card.press("Enter");
  expect((await request.post(questions, { data: { text: "¿Es de algodón?" } })).status()).toBe(401);
  await page.getByRole("link", { name: "Iniciá sesión para preguntar" }).press("Enter");
  await page.getByRole("link", { name: "Crear una cuenta" }).press("Enter");
  await expect(page.getByRole("heading", { name: "Crear cuenta" })).toBeVisible();
  await page.getByLabel("Correo electrónico").fill(`compra-${suffix}@example.test`);
  const registrationPassword = page.getByLabel("Contraseña");
  await registrationPassword.fill(password);
  await registrationPassword.press("Enter");
  await expect(page).toHaveURL(new URL("/publicaciones/" + slug + "#preguntas", origin).href);

  const input = page.getByLabel("Tu pregunta");
  await input.fill("¿Es de algodón?");
  const sent = page.waitForResponse((response) => response.url().endsWith(questions) && response.request().method() === "POST");
  await input.press("Tab");
  const submitQuestion = page.getByRole("button", { name: "Publicar pregunta" });
  await expect(submitQuestion).toBeFocused();
  await page.keyboard.press("Enter");
  const created = await sent;
  expect(created.status()).toBe(201);
  const publicQuestions = await request.get(questions);
  expect(publicQuestions.status()).toBe(200);
  const { id: questionId } = (await publicQuestions.json()).content[0];
  const answerPath = "/api/v1/seller/questions/" + questionId + "/answer";
  const unauthorized = await request.put(answerPath, {
    headers: { Authorization: (await created.request().headerValue("Authorization"))! }, data: { text: "Respuesta ajena" },
  });
  expect(unauthorized.status()).toBe(404);
  expect(await unauthorized.json()).toMatchObject({ code: "QUESTION_NOT_FOUND" });
  await expect(page.getByText("Pregunta publicada.", { exact: true })).toBeVisible();

  const owner = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const guest = await browser.newPage({ viewport: { width: 390, height: 844 } });
  try {
    await owner.goto(new URL("/ingresar", origin).href);
    await owner.getByLabel("Correo electrónico").fill(email);
    await owner.getByLabel("Contraseña").fill(password);
    await owner.getByLabel("Contraseña").press("Enter");
    await expect(owner).toHaveURL(/\/catalogo$/);
    await owner.getByRole("link", { name: "Mi cuenta", exact: true }).press("Enter");
    await owner.getByRole("link", { name: "Panel vendedor" }).press("Enter");
    await owner.getByRole("link", { name: "Preguntas", exact: true }).press("Enter");
    await expect(owner.getByText("¿Es de algodón?", { exact: true })).toBeVisible();
    const answer = "Sí, es de algodón. <b>Texto literal</b>";
    await owner.getByLabel("Tu respuesta").fill(answer);
    await owner.getByRole("button", { name: "Publicar respuesta" }).press("Enter");
    await expect(owner.getByText("No tenés preguntas pendientes en esta página.")).toBeVisible();

    await guest.goto(new URL("/publicaciones/" + slug + "#preguntas", origin).href);
    const thread = guest.getByRole("region", { name: "Preguntas y respuestas" });
    await expect(thread.getByText(answer, { exact: true })).toBeVisible();
    await expect(thread.locator("b")).toHaveCount(0);
    await guest.screenshot({ path: testInfo.outputPath("respuesta-publica-movil.png"), fullPage: true });

    expect((await request.post(privateListing + "/archive", { headers })).status()).toBe(200);
    await guest.reload();
    await expect(guest.getByRole("heading", { name: "Publicación no disponible" })).toBeVisible();
  } finally {
    await owner.close();
    await guest.close();
  }
});

test("anuncia la carga y permite recuperarse de un error del catálogo", async ({ page }, testInfo) => {
  let release!: () => void;
  const failure = new Promise<void>((resolve) => { release = resolve; });
  await page.route(/\/api\/v1\/listings(?:\?.*)?$/, async (route) => {
    await failure;
    await route.fulfill({ status: 503 });
  }, { times: 1 });
  await page.goto("/catalogo");
  await expect(page.getByText("Cargando publicaciones…", { exact: true })).toBeVisible();
  release();
  await expect(page.getByRole("alert")).toContainText("No pudimos cargar el catálogo.");
  await page.screenshot({ path: testInfo.outputPath("catalogo-error-movil.png"), fullPage: true });
  await page.getByRole("button", { name: "Reintentar catálogo" }).press("Enter");
  await expect(page.getByRole("status")).toHaveText(/publicaci/);
  await expect(page.getByRole("alert")).toHaveCount(0);
});
