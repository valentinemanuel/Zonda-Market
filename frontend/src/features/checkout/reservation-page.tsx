import { useQuery } from "@tanstack/react-query";
import { useEffect, useState, useSyncExternalStore } from "react";
import { Link, useParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { fetchOrder, orderKey, remainingMs, type Order } from "./checkout-api";
import { addressText, CheckoutSummary } from "./checkout-summary";

export function ReservationPage() {
  const { id = "" } = useParams();
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const order = useQuery({
    queryKey: orderKey(revision, id), queryFn: ({ signal }) => fetchOrder(id, signal), retry: false,
  });

  return <div className="mx-auto max-w-5xl space-y-6">
    <div className="space-y-2">
      <Link to="/carrito" className={profileLink + " text-sm"}>Volver al carrito</Link>
      <h1 className="text-3xl font-semibold">Tu reserva</h1>
    </div>
    {order.isPending ? <p role="status">Cargando reserva…</p>
      : signInRequired(order.error) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToCheckout: true }}>Iniciar sesión para ver la reserva</Link>
        </Button>
      : order.isError ? <div className="space-y-2">
          <p role="alert" className="text-destructive">{order.error instanceof ApiError && order.error.status === 404
            ? "No encontramos esa reserva." : "No pudimos cargar la reserva."}</p>
          <Button variant="outline" onClick={() => void order.refetch()}>Reintentar</Button>
        </div>
      : <Reservation key={order.data.id + order.data.serverNow} order={order.data} />}
  </div>;
}

function Reservation({ order }: { order: Order }) {
  const [seconds, setSeconds] = useState(() => secondsLeft(order));
  useEffect(() => {
    const interval = window.setInterval(() => setSeconds(secondsLeft(order)), 250);
    return () => window.clearInterval(interval);
  }, [order]);
  const expired = seconds === 0 || order.status === "EXPIRADA";
  const time = Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
  const address = order.subOrders[0]?.address;

  return <div className="space-y-5">
    <div className="space-y-2 rounded-lg border bg-card p-4">
      <p role="status" className={expired ? "font-semibold text-destructive" : "font-semibold text-success"}>
        {expired ? "La reserva venció." : "Productos reservados"}
      </p>
      {!expired && <p>Tiempo restante <span role="timer" className="font-semibold tabular-nums">{time}</span></p>}
      <p className="break-words text-sm text-muted-foreground">Orden {order.id}</p>
    </div>
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
