import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { subOrderLabel } from "@/features/checkout/shipment-timeline";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { sellerOrdersPath } from "@/features/listings/seller-workspace";
import { dateLabel, ReportTable, SalesChart } from "./report-presentation";
import type { Amounts, SalesDay, Settlement } from "./seller-sales-api";

const amountColumns: ReadonlyArray<[keyof Amounts, string]> = [
  ["gross", "Ventas brutas"], ["commission", "Comisiones"], ["refunds", "Devoluciones"],
  ["commissionReversed", "Comisión revertida"], ["net", "Neto estimado"],
];
const cellStyle = "whitespace-nowrap border-b px-3 py-2";
const rowHeader = "sticky start-0 z-10 border-e bg-card font-medium";
const dateFormat = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "short", timeZone: "America/Argentina/Buenos_Aires",
});

function SalesTable({ label, headers, children }: { label: string; headers: string[]; children: ReactNode }) {
  return <ReportTable label={label} headers={[...headers, ...amountColumns.map(([, heading]) => heading)]}
    numericFrom={headers.length}>{children}</ReportTable>;
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
  return <section aria-labelledby="daily-sales" className="min-w-0 space-y-3 rounded-lg border bg-card p-4 sm:p-5">
    <h2 id="daily-sales" className="text-lg font-semibold">Actividad diaria</h2>
    <SalesChart series={series} grossLabel="Ventas brutas" />
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
