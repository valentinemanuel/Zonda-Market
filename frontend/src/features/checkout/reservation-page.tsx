import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, useSyncExternalStore } from "react";
import { Link, useParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { activeOrderKey, fetchOrder, orderKey, ordersKey, orderStatus, remainingMs, type Order } from "./checkout-api";
import { addressText, CheckoutSummary } from "./checkout-summary";
import { TestPaymentAction } from "./test-payment-action";

export function ReservationPage() {
  const { id = "" } = useParams();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const client = useQueryClient();
  const order = useQuery({
    queryKey: orderKey(revision, id), queryFn: ({ signal }) => fetchOrder(id, signal), retry: false,
  });
  const { data, refetch } = order;
  useEffect(() => {
    if (data?.status === "EXPIRADA") {
      void client.invalidateQueries({ queryKey: ["private", "cart", revision] });
      void client.invalidateQueries({ queryKey: activeOrderKey(revision) });
      void client.invalidateQueries({ queryKey: ordersKey(revision) });
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
      <h1 className="text-3xl font-semibold">Tu reserva</h1>
    </div>
    {order.isPending ? <p role="status">Cargando reserva…</p>
      : signInRequired(order.error) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToOrder: id }}>Iniciar sesión para ver la reserva</Link>
        </Button>
      : order.isError ? <div className="space-y-2">
          <p role="alert" className="text-destructive">{order.error instanceof ApiError && order.error.status === 404
            ? "No encontramos esa reserva." : "No pudimos cargar la reserva."}</p>
          <Button variant="outline" onClick={() => void order.refetch()}>Reintentar</Button>
        </div>
      : <Reservation key={revision + ":" + order.data.id} order={order.data} revision={revision} />}
  </div>;
}

function Reservation({ order, revision }: { order: Order; revision: number }) {
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
    {order.testPayment && <TestPaymentAction order={order} fixture={order.testPayment}
      enabled={active && remaining > 0} revision={revision} />}
    {address && <section aria-label="Dirección de entrega" className="space-y-1 rounded-lg border bg-card p-4">
      <h2 className="text-lg font-semibold">Dirección de entrega</h2>
      <p className="break-words">{addressText(address)}</p>
    </section>}
    <CheckoutSummary groups={order.subOrders.map(({ group }) => group)} total={order.total} />
  </div>;
}

function secondsLeft(order: Order) {
  return Math.max(0, Math.ceil(remainingMs(order) / 1000));
}

function running(status: string) {
  return status === "RESERVADA" || status === "PAGO_PENDIENTE";
}
