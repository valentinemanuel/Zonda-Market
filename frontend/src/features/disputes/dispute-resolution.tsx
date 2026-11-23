import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState, type FormEvent } from "react";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { Button } from "@/shared/ui/button";
import { ApiError } from "@/shared/api/http-client";
import { disputeError, disputeKey, disputeQueueKey, resolutionBalance, resolutionPath, resolveDispute,
  type DisputeDetail, type Resolution, type ResolutionOutcome, type ResolutionRequest } from "./dispute-api";
import { disputeControl, disputeDate } from "./dispute-ui";

const labels: Record<Resolution["status"], string> = {
  CERRADA: "Resolución sin devolución",
  PENDIENTE: "Devolución pendiente de confirmación",
  EN_REVISION: "Devolución en revisión",
  CONFIRMADA: "Devolución confirmada",
  RECHAZADA: "Devolución rechazada",
};

export function ResolutionSummary({ resolution }: { resolution: Resolution }) {
  return <section aria-labelledby="dispute-resolution" className="space-y-2 rounded-lg border bg-card p-4">
    <h2 id="dispute-resolution" className="text-lg font-semibold">Resolución</h2>
    <p role="status" className="font-medium">{labels[resolution.status]}</p>
    {resolution.amount && <p>Monto {formattedPrice(resolution.amount)} ARS</p>}
    <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{resolution.reason}</p>
    {resolution.providerRefundId && <p className="break-all text-sm">
      {resolution.simulated ? "Referencia de prueba" : "Referencia de Mercado Pago"} {resolution.providerRefundId}
    </p>}
    {resolution.status === "RECHAZADA" && <p className="text-sm">El reclamo volvió a mediación.</p>}
    <time className="text-sm text-muted-foreground" dateTime={resolution.updatedAt}>
      {disputeDate.format(new Date(resolution.updatedAt))}
    </time>
  </section>;
}

function cents(value: string) {
  const [whole, fraction = ""] = value.split(".");
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
}

