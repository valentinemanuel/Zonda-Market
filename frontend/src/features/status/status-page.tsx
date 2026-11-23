import { useQuery } from "@tanstack/react-query";
import { Activity, ArrowUpRight, CircleAlert, CircleCheck, Clock3, Database, RefreshCw, Server, Tag } from "lucide-react";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/ui/card";
import { Link } from "react-router-dom";
import { fetchStatus } from "./status-api";

const serviceLink = "inline-flex items-center gap-1.5 text-sm text-muted-foreground underline-offset-4 hover:underline";

export function StatusPage() {
  const { data, dataUpdatedAt, isFetching, refetch } = useQuery({
    queryKey: ["system-status"],
    queryFn: ({ signal }) => fetchStatus(signal),
    refetchOnWindowFocus: false,
  });

  const ready = Boolean(data?.api) && data?.readiness === "ready";
  const summary = data ? ready ? "Sistema operativo" : "Servicio interrumpido" : "Comprobando servicios";
  const SummaryIcon = data ? ready ? CircleCheck : CircleAlert : Clock3;
  const summaryColor = data ? ready ? "text-[#a4e7ba]" : "text-[#ffd08d]" : "text-hero-muted";
  const apiStatus = data ? data.api ? "Disponible" : "No disponible" : "Comprobando";
  const databaseStatus = !data ? "Comprobando" : {
    ready: "Disponible",
    down: "No disponible",
    unknown: "Sin confirmar",
  }[data.readiness];
  const checkedAt = data ? new Date(dataUpdatedAt) : null;

  return (
    <div className="space-y-7">
      <section aria-labelledby="status-title" className="workspace-heading p-6 sm:p-9 lg:p-11">
        <div className="grid gap-8 md:grid-cols-[1fr_18rem] md:items-end lg:gap-16">
          <div>
            <Activity size={29} className="mb-5 text-[#ffc564]" aria-hidden="true" />
            <h1 id="status-title" className="text-4xl leading-[1.04] font-semibold tracking-[-0.055em] sm:text-6xl">
              Estado del sistema
            </h1>
            <p className="mt-4 max-w-md text-base leading-relaxed text-hero-muted sm:text-lg">
              Consultá la versión de Zonda Market y la disponibilidad de la API y la base de datos.
            </p>
          </div>
          <div role="status" className="rounded-2xl border border-hero-border bg-hero-panel p-5 sm:p-6">
            <span className="text-sm text-hero-muted">Disponibilidad general</span>
            <div className={`mt-3 flex items-center gap-2.5 text-lg font-semibold ${summaryColor}`}>
              <SummaryIcon size={20} aria-hidden="true" />
              {summary}
            </div>
          </div>
        </div>
      </section>
      <Link
        to="/categorias/e0020000-0000-4000-8000-000000000001"
        className="inline-flex min-h-11 items-center gap-2 rounded-md text-sm font-semibold text-primary hover:underline"
      >
        Explorar categoría Tecnología <ArrowUpRight size={16} aria-hidden="true" />
      </Link>

      <section aria-labelledby="services-title" className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 id="services-title" className="text-2xl font-semibold tracking-tight">Servicios</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {checkedAt
                ? <>Última comprobación <time dateTime={checkedAt.toISOString()}>{checkedAt.toLocaleTimeString("es-AR")}</time></>
                : "Esperando la primera comprobación"}
            </p>
          </div>
          <Button type="button" variant="outline" disabled={isFetching} onClick={() => void refetch()}>
            <RefreshCw aria-hidden="true" className={isFetching ? "motion-safe:animate-spin" : undefined} />
            {isFetching ? "Comprobando…" : data && !ready ? "Reintentar" : "Actualizar"}
          </Button>
        </div>

        {data && !ready && (
          <p role="alert" className="flex items-start gap-2 rounded-xl border border-[#e5c6b8] bg-[#fff5ee] px-4 py-3.5 text-sm text-[#87361f]">
            <CircleAlert size={18} className="mt-0.5 shrink-0" aria-hidden="true" />
            {data.api
              ? "La base de datos no está disponible o no pudo verificarse. Reintentá cuando se restablezca el servicio."
              : "No pudimos consultar la API. Reintentá cuando se restablezca el servicio."}
          </p>
        )}

        <div className="grid gap-4 sm:grid-cols-3">
          <Card className="shadow-none">
            <CardHeader>
              <Server size={21} className="mb-2 text-muted-foreground" aria-hidden="true" />
              <CardTitle>API</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-lg font-semibold">{apiStatus}</p>
              <a href="/api/v1/status" className={serviceLink}>Consultar respuesta <ArrowUpRight size={15} aria-hidden="true" /></a>
            </CardContent>
          </Card>
          <Card className="shadow-none">
            <CardHeader>
              <Database size={21} className="mb-2 text-muted-foreground" aria-hidden="true" />
              <CardTitle>PostgreSQL</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <p className="text-lg font-semibold">{databaseStatus}</p>
              <a href="/actuator/health/readiness" className={serviceLink}>Consultar readiness <ArrowUpRight size={15} aria-hidden="true" /></a>
            </CardContent>
          </Card>
          <Card className="shadow-none">
            <CardHeader>
              <Tag size={21} className="mb-2 text-muted-foreground" aria-hidden="true" />
              <CardTitle>Versión del backend</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="font-mono text-lg font-semibold break-all">{data?.api?.version ?? "—"}</p>
            </CardContent>
          </Card>
        </div>
      </section>
    </div>
  );
}
