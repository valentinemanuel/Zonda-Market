import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import { ProfileInput, profileControl } from "@/features/auth/profile-fields";
import { Button } from "@/shared/ui/button";
import { type Sku } from "./listing-api";
import { useSave } from "./listing-forms";
import { adjustStock, fetchStockMovements, type StockAdjustment, type StockMovement } from "./stock-api";

const formatDate = new Intl.DateTimeFormat("es-AR", { dateStyle: "short", timeStyle: "short" });

export function StockEditor({ sku, revision, saved, reload }: {
  sku: Sku;
  revision: number;
  saved: (movement: StockMovement) => void;
  reload: () => Promise<Sku>;
}) {
  const state = useSave();
  const [version, setVersion] = useState(sku.version);
  const [page, setPage] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState(false);
  const [conflictResolved, setConflictResolved] = useState(false);
  const retry = useRef<{ key: string; body: string } | undefined>(undefined);
  const cache = useQueryClient();
  const key = ["private", "stock-movements", revision, sku.id] as const;
  const history = useQuery({
    queryKey: [...key, page],
    queryFn: ({ signal }) => fetchStockMovements(sku.id, page, signal),
    retry: false,
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const command: StockAdjustment = {
      delta: Number(data.get("delta")),
      reason: String(data.get("reason")).trim(),
      expectedVersion: version,
    };
    void state.submit(async () => {
      setRefreshError(false);
      setConflictResolved(false);
      const body = JSON.stringify(command);
      if (retry.current?.body !== body) retry.current = { key: crypto.randomUUID(), body };
      const movement = await adjustStock(sku.id, retry.current.key, command);
      retry.current = undefined;
      setVersion(movement.version);
      saved(movement);
      form.reset();
      setPage(0);
      void cache.invalidateQueries({ queryKey: key });
    }, "Ajuste registrado.");
  }

  async function refreshStock() {
    setRefreshing(true);
    setRefreshError(false);
    try {
      const current = await reload();
      setVersion(current.version);
      retry.current = undefined;
      setConflictResolved(true);
      void cache.invalidateQueries({ queryKey: key });
    } catch {
      setRefreshError(true);
    } finally {
      setRefreshing(false);
    }
  }

  const conflict = state.error?.code === "VERSION_CONFLICT" && !conflictResolved;

  return (
    <div className="space-y-4">
      <p className="font-medium tabular-nums">Stock disponible: {sku.availableStock}</p>
      <form onSubmit={submit} className="space-y-3">
        <fieldset disabled={state.busy || refreshing} className="min-w-0 space-y-3">
          <ProfileInput id="stock-delta" name="delta" label="Ajuste en unidades" type="number"
            step={1} min={-2147483648} max={2147483647} required aria-describedby="stock-delta-help" />
          <p id="stock-delta-help" className="text-sm text-muted-foreground">Usá un número positivo para sumar o negativo para descontar.</p>
          <div>
            <label htmlFor="stock-reason" className="mb-1.5 block text-sm font-medium">Motivo del ajuste</label>
            <textarea id="stock-reason" name="reason" className={profileControl} rows={2} maxLength={240} required />
          </div>
        </fieldset>
        <Button type="submit" className="min-h-11" disabled={state.busy || refreshing || conflict}>Guardar ajuste</Button>
      </form>
      <p role="status" className="text-sm">{state.busy ? "Guardando ajuste…" : refreshing ? "Recargando stock…" : state.success}</p>
      {state.error && (state.error.code !== "VERSION_CONFLICT" || conflict) && <p role="alert" className="text-sm text-destructive">{state.error.message}</p>}
      {refreshError && <p role="alert" className="text-sm text-destructive">No pudimos recargar el stock. Volvé a intentar.</p>}
      {conflict && <Button type="button" variant="outline" disabled={refreshing} onClick={() => void refreshStock()}>Recargar stock</Button>}

      <section aria-labelledby="stock-history-title" className="space-y-3 border-t pt-4">
        <h3 id="stock-history-title" className="font-semibold">Historial de movimientos</h3>
        {history.isPending && <p role="status" className="text-sm">Cargando movimientos…</p>}
        {history.isError && (
          <div className="space-y-2">
            <p role="alert" className="text-sm text-destructive">No pudimos cargar el historial.</p>
            <Button type="button" variant="outline" onClick={() => void history.refetch()}>Reintentar historial</Button>
          </div>
        )}
        {history.isSuccess && (
          <>
            {history.data.items.length === 0 && <p className="text-sm text-muted-foreground">Todavía no hay movimientos.</p>}
            <ul className="divide-y rounded-lg border bg-card empty:hidden">
              {history.data.items.map((movement) => (
                <li key={movement.id} className="flex flex-wrap justify-between gap-2 px-4 py-3 text-sm">
                  <div className="min-w-0 space-y-1">
                    <p className="font-medium [overflow-wrap:anywhere]">{movement.reason}</p>
                    <time className="text-muted-foreground" dateTime={movement.createdAt}>
                      {formatDate.format(new Date(movement.createdAt))}
                    </time>
                  </div>
                  <div className="tabular-nums">
                    <p className="font-medium">{movement.delta > 0 ? "+" : ""}{movement.delta} unidades</p>
                    <p className="text-muted-foreground">De {movement.beforeStock} a {movement.afterStock}</p>
                  </div>
                </li>
              ))}
            </ul>
            {(page > 0 || history.data.totalPages > 1) && (
              <nav aria-label="Páginas de movimientos" className="flex flex-wrap items-center gap-2">
                <Button type="button" variant="outline" disabled={page === 0}
                  onClick={() => setPage(page - 1)}>Anterior</Button>
                <span className="px-1 text-sm">Página {page + 1} de {Math.max(history.data.totalPages, 1)}</span>
                <Button type="button" variant="outline" disabled={page + 1 >= history.data.totalPages}
                  onClick={() => setPage(page + 1)}>Siguiente</Button>
              </nav>
            )}
          </>
        )}
      </section>
    </div>
  );
}
