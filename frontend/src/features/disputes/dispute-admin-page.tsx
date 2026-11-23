import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { AdminPage, LoadError } from "@/features/admin/admin-page";
import { Button } from "@/shared/ui/button";
import { disputePath, disputeQueueKey, disputeStatuses, fetchDisputeQueue, queuePath } from "./dispute-api";
import { disputeControl, disputeDate } from "./dispute-ui";

export function DisputeAdminPage() {
  return <AdminPage title="Mediación de reclamos">{(revision) => <Queue key={revision} revision={revision} />}</AdminPage>;
}

function Queue({ revision }: { revision: number }) {
  const [filters, setFilters] = useState({ status: "", age: "", page: 0 });
  const query = useQuery({
    queryKey: [...disputeQueueKey(revision), filters],
    queryFn: ({ signal }) => fetchDisputeQueue(filters.status, filters.age, filters.page, signal),
    retry: false,
  });

  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setFilters({ status: String(form.get("status")), age: String(form.get("maxAgeDays")), page: 0 });
  }

  return <section className="space-y-4">
    <search>
      <form method="get" action={queuePath} onSubmit={filter} aria-label="Filtros de reclamos"
        className="flex flex-wrap items-end gap-3 rounded-lg border bg-card p-4">
        <div className="grid gap-1 text-sm font-medium">
          <label htmlFor="dispute-filter-status">Estado</label>
          <select id="dispute-filter-status" name="status" className={disputeControl} defaultValue="">
            <option value="">Todos los estados</option>
            {Object.entries(disputeStatuses).map(([state, label]) => <option value={state} key={state}>{label}</option>)}
          </select>
        </div>
        <div className="grid gap-1 text-sm font-medium">
          <label htmlFor="dispute-filter-age">Antigüedad</label>
          <select id="dispute-filter-age" name="maxAgeDays" className={disputeControl} defaultValue="">
            <option value="">Cualquier fecha</option>
            <option value="7">Últimos 7 días</option><option value="30">Últimos 30 días</option><option value="90">Últimos 90 días</option>
          </select>
        </div>
        <Button type="submit" className="min-h-12">Filtrar</Button>
      </form>
    </search>
    {query.isPending && <p role="status">Cargando reclamos…</p>}
    {query.isError && <LoadError message="No pudimos cargar los reclamos." retry={query.refetch} />}
    {query.isSuccess && <>
      {query.data.content.length ? <ul className="space-y-3" role="list">
        {query.data.content.map((item) => <li key={item.id}
          className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-card p-4">
          <div className="min-w-0 space-y-1">
            <p className="font-semibold [overflow-wrap:anywhere]">{item.reason}</p>
            <p className="text-sm text-muted-foreground">{disputeStatuses[item.status]} ·{" "}
              <time dateTime={item.openedAt}>{disputeDate.format(new Date(item.openedAt))}</time></p>
          </div>
          <Button variant="outline" asChild className="min-h-12">
            <Link to={disputePath(item.id)} aria-label={`Ver reclamo ${item.reason}`}>Ver reclamo</Link>
          </Button>
        </li>)}
      </ul> : <p>No hay reclamos para estos filtros.</p>}
      {(filters.page > 0 || query.data.hasNext) && <nav aria-label="Páginas de reclamos" className="flex flex-wrap gap-2">
        <Button variant="outline" disabled={filters.page === 0} onClick={() => setFilters({ ...filters, page: filters.page - 1 })}>Anterior</Button>
        <Button variant="outline" disabled={!query.data.hasNext} onClick={() => setFilters({ ...filters, page: filters.page + 1 })}>Siguiente</Button>
      </nav>}
    </>}
  </section>;
}
