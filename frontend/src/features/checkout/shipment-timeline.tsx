import { profileLink } from "@/features/auth/profile-fields";
import { ApiError } from "@/shared/api/http-client";
import type { Carrier, ShipmentResponse } from "./checkout-api";

export const carriers: Record<Carrier, string> = {
  ANDREANI: "Andreani",
  CORREO_ARGENTINO: "Correo Argentino",
};

const statuses: Record<string, string> = {
  ESPERA_PAGO: "En espera de pago",
  PREPARANDO: "En preparación",
  DESPACHADA: "Despachada",
  ENTREGADA: "Entregada",
  CANCELADA: "Cancelada",
  REEMBOLSADA: "Reembolsada",
};

export function subOrderLabel(status: string) {
  return statuses[status] ?? status.replaceAll("_", " ").toLowerCase();
}

const dateFormat = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "medium", timeStyle: "short", timeZone: "America/Argentina/Buenos_Aires",
});

export function ShipmentTimeline({ shipment }: { shipment: NonNullable<ShipmentResponse["shipment"]> }) {
  return <section className="space-y-2 border-t pt-3" aria-label="Seguimiento del envío">
    <h3 className="font-semibold">Seguimiento del envío</h3>
    <ol className="ml-2 space-y-3 border-l-2 border-primary/30 pl-4 text-sm">
      <li>
        <p className="font-medium">Despacho registrado</p>
        <EventTime value={shipment.shippedAt} />
        <p className="[overflow-wrap:anywhere]">{carriers[shipment.carrier]} · {shipment.trackingCode}</p>
        <a className={profileLink} href={shipment.trackingUrl} target="_blank" rel="noopener noreferrer">
          Consultar en {carriers[shipment.carrier]} (nueva pestaña)
        </a>
      </li>
      <li>
        <p className="font-medium">{shipment.deliveredAt ? "Recepción confirmada" : "Recepción pendiente"}</p>
        {shipment.deliveredAt && <EventTime value={shipment.deliveredAt} />}
      </li>
    </ol>
  </section>;
}

function EventTime({ value }: { value: string }) {
  return <time className="text-muted-foreground" dateTime={value}>{dateFormat.format(new Date(value))}</time>;
}

export function shipmentError(error: unknown, fallback: string) {
  if (!(error instanceof ApiError)) return fallback;
  if (error.status === 409) return "La suborden cambió. Actualizá su estado.";
  if (error.status === 404) return "No encontramos esa suborden.";
  if (error.status === 400) return "Revisá el transportista y el código de seguimiento.";
  return fallback;
}
