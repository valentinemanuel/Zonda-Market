import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { AdminPage, LoadError } from "./admin-page";
import { auditPath, auditQueryKey, fetchIncidents, incidentsQueryKey, recoverIncident, type PaymentIncident } from "./audit-api";
import { AuditPagination, auditControl, auditDate, results } from "./audit-ui";

const kinds: Record<PaymentIncident["kind"], string> = {
  WEBHOOK: "Notificación de pago", COMPENSATION: "Compensación de pago", DISPUTE_REFUND: "Devolución de reclamo",
};
const recoveryErrors: Record<string, string> = {
  RECOVERY_COOLDOWN: "Esperá un minuto entre solicitudes de recuperación.",
  INCIDENT_NOT_RECOVERABLE: "La incidencia cambió y ya no admite una recuperación.",
  RECOVERY_KEY_REUSED: "La solicitud original usó otro motivo. Volvé a abrir la confirmación.",
};

export function PaymentIncidentsPage() {
  return <AdminPage title="Incidencias de pago">{(revision) => <Incidents key={revision} revision={revision} />}</AdminPage>;
}

function Incidents({ revision }: { revision: number }) {
  const [page, setPage] = useState(0);
  const [confirmation, setConfirmation] = useState<{ item: PaymentIncident; key: string }>();
  const [acceptedTrace, setAcceptedTrace] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    if (confirmation && !dialog.current?.open) dialog.current?.showModal();
  }, [confirmation]);
  const client = useQueryClient();
  const query = useQuery({
    queryKey: [...incidentsQueryKey(revision), page],
    queryFn: ({ signal }) => fetchIncidents(page, signal),
    retry: false,
    refetchInterval: (entry) => entry.state.data?.items.some((item) => item.status === "EN_COLA") ? 5_000 : false,
  });
  const recovery = useMutation({
    mutationFn: ({ id, reason, key }: { id: string; reason: string; key: string }) => recoverIncident(id, reason, key),
    onSuccess: ({ traceId }) => {
      dialog.current?.close();
      setAcceptedTrace(traceId);
      void client.invalidateQueries({ queryKey: incidentsQueryKey(revision) });
      void client.invalidateQueries({ queryKey: auditQueryKey(revision) });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!confirmation || recovery.isPending) return;
    const value = new FormData(event.currentTarget).get("reason");
    recovery.mutate({ id: confirmation.item.id, reason: String(value).trim(), key: confirmation.key });
  }

  const errorCode = recovery.error instanceof ApiError ? recovery.error.code : null;
  return <section className="space-y-4">
    {acceptedTrace && <p role="status" className="rounded-lg border bg-card p-3">
      Recuperación solicitada. <Link className="underline" to={`${auditPath}?traceId=${acceptedTrace}`}>Consultar la traza de recuperación</Link>
    </p>}
    {query.isPending && <p role="status">Cargando incidencias…</p>}
    {query.isError && <LoadError message="No pudimos cargar las incidencias." retry={query.refetch} />}
    {query.data && <>
      {query.data.items.length > 0 ? <ul role="list" className="space-y-3">
        {query.data.items.map((item) => <li key={item.id} className="grid gap-3 rounded-xl border bg-card p-4 sm:grid-cols-[1fr_auto]">
          <div className="min-w-0 space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="font-semibold">{kinds[item.kind]}</h2>
              <span className="rounded-md bg-muted px-2 py-1 text-sm font-medium">{results[item.status] ?? item.status}</span>
            </div>
            <p className="break-all text-sm text-muted-foreground">{item.id}</p>
            <p className="text-sm text-muted-foreground">{item.attempts} {item.attempts === 1 ? "intento" : "intentos"} ·{" "}
              <time dateTime={item.updatedAt}>{auditDate.format(new Date(item.updatedAt))}</time>
            </p>
            {item.reconcileAvailableAt && Date.parse(item.reconcileAvailableAt) > Date.now() &&
              <p className="text-sm">Próxima solicitud desde las {auditDate.format(new Date(item.reconcileAvailableAt))} (Buenos Aires).</p>}
            <Link className="text-sm underline underline-offset-2" to={`${auditPath}?traceId=${item.traceId}`}>Consultar traza original</Link>
          </div>
          {item.canReconcile && <Button type="button" variant="outline" disabled={recovery.isPending} className="self-start"
            onClick={() => {
              recovery.reset();
              setConfirmation({ item, key: crypto.randomUUID() });
            }}>Recuperar incidencia</Button>}
        </li>)}
      </ul> : <p role="status">No hay incidencias en esta página.</p>}
      <AuditPagination page={page} hasNext={query.data.hasNext} changePage={setPage} />
    </>}
    <dialog ref={dialog} aria-labelledby="recovery-title"
      className="m-auto max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg overscroll-contain rounded-xl border bg-card p-5 text-card-foreground backdrop:overscroll-contain backdrop:overflow-hidden backdrop:bg-black/50">
      {confirmation && <form key={confirmation.key} onSubmit={submit} className="space-y-4">
        <h2 id="recovery-title" className="text-xl font-semibold">Recuperar incidencia</h2>
        <p className="text-sm">Se encolará una consulta de conciliación para {kinds[confirmation.item.kind].toLowerCase()}.</p>
        <label className="grid gap-1 text-sm font-medium">
          Motivo de recuperación
          <textarea className={auditControl} name="reason" rows={3} required maxLength={500} autoFocus
            onChange={(event) => event.currentTarget.setCustomValidity(event.currentTarget.value.trim() ? "" : "Ingresá un motivo.")} />
        </label>
        {recovery.isPending && <p role="status">Solicitando recuperación…</p>}
        {recovery.isError && <p role="alert" className="text-sm text-destructive">
          {(errorCode && recoveryErrors[errorCode]) || "No pudimos confirmar la recuperación. Podés volver a intentar."}
        </p>}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => dialog.current?.close()}>Cancelar</Button>
          <Button type="submit" disabled={recovery.isPending}>Confirmar recuperación</Button>
        </div>
      </form>}
    </dialog>
  </section>;
}
