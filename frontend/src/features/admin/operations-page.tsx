import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { getSessionRevision } from "@/features/auth/session-api";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { PeriodForm } from "@/features/sales/period-form";
import { dateLabel, ReportTable, SalesChart } from "@/features/sales/report-presentation";
import type { Period } from "@/features/sales/seller-sales-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { AdminPage, LoadError } from "./admin-page";
import { downloadOperations, fetchOperations, operationsPath, type Operations } from "./operations-api";

const countFormat = new Intl.NumberFormat("es-AR");
const amountCell = "whitespace-nowrap border-b px-3 py-2 text-right tabular-nums";
const rowHeading = "sticky start-0 z-10 min-w-24 max-w-56 border-e border-b bg-card px-3 py-2 text-left font-medium [overflow-wrap:anywhere]";

export function OperationsPage() {
  return <AdminPage title="Operación global">{(revision) => <OperationsReport key={revision} revision={revision} />}</AdminPage>;
}

function OperationsReport({ revision }: { revision: number }) {
  const [params, setParams] = useSearchParams();
  const from = params.get("from");
  const to = params.get("to");
  const report = useQuery({
    queryKey: ["private", "admin-operations", revision, from, to],
    queryFn: ({ signal }) => {
      const range = new URLSearchParams();
      if (from !== null) range.set("from", from);
      if (to !== null) range.set("to", to);
      return fetchOperations(range, signal);
    },
    retry: false,
  });
  const invalidRange = report.error instanceof ApiError && report.error.code === "INVALID_ADMIN_DATE_RANGE";

  return <div className="min-w-0 space-y-5">
    {(!report.isPending || from !== null || to !== null) &&
      <PeriodForm path={operationsPath} from={from ?? report.data?.from ?? ""} to={to ?? report.data?.to ?? ""}
        apply={(period) => setParams(period)} />}
    {report.isPending && <p role="status">Cargando métricas…</p>}
    {invalidRange ? <div className="space-y-2">
      <p role="alert" className="text-sm text-destructive">Elegí un período válido de hasta 90 días corridos.</p>
      <Button type="button" variant="outline" onClick={() => setParams({})}>Restablecer período</Button>
    </div> : report.isError && <LoadError message="No pudimos cargar las métricas." retry={report.refetch} />}
    {report.isSuccess && <OperationsResults key={report.data.from + "/" + report.data.to} metrics={report.data} revision={revision} />}
  </div>;
}

function OperationsResults({ metrics, revision }: { metrics: Operations; revision: number }) {
  const summary = [
    ["GMV aprobado", formattedPrice(metrics.gmv)],
    ["Devoluciones confirmadas", formattedPrice(metrics.refunds)],
    ["Comisión neta de la plataforma", formattedPrice(metrics.platformCommission)],
    ["Órdenes pagadas", countFormat.format(metrics.paidOrders)],
    ["Reclamos iniciados", countFormat.format(metrics.disputes)],
    ["Casos actualmente abiertos", countFormat.format(metrics.openCases)],
  ];
  const hasActivity = metrics.paidOrders > 0 || metrics.refunds !== "0.00" || metrics.disputes > 0;
  return <>
    <section aria-labelledby="operations-summary" className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="operations-summary" className="text-lg font-semibold">Resumen del período</h2>
          <p role="status" className="text-sm text-muted-foreground">
            {dateLabel(metrics.from)} al {dateLabel(metrics.to)} · {metrics.currency} · Buenos Aires
          </p>
        </div>
        <ExportButton period={metrics} revision={revision} limited={metrics.breakdownLimited} />
      </div>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        {summary.map(([label, value]) => <Card key={label} className="gap-0 py-3 sm:py-4">
          <CardContent className="min-w-0 px-3 sm:px-4">
            <dl className="space-y-1">
              <dt className="text-sm font-medium text-muted-foreground">{label}</dt>
              <dd className="text-lg font-semibold tabular-nums [overflow-wrap:anywhere] sm:text-2xl">{value}</dd>
            </dl>
          </CardContent>
        </Card>)}
      </div>
    </section>
    {hasActivity ? <DailyActivity series={metrics.series} /> :
      <p role="status" className="rounded-lg border bg-card p-4">No hay actividad en este período.</p>}
    {metrics.breakdownLimited && <p role="status" className="text-sm text-muted-foreground">
      Se muestran hasta 1.000 filas por desglose. Acotá el período para descargar el CSV completo.
    </p>}
    <section className="space-y-2" aria-labelledby="operations-sellers">
      <h2 id="operations-sellers" className="text-lg font-semibold">Por vendedor</h2>
      {metrics.sellers.length === 0 ? <p className="text-sm text-muted-foreground">Sin movimientos de vendedores en el período.</p>
        : <ReportTable label="Importes por vendedor" headers={["Vendedor", "GMV", "Devoluciones", "Comisión neta"]}>
          {metrics.sellers.map((seller) => <tr key={seller.id}>
            <th scope="row" className={rowHeading}>{seller.name}</th>
            <Money value={seller.gmv} /><Money value={seller.refunds} /><Money value={seller.platformCommission} />
          </tr>)}
        </ReportTable>}
    </section>
    <section className="space-y-2" aria-labelledby="operations-categories">
      <h2 id="operations-categories" className="text-lg font-semibold">Por categoría</h2>
      <p className="text-sm text-muted-foreground">Mercadería aprobada y comisión original por línea de compra.</p>
      {metrics.categories.length === 0 ? <p className="text-sm text-muted-foreground">Sin ventas por categoría en el período.</p>
        : <ReportTable label="Importes por categoría" headers={["Categoría", "Mercadería", "Comisión original"]}>
          {metrics.categories.map((category) => <tr key={category.id}>
            <th scope="row" className={rowHeading}>{category.name}</th>
            <Money value={category.merchandise} /><Money value={category.originalCommission} />
          </tr>)}
        </ReportTable>}
    </section>
  </>;
}

