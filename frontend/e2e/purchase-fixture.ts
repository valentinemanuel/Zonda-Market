import { randomUUID } from "node:crypto";
import { expect, type APIRequestContext, type Page } from "@playwright/test";
import type { Order } from "../src/features/checkout/checkout-api";
import { credentials, publish, verifyByMail } from "./cart-fixtures";
import { productPhoto } from "./product-photo";

export async function purchaseCartFixture(page: Page, request: APIRequestContext, origin: string, label: string,
  verify = verifyByMail) {
  await page.goto("/registro");
  const photo = await productPhoto(page, "#2563eb");
  const alpha = await publish(request, origin, photo, "Alfa envío", 100, verify);
  const beta = await publish(request, origin, photo, "Beta envío", 150, verify);
  const buyer = `${label}-${randomUUID().slice(0, 8)}@example.test`;
  const registered = page.waitForResponse("**/api/v1/auth/register");
  await credentials(page, buyer);
  const headers = { Authorization: "Bearer " + (await (await registered).json()).accessToken };
  await expect(page.getByRole("heading", { name: "Mi cuenta" })).toBeVisible();
  await verify(request, buyer);
  const address = await request.post("/api/v1/me/addresses", {
    headers, data: { street: "Calle Ficticia 200", city: "La Plata", provinceCode: "AR-B", postalCode: "1900", country: "AR" },
  });
  expect(address.status()).toBe(201);
  for (const [expectedVersion, product] of [alpha, beta].entries()) {
    expect((await request.put("/api/v1/cart/items/" + product.skuId, {
      headers, data: { quantity: 1, expectedVersion },
    })).status()).toBe(200);
  }
  return { alpha, beta, buyer, headers, addressId: (await address.json()).id as string };
}

export async function purchaseFixture(page: Page, request: APIRequestContext, origin: string, label = "envio",
  verify = verifyByMail) {
  const { alpha, beta, buyer, headers, addressId } = await purchaseCartFixture(page, request, origin, label, verify);
  const quote = await request.post("/api/v1/checkout/quote", {
    headers, data: { addressId, cartVersion: 2 },
  });
  expect(quote.status()).toBe(200);
  const { id: quoteId } = await quote.json();
  const reservation = await request.post("/api/v1/checkout/confirm", {
    headers: { ...headers, "Idempotency-Key": quoteId }, data: { quoteId },
  });
  expect(reservation.status()).toBe(200);
  const order = await reservation.json() as Pick<Order, "id" | "subOrders" | "testPayment">;
  const approve = async () => {
    expect((await request.post(`/api/v1/test-fixtures/orders/${order.id}/payment-outcome`, {
      headers, data: { reference: order.testPayment!.reference, outcome: "APPROVED" },
    })).status()).toBe(200);
  };
  return { order, alpha, beta, buyer, headers, approve };
}
