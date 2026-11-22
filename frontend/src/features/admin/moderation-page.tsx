import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, useSyncExternalStore, type FormEvent, type ReactNode } from "react";
import { Link, NavLink, Navigate } from "react-router-dom";
import { currentAccount, getSessionRevision, subscribeToSession } from "@/features/auth/session-api";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { listTargets, moderate, targetDetails, type Action, type Audit, type OrderCase, type Page, type Resource, type Row } from "./moderation-api";

const actions: Record<Action, string> = {
  HIDE: "Ocultar publicación",
  RESTORE: "Restaurar publicación",
  SUSPEND: "Suspender cuenta",
  REACTIVATE: "Reactivar cuenta",
};
const states: Record<string, string> = {
  ACTIVE: "Activa", SUSPENDED: "Suspendida", HIDDEN: "Oculta por moderación", UNHIDDEN: "Sin ocultamiento",
  BORRADOR: "Borrador", ACTIVA: "Activa", ARCHIVADA: "Archivada", RESERVADA: "Reservada",
  PAGO_PENDIENTE: "Pago pendiente", PAGADA: "Pagada", PREPARANDO: "Preparando", DESPACHADA: "Despachada",
};
const control = "w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-ring";
const dateTime = new Intl.DateTimeFormat("es-AR", { dateStyle: "short", timeStyle: "short" });

function label(row: Row) {
  return "title" in row ? row.title : row.displayName || row.email;
}

function actionFor(row: Row): Action | null {
  if ("title" in row) return row.hidden ? "RESTORE" : "HIDE";
  if (row.role === "ADMIN") return null;
  return row.suspended ? "REACTIVATE" : "SUSPEND";
}

