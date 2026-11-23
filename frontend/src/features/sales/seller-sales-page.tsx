import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { subOrderLabel } from "@/features/checkout/shipment-timeline";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { SellerHeading, SellerNavigation, sellerSalesPath } from "@/features/listings/seller-workspace";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { PeriodForm } from "./period-form";
import { dateLabel } from "./report-presentation";
import { SalesSeries, SettlementTable } from "./sales-tables";
import { fetchMetrics, fetchSettlements, salesKey, type SellerMetrics } from "./seller-sales-api";

export function SellerSalesPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const [params, setParams] = useSearchParams();
  const from = params.get("from");
  const to = params.get("to");
  const raw = Number(params.get("pagina"));
  const page = Number.isInteger(raw) && raw > 0 && raw <= 2147483647 ? raw : 0;
  const metrics = useQuery({
    queryKey: [...salesKey(revision), "metrics", from, to],
    queryFn: ({ signal }) => fetchMetrics({ ...(from !== null && { from }), ...(to !== null && { to }) }, signal),
    retry: false,
  });
  const accepted = metrics.data;
  if (signInRequired(metrics.error)) {
    return <Button asChild><Link to="/ingresar" state={{ returnToSellerSales: true }}>Iniciar sesión para ver tus ventas</Link></Button>;
  }
  const forbidden = metrics.error instanceof ApiError && metrics.error.status === 403;
  return <div className="mx-auto max-w-5xl space-y-5">
    <SellerNavigation />
    <SellerHeading title="Ventas y neto estimado">
      <p className="text-sm font-medium text-slate-200">Entorno de prueba · ARS</p>
    </SellerHeading>
    {!forbidden && (!metrics.isPending || from !== null || to !== null) &&
      <PeriodForm path={sellerSalesPath} from={from ?? accepted?.from ?? ""} to={to ?? accepted?.to ?? ""}
        apply={(period) => setParams(period)} />}
    {metrics.isPending ? <p role="status">Cargando ventas…</p>
      : metrics.isError ? <div className="space-y-2">
        <p role="alert" className="text-destructive">{forbidden ? "Activá tu perfil de vendedor desde Mi cuenta."
          : metrics.error instanceof ApiError && metrics.error.status === 400
            ? "Elegí un período válido de hasta 90 días corridos." : "No pudimos cargar tus ventas."}</p>
        {!forbidden && (metrics.error instanceof ApiError && metrics.error.status === 400
          ? <Button type="button" variant="outline" onClick={() => setParams({})}>Restablecer período</Button>
          : <Button type="button" variant="outline" onClick={() => void metrics.refetch()}>Reintentar</Button>)}
      </div>
      : <>
        <SalesSummary metrics={metrics.data} />
        {metrics.data.series.some((day) => day.gross !== "0.00" || day.refunds !== "0.00")
          ? <SalesSeries series={metrics.data.series} />
          : <p role="status" className="rounded-lg border bg-card p-4">No hay movimientos en este período.</p>}
        <SettlementList metrics={metrics.data} revision={revision} page={page}
          changePage={(next) => setParams({ from: metrics.data.from, to: metrics.data.to, pagina: String(next) })} />
      </>}
  </div>;
}

function SalesSummary({ metrics }: { metrics: SellerMetrics }) {
  const amounts = [
    ["Ventas brutas", metrics.gross],
    ["Comisiones", metrics.commission],
    ["Devoluciones", metrics.refunds],
    ["Neto interno estimado", metrics.net],
  ];
  const states = Object.entries(metrics.ordersByState);
  return <section className="space-y-3" aria-labelledby="sales-summary">
    <h2 id="sales-summary" className="text-lg font-semibold">Resumen de ventas</h2>
    <p role="status" className="text-sm text-muted-foreground">
      <time dateTime={metrics.from}>{dateLabel(metrics.from)}</time> al <time dateTime={metrics.to}>{dateLabel(metrics.to)}</time>
    </p>
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      {amounts.map(([label, amount]) => <Card key={label} className="gap-0 py-3 sm:py-4">
        <CardContent className="px-3 sm:px-4">
          <dl className="space-y-1">
            <dt className="text-sm font-medium text-muted-foreground">{label}</dt>
            <dd className="text-lg font-semibold tabular-nums [overflow-wrap:anywhere] sm:text-2xl">{formattedPrice(amount)}</dd>
          </dl>
        </CardContent>
      </Card>)}
    </div>
    <p className="text-sm text-muted-foreground">Comisión revertida <strong className="text-foreground">{formattedPrice(metrics.commissionReversed)}</strong></p>
    {states.length > 0 && <div className="space-y-1">
      <h3 className="text-sm font-semibold">Subórdenes acreditadas</h3>
      <ul className="flex flex-wrap gap-2 text-sm">
        {states.map(([state, count]) => <li key={state} className="rounded-md border bg-card px-2 py-1">
          {subOrderLabel(state)} · {count}
        </li>)}
      </ul>
    </div>}
  </section>;
}

function SettlementList({ metrics, revision, page, changePage }: {
  metrics: SellerMetrics; revision: number; page: number; changePage: (page: number) => void;
}) {
  const { from, to } = metrics;
  const settlements = useQuery({
    queryKey: [...salesKey(revision), "settlements", from, to, page],
    queryFn: ({ signal }) => fetchSettlements({ from, to }, page, signal),
    retry: false,
  });
  return <section aria-labelledby="seller-settlements" className="min-w-0 space-y-3">
    <h2 id="seller-settlements" className="text-lg font-semibold">Detalle por suborden</h2>
    {settlements.isPending ? <p role="status">Cargando subórdenes…</p>
      : settlements.isError ? <div className="space-y-2">
        <p role="alert" className="text-destructive">No pudimos cargar el detalle de las subórdenes.</p>
        <Button type="button" variant="outline" onClick={() => void settlements.refetch()}>Reintentar</Button>
      </div>
      : <>
        {settlements.data.content.length > 0 ? <SettlementTable rows={settlements.data.content} />
          : <p>{page === 0 ? "Todavía no hay subórdenes con actividad en este período." : "No hay subórdenes en esta página."}</p>}
        {(page > 0 || settlements.data.hasNext) && <nav className="flex gap-2" aria-label="Paginación de subórdenes">
          <Button variant="outline" disabled={page === 0} onClick={() => changePage(page - 1)}>Anterior</Button>
          <Button variant="outline" disabled={!settlements.data.hasNext} onClick={() => changePage(page + 1)}>Siguiente</Button>
        </nav>}
      </>}
  </section>;
}
