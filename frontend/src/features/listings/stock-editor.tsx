import { History, Minus, Plus, Save } from "lucide-react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import { ProfileInput, profileControl } from "@/features/auth/profile-fields";
import { Button } from "@/shared/ui/button";
import { type Sku } from "./listing-api";
import { useSave } from "./listing-forms";
import { unitFormat } from "./seller-workspace";
import { adjustStock, fetchStockMovements, type StockAdjustment, type StockMovement } from "./stock-api";

const formatDate = new Intl.DateTimeFormat("es-AR", { dateStyle: "short", timeStyle: "short" });

export function StockEditor({ sku, revision, saved, reload }: {
  sku: Sku;
  revision: number;
  saved: (movement: StockMovement) => void;
  reload: () => Promise<Sku>;
}) {
  const state = useSave();
  const [direction, setDirection] = useState<"add" | "remove">("add");
  const [quantity, setQuantity] = useState("");
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
      delta: Number(quantity) * (direction === "add" ? 1 : -1),
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
      setQuantity("");
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
  const units = Number(quantity);
  const preview = units > 0 && Number.isInteger(units) && units <= 2147483647
    ? sku.availableStock + units * (direction === "add" ? 1 : -1) : undefined;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3 rounded-xl border bg-card p-4">
        <div aria-label="Stock disponible" role="group">
          <p className="text-sm font-medium text-muted-foreground">Stock disponible</p>
          <p className="text-3xl leading-tight font-semibold tabular-nums">{unitFormat.format(sku.availableStock)}</p>
          <p className="text-xs text-muted-foreground">{sku.availableStock === 1 ? "unidad" : "unidades"}</p>
        </div>
        {preview !== undefined && (
          <div className="text-right" aria-live="polite">
            <p className="text-xs font-medium text-muted-foreground">Después del ajuste</p>
            <p className={preview >= 0 && preview <= 2147483647 ? "font-semibold tabular-nums text-primary" : "text-sm font-semibold text-destructive"}>
              {preview < 0 ? "Saldo insuficiente" : preview > 2147483647 ? "Supera el límite" : unitFormat.format(preview) + " u."}
            </p>
          </div>
        )}
      </div>
      <form onSubmit={submit} className="space-y-3 rounded-xl border bg-card p-4">
        <fieldset disabled={state.busy || refreshing} className="min-w-0 space-y-3">
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Tipo de ajuste</legend>
            <div className="grid grid-cols-2 gap-2">
              <label className="cursor-pointer">
                <input type="radio" name="stock-direction" value="add" className="peer sr-only" checked={direction === "add"}
                  onChange={() => setDirection("add")} />
                <span className="flex min-h-11 items-center justify-center gap-2 rounded-lg border px-2 text-sm font-semibold peer-checked:border-primary peer-checked:bg-secondary peer-checked:text-accent-foreground peer-focus-visible:ring-2 peer-focus-visible:ring-ring">
                  <Plus size={17} aria-hidden="true" /> Sumar
                </span>
              </label>
              <label className="cursor-pointer">
                <input type="radio" name="stock-direction" value="remove" className="peer sr-only" checked={direction === "remove"}
                  onChange={() => setDirection("remove")} />
                <span className="flex min-h-11 items-center justify-center gap-2 rounded-lg border px-2 text-sm font-semibold peer-checked:border-primary peer-checked:bg-secondary peer-checked:text-accent-foreground peer-focus-visible:ring-2 peer-focus-visible:ring-ring">
                  <Minus size={17} aria-hidden="true" /> Descontar
                </span>
              </label>
            </div>
          </fieldset>
          <ProfileInput id="stock-delta" name="delta" label="Cantidad de unidades" type="number"
            inputMode="numeric" step={1} min={1} max={2147483647} required value={quantity}
            onChange={(event) => setQuantity(event.target.value)} />
          <div>
            <label htmlFor="stock-reason" className="mb-1.5 block text-sm font-medium">Motivo del ajuste</label>
            <textarea id="stock-reason" name="reason" className={profileControl} rows={2} maxLength={240} required />
          </div>
        </fieldset>
        <Button type="submit" className="min-h-11 w-full sm:w-auto" disabled={state.busy || refreshing || conflict}><Save /> Guardar ajuste</Button>
      </form>
      <p role="status" className="text-sm">{state.busy ? "Guardando ajuste…" : refreshing ? "Recargando stock…" : state.success}</p>
      {state.error && (state.error.code !== "VERSION_CONFLICT" || conflict) && <p role="alert" className="text-sm text-destructive">{state.error.message}</p>}
      {refreshError && <p role="alert" className="text-sm text-destructive">No pudimos recargar el stock. Volvé a intentar.</p>}
      {conflict && <Button type="button" variant="outline" disabled={refreshing} onClick={() => void refreshStock()}>Recargar stock</Button>}

      <section aria-labelledby="stock-history-title" className="space-y-3 border-t pt-4">
        <h3 id="stock-history-title" className="flex items-center gap-2 font-semibold"><History size={18} aria-hidden="true" /> Historial de movimientos</h3>
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
