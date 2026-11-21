import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { formattedPrice } from "@/features/listings/public-listing-format";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { fetchOrders, ordersKey, orderStatus, reservationPath } from "./checkout-api";

const dateFormat = new Intl.DateTimeFormat("es-AR", { dateStyle: "medium", timeStyle: "short", timeZone: "America/Argentina/Buenos_Aires" });

export function OrdersPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get("pagina"));
  const page = Number.isInteger(raw) && raw > 0 && raw <= 2147483647 ? raw : 0;
  const orders = useQuery({
    queryKey: [...ordersKey(revision), page],
    queryFn: ({ signal }) => fetchOrders(page, signal),
    retry: false,
  });

  return <div className="mx-auto max-w-3xl space-y-5">
    <div className="space-y-2">
      <Link className={profileLink + " text-sm"} to="/cuenta">Volver a Mi cuenta</Link>
      <h1 className="text-3xl font-semibold">Mis órdenes</h1>
    </div>
    {orders.isPending ? <p role="status">Cargando órdenes…</p>
      : signInRequired(orders.error) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToOrders: true }}>Iniciar sesión para ver tus órdenes</Link>
        </Button>
      : orders.isError ? <div className="space-y-2">
          <p role="alert" className="text-destructive">No pudimos cargar tus órdenes.</p>
          <Button variant="outline" onClick={() => void orders.refetch()}>Reintentar</Button>
        </div>
      : <>
          {orders.data.content.length ? <ul className="space-y-3">
            {orders.data.content.map((order) => (
              <li key={order.id}>
                <Card className="gap-0 py-3">
                  <CardContent className="flex flex-wrap items-center justify-between gap-x-5 gap-y-1 px-4 sm:px-6">
                    <div>
                      <Link className={profileLink + " font-semibold"} to={reservationPath(order.id)}
                        aria-label={"Ver orden " + order.id}>Orden {order.id.slice(0, 8)}</Link>
                      <p className="text-sm text-muted-foreground">
                        <time dateTime={order.createdAt}>{dateFormat.format(new Date(order.createdAt))}</time>
                      </p>
                    </div>
                    <div className="space-y-1">
                      <p className="text-sm font-medium">{orderStatus(order.status)}</p>
                      <p className="tabular-nums">{formattedPrice(order.total)}</p>
                    </div>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul> : <p>{page === 0 ? "Todavía no tenés órdenes." : "No hay órdenes en esta página."}</p>}
          {(page > 0 || orders.data.hasNext) && <nav aria-label="Paginación de órdenes" className="flex flex-wrap gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setParams({ pagina: String(page - 1) })}>Anterior</Button>
            <Button variant="outline" disabled={!orders.data.hasNext} onClick={() => setParams({ pagina: String(page + 1) })}>Siguiente</Button>
          </nav>}
        </>}
  </div>;
}
