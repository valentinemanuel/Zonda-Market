import { authenticatedRequest } from "@/features/auth/session-api";

export type Period = { from: string; to: string };
export type Amounts = { gross: string; commission: string; refunds: string; commissionReversed: string; net: string };
export type SalesDay = Amounts & { date: string };
export type SellerMetrics = Period & Amounts & {
  currency: "ARS";
  timeZone: string;
  ordersByState: Record<string, number>;
  series: SalesDay[];
};
export type Settlement = Amounts & {
  subOrderId: string;
  status: string;
  recordedAt: string;
  lastActivityAt: string;
  merchandise: string;
  shipping: string;
};
type SettlementPage = { content: Settlement[]; page: number; size: number; hasNext: boolean };

export const salesKey = (revision: number) => ["private", "seller-sales", revision] as const;

export async function fetchMetrics(period: Partial<Period>, signal: AbortSignal): Promise<SellerMetrics> {
  return (await authenticatedRequest("/api/v1/seller/metrics?" + new URLSearchParams(period), { signal })).json();
}

export async function fetchSettlements(period: Period, page: number, signal: AbortSignal): Promise<SettlementPage> {
  const params = new URLSearchParams({ ...period, page: String(page) });
  return (await authenticatedRequest("/api/v1/seller/settlements?" + params, { signal })).json();
}
