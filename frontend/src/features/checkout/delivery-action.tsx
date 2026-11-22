import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { confirmDelivery, orderKey, type Order, type SubOrder } from "./checkout-api";
import { shipmentError } from "./shipment-timeline";

export function DeliveryAction({ sub, orderId, revision }: { sub: SubOrder; orderId: string; revision: number }) {
  const client = useQueryClient();
  const queryKey = orderKey(revision, orderId);
  const delivery = useMutation({
    mutationFn: () => confirmDelivery(sub.id, sub.version),
    onSuccess: async (updated) => {
      await client.cancelQueries({ queryKey });
      client.setQueryData<Order>(queryKey, (order) => order && ({
        ...order, subOrders: order.subOrders.map((item) => item.id === updated.id ? { ...item, ...updated } : item),
      }));
      void client.invalidateQueries({ queryKey });
    },
  });
  if (sub.status !== "DESPACHADA") return null;
  const conflict = delivery.error instanceof ApiError && delivery.error.status === 409;

  return <div className="space-y-2">
    {delivery.isError && <p role="alert" className="text-destructive">
      {shipmentError(delivery.error, "No pudimos confirmar la recepción. Volvé a intentar.")}
    </p>}
    <Button type="button" className="min-h-12 w-full sm:w-auto" disabled={delivery.isPending} onClick={() => {
      if (conflict) {
        delivery.reset();
        void client.invalidateQueries({ queryKey });
      } else {
        delivery.mutate();
      }
    }}>{conflict ? "Actualizar suborden" : delivery.isPending ? "Confirmando recepción…" : "Confirmar recepción"}</Button>
    {delivery.isPending && <p className="sr-only" role="status">Confirmando recepción…</p>}
  </div>;
}
