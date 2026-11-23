import { Button } from "@/shared/ui/button";

export const auditDate = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "short",
  timeStyle: "medium",
  timeZone: "America/Argentina/Buenos_Aires",
});
export const auditControl = "min-h-11 w-full rounded-lg border bg-background px-3 py-2 font-normal";
export const auditActions: Record<string, string> = {
  MODERATION_HIDE: "Publicación ocultada",
  MODERATION_RESTORE: "Publicación restaurada",
  MODERATION_SUSPEND: "Cuenta suspendida",
  MODERATION_REACTIVATE: "Cuenta reactivada",
  STOCK_ADJUSTED: "Stock ajustado",
  COMMISSION_CHANGED: "Comisión actualizada",
  CHECKOUT_PAYMENT_OUTCOME: "Resultado del pago",
  PAYMENT_VERIFIED: "Pago verificado",
  DISPUTE_OPENED: "Reclamo abierto",
  DISPUTE_ASSIGNED: "Reclamo asignado",
  DISPUTE_DECIDED: "Reclamo resuelto",
  DISPUTE_REFUND_STATUS: "Estado de devolución",
  COMPENSATION_STATUS: "Estado de compensación",
  WEBHOOK_FINISHED: "Notificación de pago procesada",
  INCIDENT_RECONCILE_REQUESTED: "Recuperación solicitada",
};

export const results: Record<string, string> = {
  APPLIED: "Aplicado", APPROVED: "Aprobado", REJECTED: "Rechazado", LATE_APPROVAL: "Aprobado fuera de plazo",
  EXPIRED: "Vencido", APROBADO: "Aprobado", RECHAZADO: "Rechazado", REEMBOLSO: "Con devolución",
  SIN_REEMBOLSO: "Sin devolución", QUEUED: "En cola", EN_COLA: "En cola", PENDIENTE: "Pendiente",
  CONFIRMADA: "Confirmada", RECHAZADA: "Rechazada", EN_REVISION: "En revisión", FALLIDA: "Fallida", CONCILIADA: "Conciliada",
};

export function AuditPagination({ page, hasNext, changePage }: { page: number; hasNext: boolean; changePage: (page: number) => void }) {
  if (page === 0 && !hasNext) return null;
  return <nav aria-label="Paginación" className="flex flex-wrap items-center gap-2">
    <Button type="button" variant="outline" disabled={page === 0} onClick={() => changePage(page - 1)}>Anterior</Button>
    <span className="px-2 text-sm">Página {page + 1}</span>
    <Button type="button" variant="outline" disabled={!hasNext} onClick={() => changePage(page + 1)}>Siguiente</Button>
  </nav>;
}
