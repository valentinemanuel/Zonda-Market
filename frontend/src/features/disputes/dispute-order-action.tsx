import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { orderKey, type SubOrder } from "@/features/checkout/checkout-api";
import { disputeActive, disputeError, disputeOpenPath, disputePath, disputeStatuses, openDispute } from "./dispute-api";
import { disputeControl } from "./dispute-ui";

const deliveryWindowMs = 7 * 24 * 60 * 60 * 1000;

export function DisputeLink({ dispute }: { dispute: NonNullable<SubOrder["dispute"]> }) {
  return <div className="space-y-2 border-t pt-3">
    <p className="text-sm font-medium">Reclamo {disputeStatuses[dispute.status].toLowerCase()}</p>
    {disputeActive(dispute.status) && <p className="text-sm text-muted-foreground">
      El despacho y la confirmación de entrega quedan en pausa durante el reclamo.
    </p>}
    <Button asChild variant="outline" className="min-h-12"><Link to={disputePath(dispute.id)}>Ver reclamo</Link></Button>
  </div>;
}

export function BuyerDisputeAction({ sub, orderId, paid, serverNow, revision }: {
  sub: SubOrder; orderId: string; paid: boolean; serverNow: string; revision: number;
}) {
  if (sub.dispute) return <DisputeLink dispute={sub.dispute} />;
  const delivered = sub.shipment?.deliveredAt;
  const withinWindow = sub.status === "ENTREGADA" && delivered
    && Date.parse(serverNow) <= Date.parse(delivered) + deliveryWindowMs;
  if (!paid || !(sub.status === "PREPARANDO" || sub.status === "DESPACHADA" || withinWindow)) return null;
  return <OpenDisputeForm sub={sub} orderId={orderId} revision={revision} />;
}

function OpenDisputeForm({ sub, orderId, revision }: { sub: SubOrder; orderId: string; revision: number }) {
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  const reason = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const navigate = useNavigate();
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: ({ reason, description }: { reason: string; description: string }) =>
      openDispute(sub.id, reason, description, sub.version),
    onSuccess: (dispute) => {
      void client.invalidateQueries({ queryKey: orderKey(revision, orderId) });
      navigate(disputePath(dispute.id));
    },
  });
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;

  useEffect(() => {
    if (expanded) reason.current?.focus();
  }, [expanded]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mutation.isPending) return;
    const fields = new FormData(event.currentTarget);
    mutation.mutate({ reason: String(fields.get("reason")).trim(), description: String(fields.get("description")).trim() });
  }

  async function reload() {
    await client.invalidateQueries({ queryKey: orderKey(revision, orderId) });
    mutation.reset();
  }

  return <section className="space-y-3 border-t pt-3">
    <Button ref={trigger} type="button" variant="outline" className="min-h-12" aria-expanded={expanded} aria-controls={id}
      disabled={mutation.isPending} onClick={() => setExpanded(!expanded)}>Abrir un reclamo</Button>
    <div id={id} hidden={!expanded}>
      {expanded && <form method="post" action={disputeOpenPath(sub.id)} onSubmit={submit}
        aria-labelledby={id + "-heading"} className="space-y-3">
        <h3 id={id + "-heading"} className="font-semibold">Nuevo reclamo privado</h3>
        <p className="text-sm text-muted-foreground">Lo pueden leer el vendedor de esta suborden y administración.</p>
        <fieldset disabled={mutation.isPending} className="space-y-3">
          <legend className="sr-only">Datos del reclamo</legend>
          <label className="grid gap-1 text-sm font-medium">Motivo (obligatorio)
            <input ref={reason} name="reason" className={disputeControl} required maxLength={120} />
          </label>
          <label className="grid gap-1 text-sm font-medium">Qué pasó (obligatorio)
            <textarea name="description" className={disputeControl} required maxLength={2000} rows={4} />
          </label>
        </fieldset>
        {mutation.isError && <p role="alert" className="text-destructive">
          {disputeError(mutation.error, "No pudimos abrir el reclamo. Volvé a intentar.")}
        </p>}
        <div className="flex flex-wrap gap-2">
          {conflict ? <Button type="button" className="min-h-12" onClick={() => void reload()}>Actualizar pedido</Button>
            : <Button type="submit" className="min-h-12" disabled={mutation.isPending}>
                {mutation.isPending ? "Abriendo reclamo…" : "Confirmar reclamo"}
              </Button>}
          <Button type="button" variant="outline" className="min-h-12" disabled={mutation.isPending} onClick={() => {
            setExpanded(false);
            mutation.reset();
            trigger.current?.focus();
          }}>Cancelar</Button>
        </div>
      </form>}
    </div>
  </section>;
}
