import { useState, type FormEvent } from "react";
import { profileControl } from "@/features/auth/profile-fields";
import { Button } from "@/shared/ui/button";
import type { Period } from "./seller-sales-api";

export function PeriodForm({ from, to, path, apply }: Period & { path: string; apply: (period: Period) => void }) {
  const [error, setError] = useState("");
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    const start = String(fields.get("from"));
    const end = String(fields.get("to"));
    if (start > end) {
      setError("La fecha Hasta debe ser igual o posterior a Desde.");
      (event.currentTarget.elements.namedItem("to") as HTMLInputElement).focus();
      return;
    }
    setError("");
    apply({ from: start, to: end });
  }

  return <form action={path} method="get" onSubmit={submit} aria-labelledby="sales-period"
    className="space-y-3 rounded-lg border bg-card p-4 sm:p-5">
    <h2 id="sales-period" className="text-lg font-semibold">Período</h2>
    <p id="sales-period-help" className="text-sm text-muted-foreground">Hasta 90 días corridos, en horario de Buenos Aires.</p>
    <div className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]">
      <div className="min-w-0 space-y-1">
        <label className="block text-sm font-medium" htmlFor="sales-from">Desde</label>
        <input key={from} className={profileControl + " min-h-12 w-full min-w-0 text-base"} id="sales-from" name="from"
          type="date" min="0001-01-01" max="9999-12-31" defaultValue={from} required onChange={() => setError("")}
          aria-describedby="sales-period-help" />
      </div>
      <div className="min-w-0 space-y-1">
        <label className="block text-sm font-medium" htmlFor="sales-to">Hasta</label>
        <input key={to} className={profileControl + " min-h-12 w-full min-w-0 text-base"} id="sales-to" name="to"
          type="date" min="0001-01-01" max="9999-12-31" defaultValue={to} required onChange={() => setError("")}
          aria-invalid={error ? true : undefined} aria-describedby={error ? "sales-period-error" : "sales-period-help"} />
      </div>
      <Button className="min-h-12" type="submit">Aplicar período</Button>
    </div>
    {error && <p role="alert" id="sales-period-error" className="text-sm text-destructive">{error}</p>}
  </form>;
}
