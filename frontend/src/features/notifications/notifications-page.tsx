import { useEffect, useSyncExternalStore } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { useMarkNotificationRead, useNotifications, type Notification } from "./notifications-api";

const titles: Record<Notification["type"], string> = {
  PAYMENT_APPROVED: "Pago aprobado",
  SUB_ORDER_SHIPPED: "Pedido despachado",
  DELIVERY_CONFIRMED: "Entrega confirmada",
  DISPUTE_OPENED: "Reclamo abierto",
  DISPUTE_ASSIGNED: "Reclamo en mediación",
};
const dateFormat = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "medium", timeStyle: "short", timeZone: "America/Argentina/Buenos_Aires",
});

export function NotificationsPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  return <Notifications key={revision} revision={revision} />;
}

function Notifications({ revision }: { revision: number }) {
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get("pagina"));
  const page = Number.isInteger(raw) && raw > 0 && raw <= 2147483647 ? raw : 0;
  const notifications = useNotifications(revision, page);
  const mark = useMarkNotificationRead(revision);
  useEffect(() => {
    const previous = document.title;
    document.title = "Notificaciones | Zonda Market";
    return () => { document.title = previous; };
  }, []);

  return <div className="mx-auto max-w-3xl space-y-5">
    <div className="space-y-2">
      <Link to="/cuenta" className={profileLink + " text-sm"}>Volver a Mi cuenta</Link>
      <h1 className="text-3xl font-semibold">Notificaciones</h1>
    </div>
    {notifications.isPending ? <p role="status">Cargando notificaciones…</p>
      : signInRequired(notifications.error) ? <Button asChild>
          <Link to="/ingresar" state={{ returnToNotifications: true }}>Iniciar sesión para ver tus notificaciones</Link>
        </Button>
      : notifications.isError && !notifications.data ? <div className="space-y-2">
          <p role="alert" className="text-destructive">No pudimos cargar tus notificaciones.</p>
          <Button type="button" variant="outline" onClick={() => void notifications.refetch()}>Reintentar</Button>
        </div>
      : notifications.data && <>
        {notifications.data.content.length === 0 ? <p>
          {page === 0 ? "Todavía no tenés notificaciones." : "No hay notificaciones en esta página."}
        </p> : <ol role="list" className="space-y-3">
          {notifications.data.content.map((entry) => {
            const title = titles[entry.type];
            const titleId = "title-" + entry.id;
            const readId = "read-" + entry.id;
            const saving = mark.isPending && mark.variables === entry.id;
            return <li key={entry.id}>
              <Card className="gap-0 py-4">
                <CardContent className="space-y-3 px-4 sm:px-6">
                  <div className="flex flex-col gap-1 sm:flex-row sm:flex-wrap sm:items-baseline sm:justify-between sm:gap-x-4">
                    <h2 className="text-lg font-semibold" id={titleId}>{title}</h2>
                    <time dateTime={entry.createdAt} className="text-sm text-muted-foreground">
                      {dateFormat.format(new Date(entry.createdAt))}
                    </time>
                  </div>
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                    <Link to={entry.href} className={profileLink + " inline-flex min-h-11 items-center"}>
                      {entry.type === "DISPUTE_OPENED" || entry.type === "DISPUTE_ASSIGNED" ? "Ver reclamo"
                        : entry.href.startsWith("/vendedor/") ? "Ver pedido" : "Ver compra"}
                      <span className="sr-only"> {entry.resourceId.slice(0, 8)}</span>
                    </Link>
                    <Button type="button" variant={entry.read && !saving ? "ghost" : "outline"} className="min-h-11"
                      aria-disabled={entry.read || mark.isPending}
                      aria-labelledby={readId + " " + titleId}
                      onClick={() => { if (!entry.read && !mark.isPending) mark.mutate(entry.id); }}>
                      <span id={readId}>
                        {saving ? "Guardando…" : entry.read ? "Leída" : "Marcar como leída"}
                      </span>
                    </Button>
                  </div>
                  {mark.isError && mark.variables === entry.id && <p role="alert" className="text-destructive">
                    No pudimos marcar la notificación como leída. Volvé a intentar.
                  </p>}
                </CardContent>
              </Card>
            </li>;
          })}
        </ol>}
        <p role="status" className="sr-only">{mark.isSuccess ? "Notificación marcada como leída." : ""}</p>
        {(page > 0 || notifications.data.hasNext) && <nav aria-label="Paginación de notificaciones" className="flex gap-2">
          <Button variant="outline" disabled={page === 0} onClick={() => setParams({ pagina: String(page - 1) })}>Anterior</Button>
          <Button variant="outline" disabled={!notifications.data.hasNext}
            onClick={() => setParams({ pagina: String(page + 1) })}>Siguiente</Button>
        </nav>}
      </>}
  </div>;
}
