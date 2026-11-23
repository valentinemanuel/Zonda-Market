import { authenticatedRequest } from "@/features/auth/session-api";
import type { Period } from "@/features/sales/seller-sales-api";

export const operationsPath = "/admin/operacion";

type Amounts = { gmv: string; refunds: string; platformCommission: string };
type Counts = { paidOrders: number; disputes: number };
export type OperationsDay = Amounts & Counts & { date: string };
export type Operations = Amounts & Counts & Period & {
  currency: string;
  timeZone: string;
  openCases: number;
  series: OperationsDay[];
  sellers: Array<Amounts & { id: string; name: string }>;
  categories: Array<{ id: string; name: string; merchandise: string; originalCommission: string }>;
  breakdownLimited: boolean;
};

export async function fetchOperations(params: URLSearchParams, signal: AbortSignal): Promise<Operations> {
  return (await authenticatedRequest(`/api/v1/admin/dashboard?${params}`, { signal })).json();
}

export async function downloadOperations(period: Period, signal: AbortSignal) {
  const params = new URLSearchParams(period);
  const response = await authenticatedRequest(`/api/v1/admin/reports/sales.csv?${params}`, {
    signal, headers: { Accept: "text/csv, application/problem+json" },
  });
  return response.blob();
}
