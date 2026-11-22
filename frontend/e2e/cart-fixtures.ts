import { randomUUID } from "node:crypto";
import { expect, request as playwrightRequest, type APIRequestContext, type Page } from "@playwright/test";
import { linkFromMail } from "./mailpit";

export const password = "password-ficticia-e301";
const sellerListings = "/api/v1/seller/listings";
const categoryId = "e2010000-0000-4000-8000-000000000001";

export async function publish(request: APIRequestContext, origin: string, photo: Buffer, name: string, price: number) {
  const suffix = randomUUID().slice(0, 8);
  const email = `vendedor-carrito-${suffix}@example.test`;
  const session = await playwrightRequest.newContext({ baseURL: origin });
  let accessToken: string;
  try {
    const registered = await session.post("/api/v1/auth/register", {
      headers: { Origin: origin, "X-Zonda-CSRF": "1" }, data: { email, password },
    });
    expect(registered.status()).toBe(201);
    accessToken = (await registered.json()).accessToken;
  } finally {
    await session.dispose();
  }
  const headers = { Authorization: "Bearer " + accessToken };
  expect((await request.post("/api/v1/auth/email-verification/request", { data: { email } })).status()).toBe(202);
  const verification = await linkFromMail(request, email, "/verificar-correo");
  const token = new URLSearchParams(verification.hash.slice(1)).get("token");
  expect((await request.post("/api/v1/auth/email-verification/confirm", { data: { token } })).status()).toBe(204);
  const seller = `Tienda ${name} ${suffix}`;
  expect((await request.post("/api/v1/me/seller-profile", {
    headers, data: { type: "PERSONAL", displayName: seller, slug: "carrito-" + suffix },
  })).status()).toBe(201);

  const title = `Producto ${name} ${suffix}`;
  const listing = await request.post(sellerListings, {
    headers, data: { categoryId, title, description: "Producto ficticio para el carrito.", specs: {} },
  });
  expect(listing.status()).toBe(201);
  const privatePath = sellerListings + "/" + (await listing.json()).id;
  const variant = await request.post(privatePath + "/skus", {
    headers, data: { code: "CARRO-" + suffix, attributes: { color: "Azul", talle: "M" }, price, initialStock: 5 },
  });
  expect(variant.status()).toBe(201);
  const { id: skuId } = await variant.json();
  expect((await request.post(privatePath + "/images", {
    headers, multipart: { file: { name: "producto.png", mimeType: "image/png", buffer: photo } },
  })).status()).toBe(201);
  const published = await request.post(privatePath + "/publish", { headers });
  expect(published.status()).toBe(200);
  return { title, seller, email, skuId, slug: (await published.json()).slug, privatePath, headers };
}

export async function credentials(page: Page, email: string) {
  await page.getByLabel("Correo electrónico").fill(email);
  await page.getByLabel("Contraseña").fill(password);
  await page.getByLabel("Contraseña").press("Enter");
}
