import { Bell, BellOff, ChevronRight } from "lucide-react";
import { useRef, useSyncExternalStore } from "react";
import { Popover } from "radix-ui";
import { Link, useLocation } from "react-router-dom";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { Button } from "@/shared/ui/button";
import { useNotifications } from "./notifications-api";
import { notificationDate, notificationStyles } from "./notification-presentation";

export function NotificationBell({ enabled }: { enabled: boolean }) {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const { key } = useLocation();
  const query = useNotifications(revision, 0, enabled, true);
  const panel = useRef<HTMLDivElement>(null);
  if (!enabled || !query.data || signInRequired(query.error)) return null;

  const count = query.data.unreadCount;
  const label = count === 0 ? "Notificaciones" : count === 1
    ? "Notificaciones, 1 sin leer" : `Notificaciones, ${count} sin leer`;
  return <Popover.Root key={revision + ":" + key}>
    <Popover.Trigger type="button" aria-label={label}
      className="inline-flex min-h-11 min-w-11 items-center justify-center gap-0.5 rounded-lg px-1 focus-visible:outline-brand-ink">
      <Bell size={19} aria-hidden="true" />
      {count > 0 && <span aria-hidden="true" className="rounded-full bg-brand-ink px-1.5 text-xs text-white tabular-nums">
        {count > 99 ? "99+" : count}
      </span>}
    </Popover.Trigger>
    <Popover.Portal>
      <Popover.Content ref={panel} align="end" sideOffset={8} collisionPadding={12} aria-label="Notificaciones recientes"
        onOpenAutoFocus={(event) => {
          const first = panel.current?.querySelector<HTMLAnchorElement>("a");
          if (!first) return;
          event.preventDefault();
          first.focus({ preventScroll: true });
        }}
        className="z-40 flex max-h-[var(--radix-popover-content-available-height)] w-[min(24rem,calc(100vw-24px))] flex-col rounded-panel border bg-card text-foreground shadow-float">
        <div className="flex items-center justify-between gap-3 border-b px-4 py-3.5">
          <h2 className="font-wordmark text-lg font-bold">Notificaciones</h2>
          {count > 0 && <span className="rounded-full bg-accent px-2.5 py-1 text-xs font-semibold text-accent-foreground">{count} sin leer</span>}
        </div>
        {query.data.content.length === 0 ? <p className="flex items-center gap-3 p-5 text-sm text-muted-foreground">
          <BellOff aria-hidden="true" className="size-5 shrink-0" />Todavía no tenés notificaciones.
        </p> : <ol role="list" className="min-h-0 divide-y overflow-y-auto overscroll-contain">
          {query.data.content.slice(0, 5).map((entry) => {
            const { title, icon: Icon, category, color } = notificationStyles[entry.type];
            return <li key={entry.id}>
              <Popover.Close asChild>
                <Link to={entry.href} className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 px-4 py-3 hover:bg-muted focus-visible:outline-offset-[-3px]">
                  <span className={`grid size-9 place-items-center rounded-lg ${color}`}><Icon aria-hidden="true" className="size-4" /></span>
                  <span>
                    <span className="block text-sm font-semibold">{title}</span>
                    <span className="mt-0.5 flex flex-wrap gap-x-2 text-xs text-muted-foreground">
                      {category}<time dateTime={entry.createdAt}>{notificationDate.format(new Date(entry.createdAt))}</time>
                    </span>
                  </span>
                  {!entry.read && <span className="size-2 rounded-full bg-primary"><span className="sr-only">Sin leer</span></span>}
                </Link>
              </Popover.Close>
            </li>;
          })}
        </ol>}
        <div className="border-t p-2">
          <Popover.Close asChild>
            <Button asChild variant="ghost" className="min-h-11 w-full justify-between text-primary">
              <Link to="/notificaciones">Ver todas mis notificaciones <ChevronRight aria-hidden="true" /></Link>
            </Button>
          </Popover.Close>
        </div>
        <Popover.Arrow className="fill-card" />
      </Popover.Content>
    </Popover.Portal>
  </Popover.Root>;
}