export function ResolutionDialog({ detail, revision, saved }: {
  detail: DisputeDetail; revision: number; saved: () => void;
}) {
  const id = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const amountField = useRef<HTMLInputElement>(null);
  const attempt = useRef<{ fingerprint: string; key: string }>(null);
  const [open, setOpen] = useState(false);
  const [outcome, setOutcome] = useState<ResolutionOutcome>("SIN_REEMBOLSO");
  const [amount, setAmount] = useState("");
  const client = useQueryClient();
  const balance = useQuery({
    queryKey: ["private", "resolution-balance", revision, detail.id, detail.version],
    queryFn: ({ signal }) => resolutionBalance(detail.id, signal), enabled: open, retry: false,
  });
  const mutation = useMutation({
    mutationFn: ({ request, key }: { request: ResolutionRequest; key: string }) => resolveDispute(detail.id, request, key),
    onSuccess: () => {
      dialog.current?.close();
      saved();
      void client.invalidateQueries({ queryKey: disputeKey(revision, detail.id) });
      void client.invalidateQueries({ queryKey: disputeQueueKey(revision) });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mutation.isPending) return;
    const fields = new FormData(event.currentTarget);
    const value = amount.trim().replace(",", ".");
    if (outcome === "REEMBOLSO") {
      if (!/^\d{1,17}(?:\.\d{1,2})?$/.test(value) || cents(value) === 0n) {
        amountField.current?.setCustomValidity("Ingresá un monto positivo con hasta dos decimales.");
        amountField.current?.reportValidity();
        return;
      }
      if (balance.data && cents(value) > cents(balance.data.available)) {
        amountField.current?.setCustomValidity("El monto supera el saldo disponible.");
        amountField.current?.reportValidity();
        return;
      }
    }
    const request: ResolutionRequest = { outcome, amount: outcome === "REEMBOLSO" ? value : null,
      reason: String(fields.get("reason")).trim(), expectedVersion: detail.version };
    const fingerprint = JSON.stringify(request);
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, key: crypto.randomUUID() };
    mutation.mutate({ request, key: attempt.current.key });
  }

  async function reload() {
    amountField.current?.setCustomValidity("");
    await Promise.all([balance.refetch(), client.invalidateQueries({ queryKey: disputeKey(revision, detail.id) })]);
    mutation.reset();
  }

  return <>
    <Button type="button" className="min-h-12" onClick={() => {
      setOpen(true);
      dialog.current?.showModal();
    }}>Resolver reclamo</Button>
    <dialog ref={dialog} aria-labelledby={id}
      className="fixed inset-0 m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg overflow-auto rounded-xl border bg-card p-5 text-card-foreground backdrop:bg-slate-950/55"
      onClose={() => setOpen(false)} onCancel={(event) => { if (mutation.isPending) event.preventDefault(); }}>
      <h2 id={id} className="text-xl font-semibold">Resolver reclamo</h2>
      <div className="mt-3 rounded-lg bg-muted p-3 text-sm">
        {balance.isPending && <p role="status">Consultando saldo…</p>}
        {balance.isSuccess && <dl className="space-y-1 tabular-nums">
          <div className="flex flex-wrap justify-between gap-2"><dt>Saldo de la suborden</dt><dd>{formattedPrice(balance.data.subOrderAvailable)}</dd></div>
          <div className="flex flex-wrap justify-between gap-2"><dt>Saldo del pago</dt><dd>{formattedPrice(balance.data.paymentAvailable)}</dd></div>
          <div className="flex flex-wrap justify-between gap-2 font-semibold"><dt>Disponible para devolver</dt><dd>{formattedPrice(balance.data.available)} ARS</dd></div>
        </dl>}
        {balance.isError && <div className="space-y-2">
          <p role="alert" className="text-destructive">No pudimos consultar el saldo.</p>
          <Button type="button" variant="outline" onClick={() => void balance.refetch()}>Reintentar saldo</Button>
        </div>}
      </div>
      <form method="post" action={resolutionPath(detail.id)} aria-labelledby={id} onSubmit={submit} className="mt-4 space-y-3">
        <fieldset disabled={mutation.isPending} className="space-y-3">
          <legend className="sr-only">Datos de la resolución</legend>
          <div className="grid gap-1">
            <label htmlFor={id + "-outcome"} className="text-sm font-medium">Resultado</label>
            <select id={id + "-outcome"} name="outcome" className={disputeControl} autoFocus value={outcome}
              onChange={(event) => setOutcome(event.target.value as ResolutionOutcome)}>
              <option value="SIN_REEMBOLSO">Cerrar sin devolución</option>
              <option value="REEMBOLSO">Devolver un monto</option>
            </select>
          </div>
          {outcome === "REEMBOLSO" && <div className="grid gap-1">
            <label htmlFor={id + "-amount"} className="text-sm font-medium">Monto en pesos (obligatorio)</label>
            <input id={id + "-amount"} ref={amountField} name="amount" inputMode="decimal" required maxLength={20}
              className={disputeControl} value={amount} aria-describedby={id + "-amount-help"} onChange={(event) => {
                event.currentTarget.setCustomValidity("");
                setAmount(event.target.value);
              }} />
            <p id={id + "-amount-help"} className="text-sm text-muted-foreground">Ingresá un monto parcial o el saldo completo.</p>
          </div>}
          <div className="grid gap-1">
            <label htmlFor={id + "-reason"} className="text-sm font-medium">Motivo de la resolución (obligatorio)</label>
            <textarea id={id + "-reason"} name="reason" rows={3} className={disputeControl} required maxLength={2000} />
          </div>
        </fieldset>
        {mutation.isError && <div className="space-y-2">
          <p role="alert" className="text-destructive">{disputeError(mutation.error, "No pudimos registrar la resolución. Volvé a intentar.")}</p>
          {mutation.error instanceof ApiError && mutation.error.status === 409 && <Button type="button" variant="outline"
            onClick={() => void reload()}>Actualizar reclamo y saldo</Button>}
        </div>}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" className="min-h-12" disabled={mutation.isPending || (outcome === "REEMBOLSO" && !balance.isSuccess)}>
            {mutation.isPending ? "Registrando…" : "Confirmar resolución"}
          </Button>
          <Button type="button" variant="outline" className="min-h-12" disabled={mutation.isPending}
            onClick={() => dialog.current?.close()}>Cancelar</Button>
        </div>
      </form>
    </dialog>
  </>;
}
