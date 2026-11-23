import { useQuery } from "@tanstack/react-query";
import { useSyncExternalStore, type ReactNode } from "react";
import { Link, NavLink, Navigate } from "react-router-dom";
import { currentAccount, getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";

export function AdminPage({ title, children }: { title: string; children: (revision: number) => ReactNode }) {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  const account = useQuery({
    queryKey: ["private", "me", revision],
    queryFn: ({ signal }) => currentAccount(signal),
    retry: false,
  });

  if (account.error instanceof ApiError && account.error.status === 401) return <Navigate to="/ingresar" replace />;

  return (
    <div className="mx-auto max-w-6xl space-y-5">
      <header className="rounded-2xl bg-hero p-5 text-white sm:p-8">
        <h1 className="font-wordmark text-3xl font-bold tracking-tight sm:text-5xl">{title}</h1>
      </header>
      {account.isPending && <p role="status">Verificando el acceso…</p>}
      {account.isError && <LoadError message="No pudimos verificar el acceso." retry={account.refetch} />}
      {account.isSuccess && (account.data.roles.includes("ADMIN") ? (
        <>
          <nav aria-label="Secciones de administración" className="flex flex-wrap gap-2">
            <AdminLink to="/admin/publicaciones">Publicaciones</AdminLink>
            <AdminLink to="/admin/cuentas">Cuentas</AdminLink>
            <AdminLink to="/admin/categorias">Categorías</AdminLink>
          </nav>
          {children(revision)}
        </>
      ) : (
        <div className="space-y-3 rounded-2xl border bg-card p-5">
          <p role="alert">Necesitás una cuenta administradora para entrar.</p>
          <Link to="/cuenta" className="underline underline-offset-4">Volver a mi cuenta</Link>
        </div>
      ))}
    </div>
  );
}

function AdminLink({ to, children }: { to: string; children: ReactNode }) {
  return <NavLink end to={to} className={({ isActive }) =>
    `rounded-lg px-4 py-3 text-sm font-semibold ${isActive ? "bg-accent text-accent-foreground" : "border bg-card hover:bg-muted"}`
  }>{children}</NavLink>;
}

export function LoadError({ message, retry }: { message: string; retry: () => unknown }) {
  return <div className="space-y-2"><p role="alert" className="text-sm text-destructive">{message}</p>
    <Button type="button" variant="outline" onClick={() => void retry()}>Reintentar</Button></div>;
}
