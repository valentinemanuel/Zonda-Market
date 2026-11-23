import { useQuery } from "@tanstack/react-query";
import type { FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { ApiError } from "@/shared/api/http-client";
import { AdminPage, LoadError } from "./admin-page";
import { auditPath, auditQueryKey, fetchAudit, type AuditEvent } from "./audit-api";
import { AuditPagination, auditActions, auditControl, auditDate, results } from "./audit-ui";

const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const fields = [
  { name: "actor", label: "ID del actor" }, { name: "resource", label: "ID del recurso" }, { name: "traceId", label: "ID de traza" },
  { name: "from", label: "Desde", date: true }, { name: "to", label: "Hasta", date: true },
];
const diffLabels: Record<string, string> = {
  before: "Antes", after: "Después", delta: "Variación", amount: "Importe", currency: "Moneda",
  method: "Medio de pago", cause: "Causa", beforeBps: "Comisión anterior (pb)", afterBps: "Comisión vigente (pb)",
  originalTraceId: "Traza original", attempts: "Intentos",
};
const resourceLabels: Record<string, string> = {
  ACCOUNT: "Cuenta", LISTING: "Publicación", SKU: "Variante", CATEGORY: "Categoría", ORDER: "Orden",
  DISPUTE: "Reclamo", COMPENSATION: "Compensación", INCIDENT: "Incidencia",
};
const roleLabels: Record<string, string> = { ADMIN: "Administración", SELLER: "Vendedor", BUYER: "Comprador", SYSTEM: "Sistema" };

export function AuditPage() {
  return <AdminPage title="Auditoría">{(revision) => <AuditResults key={revision} revision={revision} />}</AdminPage>;
}

function AuditResults({ revision }: { revision: number }) {
  const [params, setParams] = useSearchParams();
  const filters = new URLSearchParams();
  for (const { name } of [...fields, { name: "action" }, { name: "page" }]) {
    const value = params.get(name);
    if (value) filters.set(name, value);
  }

  const query = useQuery({
    queryKey: [...auditQueryKey(revision), filters.toString()],
    queryFn: ({ signal }) => fetchAudit(filters, signal),
    retry: false,
  });

  function filter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = new URLSearchParams();
    for (const [name, value] of new FormData(event.currentTarget)) {
      const normalized = String(value).trim();
      if (normalized) next.set(name, normalized);
    }
    setParams(next);
  }

  return <section className="space-y-4">
    <search>
      <form key={filters.toString()} method="get" action={auditPath} onSubmit={filter} aria-label="Filtros de auditoría"
        className="grid gap-3 rounded-xl border bg-card p-4 sm:grid-cols-2 lg:grid-cols-3">
        {fields.map(({ name, label, date }) => <label className="grid gap-1 text-sm font-medium" key={name}>
          {label}
          <input className={auditControl} name={name} defaultValue={params.get(name) ?? ""} type={date ? "date" : "text"}
            pattern={date ? undefined : uuid} title={date ? undefined : "Identificador UUID"} />
        </label>)}
        <label className="grid gap-1 text-sm font-medium">
          Acción
          <select className={auditControl} name="action" defaultValue={params.get("action") ?? ""}>
            <option value="">Todas las acciones</option>
            {Object.entries(auditActions).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <div className="flex flex-wrap gap-2 sm:col-span-2 lg:col-span-3">
          <Button type="submit">Buscar</Button>
          <Button variant="outline" asChild><Link to={auditPath}>Limpiar filtros</Link></Button>
        </div>
      </form>
    </search>
    {query.isPending && <p role="status">Cargando auditoría…</p>}
    {query.isError && (query.error instanceof ApiError && query.error.status === 400
      ? <p role="alert" className="text-destructive">Revisá los identificadores y el rango de fechas.</p>
      : <LoadError message="No pudimos cargar la auditoría." retry={query.refetch} />)}
    {query.data && <>
      {query.data.items.length > 0 ? <div role="region" aria-label="Eventos de auditoría" tabIndex={0}
        className="max-h-[65vh] overflow-auto rounded-xl border bg-card focus-visible:outline-2">
        <table className="w-full min-w-[42rem] text-left text-sm">
          <thead className="sticky top-0 bg-muted">
            <tr>{["Fecha (Buenos Aires)", "Acción", "Resultado", "Correlación"].map((heading) =>
              <th scope="col" key={heading} className="px-4 py-3">{heading}</th>)}</tr>
          </thead>
          <tbody>{query.data.items.map((event) => <AuditRow key={event.id} event={event} />)}</tbody>
        </table>
      </div> : <p role="status">No hay eventos para estos filtros.</p>}
      <AuditPagination page={query.data.page} hasNext={query.data.hasNext} changePage={(page) => {
        filters.set("page", String(page));
        setParams(filters);
      }} />
    </>}
  </section>;
}

function AuditRow({ event }: { event: AuditEvent }) {
  return <tr className="border-t align-top">
    <td className="whitespace-nowrap px-4 py-3"><time dateTime={event.timestamp}>{auditDate.format(new Date(event.timestamp))}</time></td>
    <th scope="row" className="px-4 py-3 font-semibold">{auditActions[event.action] ?? event.action}</th>
    <td className="px-4 py-3">{results[event.result] ?? event.result}</td>
    <td className="px-4 py-3">
      <details className="space-y-2">
        <summary className="cursor-pointer font-medium underline underline-offset-2">Ver detalle</summary>
        <dl className="space-y-1 break-all">
          <div><dt className="font-medium">Actor · {roleLabels[event.role] ?? event.role}</dt>
            {event.actorId && <dd>{event.actorId}</dd>}</div>
          <div><dt className="font-medium">Recurso · {resourceLabels[event.resourceType] ?? event.resourceType}</dt>
            <dd><Link className="underline" to={`${auditPath}?resource=${event.resourceId}`}>{event.resourceId}</Link></dd></div>
          {event.orderId && event.orderId !== event.resourceId && <div><dt className="font-medium">Orden</dt>
            <dd><Link className="underline" to={`${auditPath}?resource=${event.orderId}`}>{event.orderId}</Link></dd></div>}
          {event.disputeId && event.disputeId !== event.resourceId && <div><dt className="font-medium">Reclamo</dt>
            <dd><Link className="underline" to={`${auditPath}?resource=${event.disputeId}`}>{event.disputeId}</Link></dd></div>}
          <div><dt className="font-medium">Traza</dt>
            <dd><Link className="underline" to={`${auditPath}?traceId=${event.traceId}`}>{event.traceId}</Link></dd></div>
          {Object.entries(event.diff).map(([key, value]) => <div key={key}>
            <dt className="font-medium">{diffLabels[key] ?? key}</dt>
            <dd>{key === "originalTraceId" ? <Link className="underline" to={`${auditPath}?traceId=${value}`}>{value}</Link> : value}</dd>
          </div>)}
        </dl>
      </details>
    </td>
  </tr>;
}
