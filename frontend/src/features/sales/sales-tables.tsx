import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { subOrderLabel } from "@/features/checkout/shipment-timeline";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { sellerOrdersPath } from "@/features/listings/seller-workspace";
import type { Amounts, SalesDay, Settlement } from "./seller-sales-api";

const amountColumns: ReadonlyArray<[keyof Amounts, string]> = [
  ["gross", "Ventas brutas"], ["commission", "Comisiones"], ["refunds", "Devoluciones"],
  ["commissionReversed", "Comisión revertida"], ["net", "Neto estimado"],
];
const headerStyle = "border-b px-3 py-2 font-semibold";
const cellStyle = "whitespace-nowrap border-b px-3 py-2";
const rowHeader = "sticky start-0 z-10 border-e bg-card font-medium";
const dateFormat = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "short", timeZone: "America/Argentina/Buenos_Aires",
});

export function dateLabel(date: string) {
  const [year, month, day] = date.split("-");
  return `${day}/${month}/${year}`;
}

function SalesTable({ label, headers, children }: { label: string; headers: string[]; children: ReactNode }) {
  return <div tabIndex={0} role="region" aria-label={label}
    className="max-h-96 max-w-full overflow-auto rounded-lg border focus-visible:outline-offset-2">
    <table className="w-full border-separate border-spacing-0 text-sm">
      <caption className="sr-only">{label}</caption>
      <thead className="sticky top-0 z-20 whitespace-nowrap bg-muted">
        <tr>
          {headers.map((heading, index) => <th key={heading} scope="col"
            className={headerStyle + " text-left" + (index === 0 ? " sticky start-0 z-10 border-e bg-muted" : "")}>{heading}</th>)}
          {amountColumns.map(([key, heading]) => <th key={key} scope="col" className={headerStyle + " text-right"}>
            {heading}
          </th>)}
        </tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  </div>;
}

function AmountCells({ amounts }: { amounts: Amounts }) {
  return amountColumns.map(([key]) => <td key={key}
    className={cellStyle + " text-right tabular-nums" + (key === "net" ? " font-semibold" : "")}>
    {formattedPrice(amounts[key])}
  </td>);
}

export function SettlementTable({ rows }: { rows: Settlement[] }) {
  return <SalesTable label="Actividad por suborden" headers={["Suborden", "Último movimiento", "Estado"]}>
    {rows.map((entry) => <tr key={entry.subOrderId}>
      <th scope="row" className={cellStyle + " " + rowHeader + " text-left"}>
        <Link to={sellerOrdersPath + "?suborden=" + encodeURIComponent(entry.subOrderId)}
          aria-label={"Ver suborden " + entry.subOrderId} className={profileLink + " inline-flex min-h-11 items-center"}>
          {entry.subOrderId.slice(0, 8)}
        </Link>
      </th>
      <td className={cellStyle}><time dateTime={entry.lastActivityAt}>{dateFormat.format(new Date(entry.lastActivityAt))}</time></td>
      <td className={cellStyle}>{subOrderLabel(entry.status)}</td>
      <AmountCells amounts={entry} />
    </tr>)}
  </SalesTable>;
}

export function SalesSeries({ series }: { series: SalesDay[] }) {
  const peak = Math.max(1, ...series.flatMap((day) => [Number(day.gross), Number(day.refunds)]));
  const first = series[0].date;
  const last = series[series.length - 1].date;
  return <section aria-labelledby="daily-sales" className="min-w-0 space-y-3 rounded-lg border bg-card p-4 sm:p-5">
    <h2 id="daily-sales" className="text-lg font-semibold">Actividad diaria</h2>
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground" aria-hidden="true">
      <span className="inline-flex items-center gap-1.5"><span className="size-3 rounded-sm bg-primary" />Ventas brutas</span>
      <span className="inline-flex items-center gap-1.5"><span className="size-3 rounded-sm bg-orange-700" />Devoluciones</span>
    </div>
    <div className="space-y-1" aria-hidden="true">
      <div className="flex h-28 items-end gap-px border-b bg-muted/40 px-1 sm:h-36">
        {series.map((day) => <div key={day.date} className="flex h-full min-w-0 flex-1 items-end justify-center">
          <span className="w-1/2 max-w-6 rounded-t-sm bg-primary" style={{ height: Number(day.gross) / peak * 100 + "%" }} />
          <span className="w-1/2 max-w-6 rounded-t-sm bg-orange-700" style={{ height: Number(day.refunds) / peak * 100 + "%" }} />
        </div>)}
      </div>
      <div className="flex justify-between text-xs tabular-nums text-muted-foreground">
        <span>{dateLabel(first)}</span>{last !== first && <span>{dateLabel(last)}</span>}
      </div>
    </div>
    <details>
      <summary className="min-h-11 cursor-pointer content-center rounded-md font-medium text-primary">Ver importes por día</summary>
      <SalesTable label="Importes diarios" headers={["Fecha"]}>
        {series.map((day) => <tr key={day.date}>
          <th scope="row" className={cellStyle + " " + rowHeader + " text-left"}>
            <time dateTime={day.date}>{dateLabel(day.date)}</time>
          </th>
          <AmountCells amounts={day} />
        </tr>)}
      </SalesTable>
    </details>
  </section>;
}
