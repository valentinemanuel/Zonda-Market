import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, useSyncExternalStore } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { activeOrderKey, fetchOrder, orderKey, ordersKey, orderStatus, remainingMs, type Order, type Refund } from "./checkout-api";
import { addressText, CheckoutSummary } from "./checkout-summary";
import { PaymentMethods } from "./payment-methods";
import { DeliveryAction } from "./delivery-action";
import { ReviewAction } from "@/features/reviews/review-action";
import { BuyerDisputeAction } from "@/features/disputes/dispute-order-action";
import { disputeActive } from "@/features/disputes/dispute-api";

export function ReservationPage() {
  return <OrderCheckout returned={false} />;
}

export function CheckoutResultPage() {
  return <OrderCheckout returned />;
}

function OrderCheckout({ returned }: { returned: boolean }) {
  const { id = "" } = useParams();
  const { hash } = useLocation();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const client = useQueryClient();
  const order = useQuery({
    queryKey: orderKey(revision, id), queryFn: ({ signal }) => fetchOrder(id, signal), retry: false,
    refetchInterval: (query) =>
      query.state.data?.refunds?.some((refund) => refund.status === "PENDIENTE") ? 5000 : false,
  });
  const { data, refetch } = order;
  const loadedId = data?.id;
  useEffect(() => {
    if (loadedId && /^#suborden-[0-9a-f-]{36}$/i.test(hash)) {
      document.getElementById(hash.slice(1))?.focus();
    }
  }, [hash, loadedId]);
  useEffect(() => {
    if (data?.status === "EXPIRADA" || data?.status === "PAGADA") {
      void client.invalidateQueries({ queryKey: ["private", "cart", revision] });
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

  return <div className="mx-auto max-w-5xl space-y-6">
    <div className="space-y-2">
      <div className="flex flex-wrap gap-x-5">
        <Link to="/carrito" className={profileLink + " text-sm"}>Volver al carrito</Link>
        <Link to="/mis-ordenes" className={profileLink + " text-sm"}>Mis órdenes</Link>
      </div>
      <h1 className="text-3xl font-semibold">
        {returned ? "Resultado del pago" : data?.status === "PAGADA" ? "Detalle de la orden" : "Tu reserva"}
      </h1>
      {returned && <Link to={"/checkout/reserva/" + encodeURIComponent(id)} className={profileLink + " text-sm"}>
        Ver la reserva
      </Link>}
    </div>
    {order.isPending ? <p role="status">Cargando reserva…</p>
      : signInRequired(order.error) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToOrder: id, checkoutResult: returned, orderHash: hash }}>Iniciar sesión para ver la reserva</Link>
        </Button>
      : order.isError ? <div className="space-y-2">
          <p role="alert" className="text-destructive">{order.error instanceof ApiError && order.error.status === 404
            ? "No encontramos esa reserva." : "No pudimos cargar la reserva."}</p>
          <Button variant="outline" onClick={() => void order.refetch()}>Reintentar</Button>
        </div>
      : <Reservation key={revision + ":" + order.data.id} order={order.data} revision={revision} returned={returned} />}
  </div>;
}

function Reservation({ order, revision, returned }: { order: Order; revision: number; returned: boolean }) {
  const [seconds, setSeconds] = useState(() => secondsLeft(order));
  useEffect(() => {
    if (!running(order.status)) return;
    const interval = window.setInterval(() => setSeconds(secondsLeft(order)), 250);
    return () => window.clearInterval(interval);
  }, [order]);
  const expired = order.status === "EXPIRADA";
  const active = running(order.status);
  const remaining = Math.min(seconds, secondsLeft(order));
  const time = Math.floor(remaining / 60) + ":" + String(remaining % 60).padStart(2, "0");
  const address = order.subOrders[0]?.address;

  return <div className="space-y-5">
    <div className="space-y-2 rounded-lg border bg-card p-4">
      <p role="status" className={expired ? "font-semibold text-destructive" : "font-semibold text-success"}>
        {expired ? "La reserva venció." : active && remaining === 0 ? "Comprobando vencimiento…"
          : order.status === "RESERVADA" ? "Productos reservados" : orderStatus(order.status)}
      </p>
      {active && remaining > 0 && <p>Tiempo restante <span role="timer" className="font-semibold tabular-nums">{time}</span></p>}
      <p className="break-words text-sm text-muted-foreground">Orden {order.id}</p>
      {(expired || order.status === "FALLIDA") && <div className="space-y-2">
        <p className="text-sm">Tus productos siguen en el carrito.</p>
        <Button asChild><Link to="/checkout">Volver a cotizar</Link></Button>
      </div>}
    </div>
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
    {address && <section aria-label="Dirección de entrega" className="space-y-1 rounded-lg border bg-card p-4">
      <h2 className="text-lg font-semibold">Dirección de entrega</h2>
      <p className="break-words">{addressText(address)}</p>
    </section>}
    <CheckoutSummary groups={order.subOrders.map(({ group }) => group)} total={order.total} subOrders={order.subOrders}
      actions={(sub) => <>
        {!disputeActive(sub.dispute?.status) && <DeliveryAction sub={sub} orderId={order.id} revision={revision} />}
        <ReviewAction sub={sub} orderId={order.id} revision={revision} />
        <BuyerDisputeAction sub={sub} orderId={order.id} revision={revision}
          paid={order.status === "PAGADA" || order.status === "REEMBOLSO_PARCIAL"} serverNow={order.serverNow} />
      </>}>
      {order.status === "PAGADA" && <Button asChild className="w-full">
        <Link to="/mis-ordenes">Seguir mi compra</Link>
      </Button>}
    </CheckoutSummary>
  </div>;
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
