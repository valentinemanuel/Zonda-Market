import { Bell } from "lucide-react";
import { useSyncExternalStore } from "react";
import { Link } from "react-router-dom";
import { getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { signInRequired } from "@/features/cart/cart-api";
import { useNotifications } from "./notifications-api";

export function NotificationBell({ enabled }: { enabled: boolean }) {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const query = useNotifications(revision, 0, enabled, true);
  if (!enabled || !query.data || signInRequired(query.error)) return null;

  const count = query.data.unreadCount;
  const label = count === 0 ? "Notificaciones" : count === 1
    ? "Notificaciones, 1 sin leer" : `Notificaciones, ${count} sin leer`;
  return <Link to="/notificaciones" aria-label={label}
    className="inline-flex min-h-11 min-w-11 items-center justify-center gap-0.5 rounded-lg px-1 focus-visible:outline-brand-ink">
    <Bell size={19} aria-hidden="true" />
    {count > 0 && <span aria-hidden="true" className="rounded-full bg-brand-ink px-1.5 text-xs text-white tabular-nums">
      {count > 99 ? "99+" : count}
    </span>}
  </Link>;
}
