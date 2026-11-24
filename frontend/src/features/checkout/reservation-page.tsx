import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, CheckCircle2, Clock3, PackageCheck, TriangleAlert } from "lucide-react";
import { useEffect, useState, useSyncExternalStore } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { cartKey, signInRequired } from "@/features/cart/cart-api";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { activeOrderKey, checkoutPath, directOrderQuantity, fetchOrder, orderKey, ordersKey, orderStatus, remainingMs, type Order, type Refund } from "./checkout-api";
import { addressText, CheckoutHeading, CheckoutSummary, checkoutSurface } from "./checkout-summary";
import { PaymentMethods } from "./payment-methods";
import { PurchaseReceipt } from "./purchase-receipt";
import { DeliveryAction } from "./delivery-action";
import { ReviewAction } from "@/features/reviews/review-action";
import { BuyerDisputeAction } from "@/features/disputes/dispute-order-action";
import { disputeActive } from "@/features/disputes/dispute-api";
import { ReturnToShipping } from "./return-to-shipping";
import { subOrderLabel } from "./shipment-timeline";

export function ReservationPage() {
  return <OrderCheckout returned={false} />;
}

export function CheckoutResultPage() {
  return <OrderCheckout returned />;
}

function OrderCheckout({ returned }: { returned: boolean }) {
  const { id = "" } = useParams();
  const { hash, search } = useLocation();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const client = useQueryClient();
  const order = useQuery({
    queryKey: orderKey(revision, id), queryFn: ({ signal }) => fetchOrder(id, signal), retry: false,
    refetchInterval: (query) =>
      query.state.data?.refunds?.some((refund) => refund.status === "PENDIENTE") ? 5000 : false,
  });
  const { data, refetch } = order;
  const paymentReview = settled(data?.status) && new URLSearchParams(search).get("paso") === "pago";
  const heading = <CheckoutHeading title={paymentReview ? "Detalle del pago" : returned ? "Resultado del pago" : settled(data?.status) ? "Detalle de la orden" : "Tu reserva"}
    step={!paymentReview && settled(data?.status) ? "Confirmación" : "Pago"}
    quietTitle={data?.status === "PAGADA" && !paymentReview} />;
  const loadedId = data?.id;
  useEffect(() => {
    if (loadedId && /^#suborden-[0-9a-f-]{36}$/i.test(hash)) {
      document.getElementById(hash.slice(1))?.focus();
    }
  }, [hash, loadedId]);
  useEffect(() => {
    if (data?.status === "EXPIRADA" || data?.status === "CANCELADA" || settled(data?.status)) {
      void client.invalidateQueries({ queryKey: cartKey(revision) });
      void client.invalidateQueries({ queryKey: activeOrderKey(revision) });
      void client.invalidateQueries({ queryKey: ordersKey(revision) });
      void client.invalidateQueries({ queryKey: ["catalog"] });
      void client.invalidateQueries({ queryKey: ["listing-public"] });
    }
  }, [client, data?.status, revision]);
  useEffect(() => {
    if (!data || !running(data.status)) return;
    const timer = window.setTimeout(() => void refetch(), Math.max(100, remainingMs(data)));
    return () => window.clearTimeout(timer);
  }, [data, refetch]);

  return <div className="mx-auto max-w-7xl space-y-5">
    <div className="space-y-2">
      <div className="flex flex-wrap gap-x-5">
        <Link to="/carrito" className={profileLink + " text-sm"}>Volver al carrito</Link>
        <Link to="/cuenta/ordenes" className={profileLink + " text-sm"}>Mis órdenes</Link>
      </div>
      {returned && (data === undefined || !settled(data.status)) && <Link to={"/checkout/reserva/" + encodeURIComponent(id)} className={profileLink + " text-sm"}>
        Ver la reserva
      </Link>}
      {data && running(data.status) && <ReturnToShipping key={data.id} order={data} revision={revision} />}
      {data && settled(data.status) && (paymentReview
        ? <Button asChild variant="outline"><Link to={"/checkout/resultado/" + encodeURIComponent(id)}>Ver confirmación <ArrowRight aria-hidden="true" /></Link></Button>
        : <Button asChild variant="outline"><Link to={"/checkout/reserva/" + encodeURIComponent(id) + "?paso=pago"}><ArrowLeft aria-hidden="true" />Volver a Pago</Link></Button>)}
    </div>
    {!data && heading}
    {order.isPending ? <p role="status">Cargando reserva…</p>
      : signInRequired(order.error) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToOrder: id, checkoutResult: returned, orderHash: hash }}>Iniciar sesión para ver la reserva</Link>
        </Button>
      : order.isError ? <div className="space-y-2">
          <p role="alert" className="text-destructive">{order.error instanceof ApiError && order.error.status === 404
            ? "No encontramos esa reserva." : "No pudimos cargar la reserva."}</p>
          <Button variant="outline" onClick={() => void order.refetch()}>Reintentar</Button>
        </div>
      : <Reservation key={revision + ":" + order.data.id} order={order.data} revision={revision} returned={returned}
          paymentReview={paymentReview} heading={heading} />}
  </div>;
}

