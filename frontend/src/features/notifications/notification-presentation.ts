import { Bell, CreditCard, MessageSquare, PackageCheck, ShieldCheck, Truck } from "lucide-react";
import type { Notification } from "./notifications-api";

export const notificationStyles = {
  PAYMENT_APPROVED: { title: "Pago aprobado", icon: CreditCard, category: "Pago", color: "bg-emerald-50 text-emerald-800" },
  SUB_ORDER_SHIPPED: { title: "Pedido despachado", icon: Truck, category: "Envío", color: "bg-sky-50 text-sky-800" },
  DELIVERY_CONFIRMED: { title: "Entrega confirmada", icon: PackageCheck, category: "Entrega", color: "bg-emerald-50 text-emerald-800" },
  DISPUTE_OPENED: { title: "Reclamo abierto", icon: MessageSquare, category: "Reclamo", color: "bg-orange-50 text-orange-900" },
  DISPUTE_ASSIGNED: { title: "Reclamo en mediación", icon: MessageSquare, category: "Reclamo", color: "bg-orange-50 text-orange-900" },
  DISPUTE_RESOLVED: { title: "Reclamo resuelto", icon: ShieldCheck, category: "Reclamo", color: "bg-emerald-50 text-emerald-800" },
} satisfies Record<Notification["type"], { title: string; icon: typeof Bell; category: string; color: string }>;
export const notificationDate = new Intl.DateTimeFormat("es-AR", {
  dateStyle: "medium", timeStyle: "short", timeZone: "America/Argentina/Buenos_Aires",
});
