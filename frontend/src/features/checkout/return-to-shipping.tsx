import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, TriangleAlert } from "lucide-react";
import { useEffect, useRef } from "react";
import { Link, useBlocker } from "react-router-dom";
import { getSessionRevision } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { activeOrderKey, cancelReservation, orderKey, ordersKey, type Order } from "./checkout-api";

export function ReturnToShipping({ order, revision }: { order: Order; revision: number }) {
  const client = useQueryClient();
  const dialog = useRef<HTMLDialogElement>(null);
  const trigger = useRef<HTMLAnchorElement>(null);
  const active = order.status === "RESERVADA" || order.status === "PAGO_PENDIENTE";
  const blocker = useBlocker(({ nextLocation }) => active && nextLocation.pathname === "/checkout");
  const blocked = blocker.state === "blocked";
  const mutation = useMutation({
    mutationFn: () => cancelReservation(order.id),
  });

  useEffect(() => {
    const element = dialog.current;
    if (!element || !blocked) return;
    element.showModal();
    return () => {
      element.close();
      trigger.current?.focus();
    };
  }, [blocked]);

  async function confirm() {
    if (blocker.state !== "blocked" || mutation.isPending) return;
    try {
      await mutation.mutateAsync();
    } catch {
      return;
    }
    if (getSessionRevision() !== revision) return;
    await client.cancelQueries({ queryKey: activeOrderKey(revision) });
    if (getSessionRevision() !== revision) return;
    client.setQueryData(activeOrderKey(revision), null);
    void client.invalidateQueries({ queryKey: orderKey(revision, order.id) });
    void client.invalidateQueries({ queryKey: ordersKey(revision) });
    void client.invalidateQueries({ queryKey: ["private", "cart", revision] });
    void client.invalidateQueries({ queryKey: ["catalog"] });
    void client.invalidateQueries({ queryKey: ["listing-public"] });
    blocker.proceed();
  }

  function stay() {
    if (blocker.state !== "blocked" || mutation.isPending) return;
    mutation.reset();
    blocker.reset();
  }

  return <>
    <Button asChild variant="outline" className="min-h-11">
      <Link ref={trigger} to="/checkout"><ArrowLeft aria-hidden="true" />Volver a Envío</Link>
    </Button>
    <dialog ref={dialog} aria-labelledby="return-shipping-title" aria-describedby="return-shipping-description"
      className="m-auto w-[calc(100%_-_2rem)] max-w-md rounded-panel border bg-card p-5 text-foreground shadow-float backdrop:bg-brand-ink/65 sm:p-6"
      onCancel={(event) => { event.preventDefault(); stay(); }}>
      <TriangleAlert aria-hidden="true" className="mb-3 size-10 rounded-lg bg-accent p-2 text-primary" />
      <h2 id="return-shipping-title" className="text-xl font-bold">¿Volver a Envío?</h2>
      <p id="return-shipping-description" className="mt-2 text-sm leading-relaxed text-muted-foreground">
        Se cancelará el tiempo de pago y se liberará la reserva. Tus productos seguirán en el carrito.
        Tendrás que cotizar y reservar de nuevo para pagar.
      </p>
      {mutation.isError && <p role="alert" className="mt-3 rounded-lg bg-destructive-soft p-3 text-sm text-destructive">
        {mutation.error instanceof ApiError && mutation.error.status === 409
          ? "La orden ya tiene un pago en curso o un resultado definitivo. Volvé al paso de pago para consultar su estado."
          : "No pudimos confirmar la cancelación. Volvé a intentar."}
      </p>}
      <div className="mt-5 flex flex-wrap gap-2">
        <Button type="button" variant="outline" autoFocus aria-disabled={mutation.isPending} onClick={stay}>Seguir en Pago</Button>
        <Button type="button" aria-disabled={mutation.isPending} onClick={() => void confirm()}>
          {mutation.isPending ? "Liberando reserva…" : "Cancelar reserva y volver"}
        </Button>
      </div>
    </dialog>
  </>;
}
