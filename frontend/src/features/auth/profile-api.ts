import { request } from "@/shared/api/http-client";
import { type Account, authenticatedRequest } from "./session-api";

export type AddressFields = {
  street: string;
  city: string;
  provinceCode: string;
  postalCode: string;
  country: "AR";
};
export type Address = AddressFields & { id: string };
export type SellerType = "PERSONAL" | "COMERCIO";
export type SellerFields = { type: SellerType; displayName: string; slug: string; bio: string };
export type Seller = SellerFields & { reviewCount: number; averageRating: number | null };

function json(method: "PATCH" | "POST", data: object): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) };
}

export async function updateProfile(displayName: string): Promise<Account> {
  return (await authenticatedRequest("/api/v1/me", json("PATCH", { displayName }))).json();
}

export async function getAddresses(signal: AbortSignal): Promise<Address[]> {
  return (await authenticatedRequest("/api/v1/me/addresses", { signal })).json();
}

export async function saveAddress(address: AddressFields, id?: string): Promise<Address> {
  const path = id ? `/api/v1/me/addresses/${id}` : "/api/v1/me/addresses";
  return (await authenticatedRequest(path, json(id ? "PATCH" : "POST", address))).json();
}

export async function removeAddress(id: string) {
  await authenticatedRequest(`/api/v1/me/addresses/${id}`, { method: "DELETE" });
}

export async function activateSeller(profile: SellerFields): Promise<Seller> {
  return (await authenticatedRequest("/api/v1/me/seller-profile", json("POST", profile))).json();
}

export async function ownSeller(signal: AbortSignal): Promise<Seller> {
  return (await authenticatedRequest("/api/v1/me/seller-profile", { signal })).json();
}

export async function publicSeller(slug: string, signal: AbortSignal): Promise<Seller> {
  return (await request(`/api/v1/sellers/${encodeURIComponent(slug)}`, { signal })).json();
}
