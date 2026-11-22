import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { Navigate } from "react-router-dom";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { SellerHeading, SellerNavigation } from "@/features/listings/seller-workspace";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { fetchSellerOrders, sellerOrdersKey, shipSubOrder, type Carrier, type SellerOrderStatus, type SubOrder } from "./checkout-api";
import { addressText, CheckoutGroupCard } from "./checkout-summary";
import { carriers, shipmentError, subOrderLabel } from "./shipment-timeline";

const statuses: SellerOrderStatus[] = ["PREPARANDO", "DESPACHADA", "ENTREGADA", "REEMBOLSADA"];
const fieldClass = "min-h-12 w-full rounded-lg border border-input bg-background px-3 text-base focus-visible:outline-2 focus-visible:outline-primary";

export function SellerOrdersPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  return <SellerOrders key={revision} revision={revision} />;
}

function SellerOrders({ revision }: { revision: number }) {
  const [status, setStatus] = useState<SellerOrderStatus | "">("");
  const [page, setPage] = useState(0);
  const [notice, setNotice] = useState("");
  const noticeRef = useRef<HTMLParagraphElement>(null);
  const client = useQueryClient();
  const orders = useQuery({
    queryKey: [...sellerOrdersKey(revision), status, page],
    queryFn: ({ signal }) => fetchSellerOrders(page, status, signal),
    retry: false,
  });
  if (signInRequired(orders.error)) return <Navigate to="/ingresar" replace />;
  const forbidden = orders.error instanceof ApiError && orders.error.status === 403;
  const changePage = (value: number) => {
    setNotice("");
    setPage(value);
  };

  return <div className="mx-auto max-w-5xl space-y-5">
    <SellerNavigation />
    <SellerHeading title="Pedidos recibidos" />
    {!forbidden && <div className="max-w-xs space-y-1">
      <label htmlFor="seller-order-status" className="block font-medium">Estado de la suborden</label>
      <select id="seller-order-status" className={fieldClass} value={status} onChange={(event) => {
        setStatus(event.target.value as SellerOrderStatus | "");
        changePage(0);
      }}>
        <option value="">Todos los estados</option>
        {statuses.map((value) => <option key={value} value={value}>{subOrderLabel(value)}</option>)}
      </select>
    </div>}
    <p ref={noticeRef} tabIndex={-1} role="status" className="text-success empty:m-0">{notice}</p>
    {orders.isPending ? <p role="status">Cargando pedidos…</p>
      : orders.isError ? <div className="space-y-2">
        <p role="alert" className="text-destructive">
          {forbidden ? "Activá tu perfil de vendedor desde Mi cuenta." : "No pudimos cargar tus pedidos."}
        </p>
        {!forbidden && <Button variant="outline" onClick={() => void orders.refetch()}>Reintentar</Button>}
      </div>
      : <>
        {orders.data.content.length === 0 ? <p>{status || page ? "No hay subórdenes en esta página."
          : "Todavía no recibiste pedidos pagados."}</p>
          : <ul className="space-y-4">
            {orders.data.content.map((sub) => <li key={sub.id}>
              <CheckoutGroupCard group={sub.group} subOrder={sub} title={sub.group.items[0]?.title ?? sub.group.sellerName}>
                <div className="space-y-1 border-t pt-3">
                  <h3 className="font-semibold">Dirección de entrega</h3>
                  <p className="break-words text-sm">{addressText(sub.address)}</p>
                </div>
                {sub.status === "PREPARANDO" && <ShipDialog sub={sub} reload={() => void orders.refetch()}
                  saved={() => {
                    setNotice("Despacho registrado correctamente.");
                    noticeRef.current?.focus();
                    void client.invalidateQueries({ queryKey: sellerOrdersKey(revision) });
                  }} />}
              </CheckoutGroupCard>
            </li>)}
          </ul>}
        {(page > 0 || orders.data.hasNext) && <nav aria-label="Paginación de pedidos" className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={page === 0} onClick={() => changePage(page - 1)}>Anterior</Button>
          <Button variant="outline" disabled={!orders.data.hasNext} onClick={() => changePage(page + 1)}>Siguiente</Button>
        </nav>}
      </>}
  </div>;
}

function ShipDialog({ sub, saved, reload }: { sub: SubOrder; saved: () => void; reload: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const formId = useId();
  const ship = useMutation({
    mutationFn: ({ carrier, trackingCode }: { carrier: Carrier; trackingCode: string }) =>
      shipSubOrder(sub.id, carrier, trackingCode, sub.version),
    onSuccess: () => {
      dialog.current?.close();
      saved();
    },
  });
  const conflict = ship.error instanceof ApiError && ship.error.status === 409;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (ship.isPending) return;
    const fields = new FormData(event.currentTarget);
    ship.mutate({ carrier: fields.get("carrier") as Carrier, trackingCode: String(fields.get("trackingCode")) });
  }

  return <>
    <Button type="button" className="min-h-12 w-full sm:w-auto" onClick={() => {
      ship.reset();
      dialog.current?.showModal();
    }}>Registrar despacho</Button>
    <dialog ref={dialog} aria-labelledby={formId + "-title"}
      className="fixed inset-0 m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-md overflow-auto rounded-xl border bg-card p-5 text-card-foreground modal:overscroll-contain backdrop:overflow-hidden backdrop:overscroll-contain backdrop:bg-slate-950/55"
      onCancel={(event) => { if (ship.isPending) event.preventDefault(); }}>
      <h3 id={formId + "-title"} className="text-xl font-semibold">Registrar despacho</h3>
      <form method="post" action={`/api/v1/seller/sub-orders/${sub.id}/ship`} onSubmit={submit} className="mt-4 space-y-4">
        <div className="space-y-1">
          <label className="block font-medium" htmlFor={formId + "-carrier"}>Transportista</label>
          <select autoFocus required name="carrier" id={formId + "-carrier"} className={fieldClass} defaultValue="">
            <option value="" disabled>Elegí un transportista</option>
            {Object.entries(carriers).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
        <div className="space-y-1">
          <label className="block font-medium" htmlFor={formId + "-tracking"}>Código de seguimiento</label>
          <input required name="trackingCode" id={formId + "-tracking"} maxLength={64} pattern={"[A-Za-z0-9][A-Za-z0-9\\-]{0,63}"}
            className={fieldClass} autoCapitalize="none" spellCheck={false} autoCorrect="off" autoComplete="off"
            aria-describedby={formId + "-hint"} />
          <p id={formId + "-hint"} className="text-sm text-muted-foreground">Hasta 64 letras, números o guiones. Empezá con una letra o un número.</p>
        </div>
        {ship.isError && <p role="alert" className="text-destructive">
          {shipmentError(ship.error, "No pudimos registrar el despacho. Volvé a intentar.")}
        </p>}
        <div className="flex flex-wrap gap-2">
          {conflict ? <Button type="button" className="min-h-12" onClick={() => {
            dialog.current?.close();
            reload();
          }}>Actualizar pedido</Button>
            : <Button type="submit" className="min-h-12" disabled={ship.isPending}>
                {ship.isPending ? "Guardando despacho…" : "Confirmar despacho"}
              </Button>}
          <Button type="button" variant="outline" className="min-h-12" disabled={ship.isPending}
            onClick={() => dialog.current?.close()}>Cancelar</Button>
        </div>
        {ship.isPending && <p className="sr-only" role="status">Guardando despacho…</p>}
      </form>
    </dialog>
  </>;
}
