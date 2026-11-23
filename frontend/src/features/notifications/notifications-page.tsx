import { ArrowLeft, ArrowUpRight, Bell, BellOff, Check, CircleAlert } from "lucide-react";
import { useEffect, useSyncExternalStore } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { profileLink } from "@/features/auth/profile-fields";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { useMarkNotificationRead, useNotifications } from "./notifications-api";
import { notificationDate, notificationStyles } from "./notification-presentation";


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

  const unread = notifications.data?.unreadCount;
  return <div className="mx-auto max-w-5xl space-y-5">
    <Link to="/cuenta" className={profileLink + " gap-2 text-sm"}><ArrowLeft aria-hidden="true" className="size-4" />Volver a Mi cuenta</Link>
    <header className="workspace-heading flex flex-wrap items-center gap-4 p-5 sm:p-7">
      <span className="grid size-14 shrink-0 place-items-center rounded-2xl border border-white/15 bg-white/10 text-brand-gold">
        <Bell aria-hidden="true" className="size-6" />
      </span>
      <div className="min-w-0 flex-1 basis-44">
        <h1 className="font-wordmark text-3xl font-extrabold tracking-tight sm:text-4xl">Notificaciones</h1>
        <p className="mt-1 text-sm text-hero-muted">Novedades de tus compras, ventas y reclamos.</p>
      </div>
      {unread !== undefined && <span className="inline-flex min-h-8 items-center gap-2 rounded-full bg-brand-gold px-3 text-sm font-semibold text-brand-ink">
        <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />
        {unread === 0 ? "Al día" : `${unread} sin leer`}
      </span>}
    </header>
    <Card className="gap-0 overflow-hidden py-0">
      <CardContent className="px-0">
        <h2 className="border-b px-4 py-4 text-base font-bold sm:px-6">Actividad</h2>
        {notifications.isPending ? <p role="status" className="px-4 py-8 text-sm text-muted-foreground sm:px-6">Cargando notificaciones…</p>
          : signInRequired(notifications.error) ? <div className="px-4 py-8 sm:px-6">
            <Button asChild className="h-auto min-h-11 whitespace-normal text-left">
              <Link to="/ingresar" state={{ returnToNotifications: true }}>Iniciar sesión para ver tus notificaciones</Link>
            </Button>
          </div>
          : notifications.isError && !notifications.data ? <div className="space-y-3 px-4 py-8 sm:px-6">
            <p role="alert" className="flex items-center gap-2 text-destructive"><CircleAlert aria-hidden="true" className="size-4 shrink-0" />No pudimos cargar tus notificaciones.</p>
            <Button type="button" variant="outline" onClick={() => void notifications.refetch()}>Reintentar</Button>
          </div>
          : notifications.data?.content.length === 0 ? <div className="grid justify-items-center gap-3 px-4 py-12 text-center">
            <BellOff aria-hidden="true" className="size-12 rounded-2xl bg-muted p-3 text-muted-foreground" />
            <p className="font-semibold">{page === 0 ? "Todavía no tenés notificaciones." : "No hay notificaciones en esta página."}</p>
          </div>
          : notifications.data && <ol role="list" className="divide-y">
          {notifications.data.content.map((entry) => {
            const { title, icon: Icon, category, color } = notificationStyles[entry.type];
            const titleId = "title-" + entry.id;
            const readId = "read-" + entry.id;
            const saving = mark.isPending && mark.variables === entry.id;
            return <li key={entry.id} className={`grid grid-cols-[auto_minmax(0,1fr)] items-start gap-x-3 border-s-4 px-3 py-4 sm:gap-x-4 sm:px-5 ${entry.read ? "border-s-transparent" : "border-s-brand bg-accent/35"}`}>
              <span className={`grid size-10 place-items-center rounded-xl sm:size-12 ${color}`}>
                <Icon aria-hidden="true" className="size-5" />
              </span>
              <div className="grid min-w-0 gap-2 md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-muted-foreground">{category}</p>
                  <h3 className="font-bold sm:text-lg" id={titleId}>{title}</h3>
                  <time dateTime={entry.createdAt} className="text-xs text-muted-foreground sm:text-sm">
                    {notificationDate.format(new Date(entry.createdAt))}
                  </time>
                </div>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <Link to={entry.href} className="inline-flex min-h-11 items-center gap-1.5 rounded-md text-sm font-semibold text-primary hover:underline">
                      {entry.href.startsWith("/reclamos/") ? "Ver reclamo"
                        : entry.href.startsWith("/vendedor/") ? "Ver pedido" : "Ver compra"}
                      <span className="sr-only"> {entry.resourceId.slice(0, 8)}</span>
                      <ArrowUpRight aria-hidden="true" className="size-3.5" />
                    </Link>
                    <Button type="button" variant={entry.read && !saving ? "ghost" : "outline"}
                      className={`min-h-11 rounded-full ${entry.read && !saving ? "text-muted-foreground" : ""}`}
                      aria-disabled={entry.read || mark.isPending}
                      aria-labelledby={readId + " " + titleId}
                      onClick={() => { if (!entry.read && !mark.isPending) mark.mutate(entry.id); }}>
                      <Check aria-hidden="true" />
                      <span id={readId}>
                        {saving ? "Guardando…" : entry.read ? "Leída" : "Marcar como leída"}
                      </span>
                    </Button>
                  </div>
                  {mark.isError && mark.variables === entry.id && <p role="alert" className="rounded-lg bg-destructive-soft px-3 py-2 text-sm text-destructive md:col-span-2">
                    No pudimos marcar la notificación como leída. Volvé a intentar.
                  </p>}
              </div>
            </li>;
          })}
        </ol>}
        <p role="status" className="sr-only">{mark.isSuccess ? "Notificación marcada como leída." : ""}</p>
        {notifications.data && (page > 0 || notifications.data.hasNext) && <nav aria-label="Paginación de notificaciones" className="flex flex-wrap items-center gap-2 border-t p-4 sm:px-6">
          <Button variant="outline" disabled={page === 0} onClick={() => setParams({ pagina: String(page - 1) })}>Anterior</Button>
          <span className="text-sm text-muted-foreground">Página {page + 1}</span>
          <Button variant="outline" disabled={!notifications.data.hasNext}
            onClick={() => setParams({ pagina: String(page + 1) })}>Siguiente</Button>
        </nav>}
      </CardContent>
    </Card>
  </div>;
}
