import { authenticatedRequest } from "@/features/auth/session-api";

export type StockAdjustment = { delta: number; reason: string; expectedVersion: number };
export type StockMovement = {
  id: string;
  skuId: string;
  reason: string;
  delta: number;
  beforeStock: number;
  afterStock: number;
  actorId: string;
  traceId: string;
  version: number;
  createdAt: string;
};
export type MovementPage = { items: StockMovement[]; page: number; size: number; totalElements: number; totalPages: number };

function skuPath(skuId: string) {
  return "/api/v1/seller/skus/" + encodeURIComponent(skuId);
}

export async function adjustStock(skuId: string, key: string, command: StockAdjustment): Promise<StockMovement> {
  return (await authenticatedRequest(skuPath(skuId) + "/stock-adjustments", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    body: JSON.stringify(command),
  })).json();
}

export async function fetchStockMovements(skuId: string, page: number, signal: AbortSignal): Promise<MovementPage> {
  return (await authenticatedRequest(skuPath(skuId) + "/stock-movements?page=" + page + "&size=10", { signal })).json();
}