function Reservation({ order, revision, returned, paymentReview, heading }: {
  order: Order; revision: number; returned: boolean; paymentReview: boolean; heading: React.ReactNode;
}) {
  const [seconds, setSeconds] = useState(() => secondsLeft(order));
  useEffect(() => {
    if (!running(order.status)) return;
    const interval = window.setInterval(() => setSeconds(secondsLeft(order)), 250);
    return () => window.clearInterval(interval);
  }, [order]);
  const expired = order.status === "EXPIRADA";
  const active = running(order.status);
  const cancelled = order.status === "CANCELADA";
  const paid = settled(order.status);
  const receipt = order.status === "PAGADA" && !paymentReview;
  const remaining = Math.min(seconds, secondsLeft(order));
  const time = Math.floor(remaining / 60) + ":" + String(remaining % 60).padStart(2, "0");
  const address = order.subOrders[0]?.address;

  return <CheckoutSummary groups={order.subOrders.map(({ group }) => group)} total={order.total} subOrders={order.subOrders}
    totalLabel={order.status === "PAGADA" ? "Total pagado" : active ? undefined : "Total original"}
    thumbnails={order.thumbnails} time={active && remaining > 0 ? time : undefined}
    actions={paymentReview ? undefined : (sub) => <>
      {!disputeActive(sub.dispute?.status) && <DeliveryAction sub={sub} orderId={order.id} revision={revision} />}
      <ReviewAction sub={sub} orderId={order.id} revision={revision} />
      <BuyerDisputeAction sub={sub} orderId={order.id} revision={revision}
        paid={order.status === "PAGADA" || order.status === "REEMBOLSO_PARCIAL"} serverNow={order.serverNow} />
    </>}
    beforeGroups={<>
    {heading}
    {receipt && <PurchaseReceipt order={order} />}
    <section aria-label="Estado de tu pedido" className={checkoutSurface + " rounded-panel border p-4 sm:p-6"}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex min-w-0 items-start gap-3">
          {paid ? <CheckCircle2 aria-hidden="true" className="size-11 shrink-0 rounded-xl bg-emerald-50 p-2.5 text-success" />
            : active ? <Clock3 aria-hidden="true" className="size-11 shrink-0 rounded-xl bg-accent p-2.5 text-primary" />
            : <TriangleAlert aria-hidden="true" className="size-11 shrink-0 rounded-xl bg-accent p-2.5 text-primary" />}
          <div className="min-w-0">
            <p role="status" className={`font-wordmark text-xl font-extrabold ${paid ? "text-success" : "text-foreground"}`}>
              {expired ? "La reserva venció." : active && remaining === 0 ? "Comprobando vencimiento…"
                : order.status === "RESERVADA" ? "Productos reservados" : orderStatus(order.status)}
            </p>
            <p className="break-words text-sm text-muted-foreground">Orden {order.id}</p>
          </div>
        </div>
      </div>
      {paid && !paymentReview && <div className="mt-4 grid gap-2 sm:grid-cols-2">
        {order.subOrders.map((sub) => <a key={sub.id} href={"#suborden-" + sub.id}
          className="flex min-w-0 items-center gap-3 rounded-lg border bg-background p-3 hover:border-primary">
          <PackageCheck aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <div className="min-w-0">
            <p className="break-words text-sm font-bold">{sub.group.sellerName}</p>
            <p className="text-sm text-muted-foreground">Estado: {subOrderLabel(sub.status).toLocaleLowerCase("es-AR")}</p>
          </div>
        </a>)}
      </div>}
      {(expired || cancelled || order.status === "FALLIDA") && <div className="mt-4 space-y-2">
        <p className="text-sm">{order.directSkuId ? "Volvé a Envío para iniciar la compra de nuevo." : "Tus productos siguen en el carrito."}</p>
        <Button asChild><Link to={checkoutPath(order.directSkuId, directOrderQuantity(order))}>Volver a Envío</Link></Button>
      </div>}
    </section>
    <PaymentMethods order={order} eligible={active && remaining > 0} revision={revision} returned={returned} />
    {order.refunds && order.refunds.length > 0 && <section aria-labelledby="refunds-heading"
      className="space-y-2 rounded-lg border bg-card p-4">
      <h2 id="refunds-heading" className="text-lg font-semibold">Devoluciones</h2>
      <ul className="divide-y">
        {order.refunds.map((refund) => <li key={refund.id} className="flex flex-wrap justify-between gap-2 py-2">
          <div>
            <p className="font-medium">{refund.reason === "TARDIO" ? "Cobro fuera de plazo" : "Cobro duplicado"}</p>
            <p role="status">{refundStatus[refund.status]}</p>
          </div>
          <p className="tabular-nums">{formattedPrice(refund.amount)}</p>
        </li>)}
      </ul>
    </section>}
    {address && !receipt && <section aria-label="Dirección de entrega" className={checkoutSurface + " space-y-1 rounded-panel border p-4"}>
      <h2 className="text-lg font-semibold">Dirección de entrega</h2>
      <p className="break-words">{addressText(address)}</p>
    </section>}
    </>} />;
}

const refundStatus: Record<Refund["status"], string> = {
  PENDIENTE: "Devolución pendiente.",
  CONFIRMADA: "Devolución confirmada.",
  EN_REVISION: "Devolución en revisión.",
};

function secondsLeft(order: Order) {
  return Math.max(0, Math.ceil(remainingMs(order) / 1000));
}

function running(status: string) {
  return status === "RESERVADA" || status === "PAGO_PENDIENTE";
}

function settled(status: string | undefined) {
  return status === "PAGADA" || status === "REEMBOLSO_PARCIAL" || status === "REEMBOLSADA";
}