export function ModerationPage({ resource }: { resource: Resource }) {
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
        <h1 className="font-wordmark text-3xl font-bold tracking-tight sm:text-5xl">Moderación</h1>
      </header>
      {account.isPending && <p role="status">Verificando el acceso…</p>}
      {account.isError && <LoadError message="No pudimos verificar el acceso." retry={account.refetch} />}
      {account.isSuccess && (account.data.roles.includes("ADMIN") ? (
        <>
          <nav aria-label="Secciones de moderación" className="flex gap-2">
            <AdminLink to="/admin/publicaciones">Publicaciones</AdminLink>
            <AdminLink to="/admin/cuentas">Cuentas</AdminLink>
          </nav>
          <Queue key={`${revision}-${resource}`} resource={resource} revision={revision} />
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

function Queue({ resource, revision }: { resource: Resource; revision: number }) {
  const client = useQueryClient();
  const listings = resource === "listings";
  const [filter, setFilter] = useState({ q: "", state: "", page: 0 });
  const [selection, setSelection] = useState<{ kind: "change" | "detail"; row: Row } | null>(null);
  const [notice, setNotice] = useState("");
  const returnFocus = useRef<HTMLButtonElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const queryKey = ["private", "moderation", revision, resource];
  const results = useQuery({
    queryKey: [...queryKey, "list", filter],
    queryFn: ({ signal }) => listTargets(resource, filter.q, filter.state, filter.page, signal),
    retry: false,
  });
  const change = useMutation({
    mutationFn: ({ row, action, reason }: { row: Row; action: Action; reason: string }) =>
      moderate(resource, row, action, reason),
    onSuccess: (_data, variables) => {
      setNotice(`${actions[variables.action]}: cambio registrado.`);
      setSelection(null);
      void client.invalidateQueries({ queryKey });
      heading.current?.focus();
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) void client.invalidateQueries({ queryKey });
    },
  });

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setFilter({ q: String(form.get("q")).trim(), state: String(form.get("state")), page: 0 });
    setSelection(null);
    setNotice("");
  }

  function open(row: Row, kind: "change" | "detail", trigger: HTMLButtonElement) {
    returnFocus.current = trigger;
    change.reset();
    setNotice("");
    setSelection({ kind, row });
  }

  function close() {
    setSelection(null);
    if (returnFocus.current?.isConnected) returnFocus.current.focus();
    else heading.current?.focus();
  }

  function pageTo(page: number) {
    setFilter({ ...filter, page });
    setSelection(null);
    heading.current?.focus();
  }

  return (
    <section aria-labelledby="moderation-targets" className="min-w-0 space-y-4 rounded-2xl border bg-card p-4 sm:p-6">
      <h2 ref={heading} tabIndex={-1} id="moderation-targets" className="text-xl font-semibold">
        {listings ? "Publicaciones" : "Cuentas"}
      </h2>
      <form method="get" aria-label="Filtros de moderación" onSubmit={applyFilters}
        className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,12rem)_auto] sm:items-end">
        <label className="grid gap-1 text-sm font-medium">
          {listings ? "Título o correo del vendedor" : "Nombre o correo"}
          <input className={control} name="q" type="search" maxLength={160} />
        </label>
        <label className="grid gap-1 text-sm font-medium">
          Estado
          <select className={control} name="state">
            <option value="">Todos</option>
            {listings ? <><option value="VISIBLE">Visibles</option><option value="HIDDEN">Ocultas por moderación</option></>
              : <><option value="ACTIVE">Activas</option><option value="SUSPENDED">Suspendidas</option></>}
          </select>
        </label>
        <Button type="submit" disabled={change.isPending}>Filtrar</Button>
      </form>
      <div role="status" className="text-sm font-medium text-success">{notice}</div>
      {selection?.kind === "change" && (
        <Confirmation key={selection.row.id} row={selection.row} close={close} pending={change.isPending} error={change.error}
          submit={(row, action, reason) => change.mutate({ row, action, reason })} />
      )}
      {selection?.kind === "detail" && (
        <Details key={selection.row.id} row={selection.row} resource={resource} queryKey={queryKey} close={close} />
      )}
      {results.isPending && <p role="status">Cargando resultados…</p>}
      {results.isError && <LoadError message="No pudimos cargar los resultados." retry={results.refetch} />}
      {results.isSuccess && results.data.items.length === 0 && <p>No hay resultados para estos filtros.</p>}
      {results.isSuccess && results.data.items.length > 0 && (
        <div className="rounded-xl border">
          <table className="w-full table-fixed text-left text-sm sm:table-auto">
            <thead className="bg-muted/60">
              <tr><th scope="col" className="w-[37%] p-2 sm:w-auto sm:p-3">{listings ? "Publicación" : "Cuenta"}</th>
                <th scope="col" className="w-1/4 p-2 sm:w-auto sm:p-3">Estado</th>
                <th scope="col" className="p-2 sm:p-3">Acciones</th></tr>
            </thead>
            <tbody>
              {results.data.items.map((row) => {
                const action = actionFor(row);
                return (
                  <tr key={row.id} className="border-t">
                    <th scope="row" className="p-2 align-top wrap-anywhere sm:p-3">
                      {label(row)}
                      <span className="mt-1 block text-xs font-normal text-muted-foreground">
                        {"title" in row ? row.sellerEmail : row.displayName && row.email}
                      </span>
                    </th>
                    <td className="p-2 align-top wrap-anywhere sm:p-3"><RowState row={row} /></td>
                    <td className="p-2 align-top sm:p-3">
                      <div className="flex flex-wrap gap-2">
                        <Button size="sm" variant="outline" type="button" className="h-auto min-h-10 w-full whitespace-normal sm:w-auto"
                          disabled={change.isPending} onClick={(event) => open(row, "detail", event.currentTarget)}>
                          Ver detalle
                        </Button>
                        {action && <Button size="sm" type="button" variant="outline" className="h-auto min-h-10 w-full whitespace-normal sm:w-auto"
                          disabled={change.isPending} onClick={(event) => open(row, "change", event.currentTarget)}>
                          {actions[action]}
                        </Button>}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {results.isSuccess && <Pagination page={results.data} move={pageTo} disabled={change.isPending} />}
    </section>
  );
}

function RowState({ row }: { row: Row }) {
  if (!("title" in row)) {
    return <>{row.suspended ? "Suspendida" : "Activa"}<span className="block text-muted-foreground">{row.role}</span></>;
  }
  return (
    <>
      {row.hidden ? "Oculta por moderación" : row.visible ? "Visible en catálogo" : "Fuera del catálogo"}
      <span className="block text-muted-foreground">{states[row.status] ?? row.status}</span>
      {row.sellerSuspended && <span className="block text-muted-foreground">Cuenta suspendida</span>}
    </>
  );
}

function Confirmation({ row, close, pending, error, submit }: {
  row: Row; close: () => void; pending: boolean; error: Error | null; submit: (row: Row, action: Action, reason: string) => void;
}) {
  const reason = useRef<HTMLTextAreaElement>(null);
  const action = actionFor(row)!;
  const stale = error instanceof ApiError && error.status === 409;
  useEffect(() => { reason.current?.focus(); }, []);

  function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = reason.current?.value.trim();
    if (value) submit(row, action, value);
    else {
      reason.current?.setCustomValidity("Escribí el motivo del cambio.");
      reason.current?.reportValidity();
    }
  }

  return (
    <form method="post" aria-labelledby="confirmation-title" className="space-y-3 rounded-xl border bg-muted/40 p-4" onSubmit={confirm}>
      <h3 id="confirmation-title" className="font-semibold">{actions[action]} · {label(row)}</h3>
      {action === "SUSPEND" && <p className="text-sm">Se cierran las sesiones, se ocultan sus publicaciones y las órdenes pendientes quedan para seguimiento.</p>}
      <label className="block text-sm font-medium" htmlFor="moderation-reason">Motivo obligatorio</label>
      <textarea ref={reason} id="moderation-reason" name="reason" maxLength={500} rows={3} required
        readOnly={pending || stale} className={control} onInput={(event) => event.currentTarget.setCustomValidity("")} />
      {error && <p role="alert" className="text-sm text-destructive">
        {stale ? "El estado cambió. Cerrá este formulario y revisá el registro actualizado." : "No pudimos registrar el cambio. Volvé a intentar."}
      </p>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={pending || stale}>{pending ? "Registrando…" : "Confirmar cambio"}</Button>
        <Button type="button" variant="outline" disabled={pending} onClick={close}>{stale ? "Cerrar" : "Cancelar"}</Button>
      </div>
    </form>
  );
}

function Details({ row, resource, queryKey, close }: {
  row: Row; resource: Resource; queryKey: readonly unknown[]; close: () => void;
}) {
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { title.current?.focus(); }, []);

  return (
    <section aria-labelledby="moderation-detail" className="space-y-4 rounded-xl border bg-muted/40 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h3 ref={title} tabIndex={-1} id="moderation-detail" className="font-semibold">Detalle de {label(row)}</h3>
        <Button variant="outline" size="sm" type="button" onClick={close}>Cerrar detalle</Button>
      </div>
      <DetailSection<Audit> resource={resource} id={row.id} queryKey={queryKey} section="history" title="Historial de moderación"
        empty="Todavía no hay acciones registradas." render={(item) => (
        <li key={item.targetVersion} className="border-t py-2 wrap-anywhere">
          <p className="font-semibold">{actions[item.action]}</p>
          <p>{item.reason}</p>
          <p>{states[item.beforeState]} → {states[item.afterState]}</p>
          <p className="text-muted-foreground">{item.actorEmail} · {dateTime.format(new Date(item.createdAt))}</p>
        </li>
      )} />
      <DetailSection<OrderCase> resource={resource} id={row.id} queryKey={queryKey} section="cases" title="Órdenes pendientes"
        empty="No hay órdenes pendientes vinculadas." render={(item) => (
        <li key={item.subOrderId} className="border-t py-2 wrap-anywhere">
          <p className="font-semibold">{item.sellerName} · {states[item.subOrderStatus] ?? item.subOrderStatus}</p>
          {item.requiresFollowUp && <p className="font-medium">Requiere seguimiento</p>}
          <p>Orden {item.orderId} · {states[item.orderStatus] ?? item.orderStatus}</p>
          <p className="text-muted-foreground">Suborden {item.subOrderId} · {dateTime.format(new Date(item.createdAt))}</p>
        </li>
      )} />
    </section>
  );
}

function DetailSection<T>({ resource, id, queryKey, section, title, empty, render }: {
  resource: Resource; id: string; queryKey: readonly unknown[]; section: "history" | "cases";
  title: string; empty: string; render: (item: T) => ReactNode;
}) {
  const [page, setPage] = useState(0);
  const records = useQuery({
    queryKey: [...queryKey, id, section, page],
    queryFn: ({ signal }) => targetDetails<T>(resource, id, section, page, signal),
    retry: false,
  });
  return (
    <div className="space-y-2 text-sm">
      <h4 className="font-semibold">{title}</h4>
      {records.isPending && <p role="status">Cargando…</p>}
      {records.isError && <LoadError message="No pudimos cargar esta sección." retry={records.refetch} />}
      {records.isSuccess && records.data.items.length === 0 && <p>{empty}</p>}
      {records.isSuccess && records.data.items.length > 0 && <ul>{records.data.items.map(render)}</ul>}
      {records.isSuccess && <Pagination page={records.data} move={setPage} />}
    </div>
  );
}

function Pagination({ page, move, disabled }: { page: Page<unknown>; move: (page: number) => void; disabled?: boolean }) {
  if (!page.hasNext && page.page === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-3 text-sm">
      <Button type="button" size="sm" variant="outline" disabled={disabled || page.page === 0} onClick={() => move(page.page - 1)}>Anterior</Button>
      <span>Página {page.page + 1}</span>
      <Button type="button" size="sm" variant="outline" disabled={disabled || !page.hasNext} onClick={() => move(page.page + 1)}>Siguiente</Button>
    </div>
  );
}

function LoadError({ message, retry }: { message: string; retry: () => unknown }) {
  return <div className="space-y-2"><p role="alert" className="text-sm text-destructive">{message}</p>
    <Button type="button" variant="outline" onClick={() => void retry()}>Reintentar</Button></div>;
}