function DailyActivity({ series }: { series: Operations["series"] }) {
  return <section aria-labelledby="operations-daily" className="min-w-0 space-y-3 rounded-lg border bg-card p-4 sm:p-5">
    <h2 id="operations-daily" className="text-lg font-semibold">Actividad diaria</h2>
    <SalesChart series={series.map(({ date, gmv, refunds }) => ({ date, gross: gmv, refunds }))} grossLabel="GMV aprobado" />
    <details>
      <summary className="min-h-11 cursor-pointer content-center rounded-md font-medium text-primary">Ver métricas por día</summary>
      <ReportTable label="Métricas diarias" headers={["Fecha", "GMV", "Devoluciones", "Comisión neta", "Órdenes", "Reclamos"]}>
        {series.map((day) => <tr key={day.date}>
          <th scope="row" className={rowHeading + " whitespace-nowrap"}><time dateTime={day.date}>{dateLabel(day.date)}</time></th>
          <Money value={day.gmv} /><Money value={day.refunds} /><Money value={day.platformCommission} />
          <td className={amountCell}>{countFormat.format(day.paidOrders)}</td>
          <td className={amountCell}>{countFormat.format(day.disputes)}</td>
        </tr>)}
      </ReportTable>
    </details>
  </section>;
}

function Money({ value }: { value: string }) {
  return <td className={amountCell}>{formattedPrice(value)}</td>;
}

function ExportButton({ period, revision, limited }: { period: Period; revision: number; limited: boolean }) {
  const [status, setStatus] = useState<"idle" | "pending" | "success" | "error" | "limited">("idle");
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);

  async function download() {
    if (active.current) return;
    const controller = new AbortController();
    active.current = controller;
    setStatus("pending");
    try {
      const blob = await downloadOperations({ from: period.from, to: period.to }, controller.signal);
      if (controller.signal.aborted || revision !== getSessionRevision()) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `ventas-${period.from}-${period.to}.csv`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setStatus("success");
    } catch (error) {
      if (!controller.signal.aborted && revision === getSessionRevision()) {
        setStatus(error instanceof ApiError && error.code === "SALES_EXPORT_LIMIT" ? "limited" : "error");
      }
    } finally {
      active.current = null;
    }
  }

  return <div className="space-y-1">
    <Button type="button" variant="outline" disabled={limited || status === "pending"} onClick={() => void download()}>
      Descargar CSV
    </Button>
    {status === "pending" && <p role="status" className="text-sm">Preparando CSV…</p>}
    {status === "success" && <p role="status" className="text-sm">Descarga iniciada.</p>}
    {status === "error" && <p role="alert" className="text-sm text-destructive">No pudimos descargar el CSV. Volvé a intentar.</p>}
    {status === "limited" && <p role="alert" className="text-sm text-destructive">Acotá el período para descargar el CSV completo.</p>}
  </div>;
}
