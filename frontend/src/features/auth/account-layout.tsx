import { ArrowUpRight, ClipboardList, MapPin, UserRound, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { NavLink } from "react-router-dom";

const destinations = [
  { to: "/cuenta", label: "Perfil y seguridad", icon: UserRound },
  { to: "/cuenta/direcciones", label: "Mis direcciones", icon: MapPin },
  { to: "/mis-ordenes", label: "Mis órdenes", icon: ClipboardList, leavesAccount: true },
];

export function AccountLayout({ title, description, action, children }: {
  title: string;
  description: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="mx-auto max-w-6xl space-y-5 sm:space-y-7">
      <header className="flex flex-wrap items-end justify-between gap-4 rounded-2xl bg-hero p-5 text-white sm:p-8">
        <div>
          <h1 className="font-wordmark text-3xl leading-tight font-bold tracking-tight sm:text-5xl">{title}</h1>
          <p className="mt-2 max-w-xl text-sm text-hero-muted sm:text-base">{description}</p>
        </div>
        {action}
      </header>
      <div className="grid items-start gap-5 md:grid-cols-[13rem_minmax(0,1fr)] lg:gap-7">
        <nav aria-label="Secciones de Mi cuenta"
          className="grid grid-cols-3 gap-1 rounded-2xl border bg-card p-1.5 md:grid-cols-1 md:p-2">
          {destinations.map(({ to, label, icon: Icon, leavesAccount }) => (
            <NavLink key={to} to={to} end className={({ isActive }) =>
              `flex min-h-12 min-w-0 flex-col items-center justify-center gap-1 rounded-xl px-1 py-2 text-center text-xs font-semibold md:flex-row md:justify-start md:gap-2.5 md:px-3 md:text-left md:text-sm ${
                isActive ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:bg-muted"
              }`}>
              <Icon aria-hidden="true" className="size-4 shrink-0" />
              {label}
              {leavesAccount && <ArrowUpRight aria-hidden="true" className="ml-auto hidden size-3 md:block" />}
            </NavLink>
          ))}
        </nav>
        <div className="min-w-0">{children}</div>
      </div>
    </div>
  );
}

export function AccountSection({ id, title, icon: Icon, children, className = "" }: {
  id: string;
  title: string;
  icon: LucideIcon;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section aria-labelledby={id} className={`min-w-0 rounded-2xl border bg-card p-4 sm:p-6 ${className}`}>
      <div className="mb-4 flex items-center gap-3">
        <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-accent text-accent-foreground">
          <Icon aria-hidden="true" className="size-[18px]" />
        </span>
        <h2 id={id} className="text-lg font-semibold tracking-tight">{title}</h2>
      </div>
      {children}
    </section>
  );
}
