import type { ReactNode } from "react";

const headerStyle = "border-b px-3 py-2 font-semibold";

export function dateLabel(date: string) {
  const [year, month, day] = date.split("-");
  return `${day}/${month}/${year}`;
}

export function ReportTable({ label, headers, numericFrom = 1, children }: {
  label: string; headers: string[]; numericFrom?: number; children: ReactNode;
}) {
  return <div tabIndex={0} role="region" aria-label={label}
    className="max-h-96 max-w-full overflow-auto rounded-lg border focus-visible:outline-offset-2">
    <table className="w-full border-separate border-spacing-0 text-sm">
      <caption className="sr-only">{label}</caption>
      <thead className="sticky top-0 z-20 whitespace-nowrap bg-muted">
        <tr>
          {headers.map((heading, index) => <th key={heading} scope="col"
            className={headerStyle + (index >= numericFrom ? " text-right" : " text-left")
              + (index === 0 ? " sticky start-0 z-10 border-e bg-muted" : "")}>{heading}</th>)}
        </tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  </div>;
}

export function SalesChart({ series, grossLabel }: {
  series: Array<{ date: string; gross: string; refunds: string }>;
  grossLabel: string;
}) {
  const peak = Math.max(1, ...series.flatMap((day) => [Number(day.gross), Number(day.refunds)]));
  const first = series[0].date;
  const last = series[series.length - 1].date;
  return <>
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground" aria-hidden="true">
      <span className="inline-flex items-center gap-1.5"><span className="size-3 rounded-sm bg-primary" />{grossLabel}</span>
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
  </>;
}
