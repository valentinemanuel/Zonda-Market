import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, test, vi } from "vitest";
import { orderKey, type SubOrder } from "@/features/checkout/checkout-api";
import { ApiError } from "@/shared/api/http-client";
import { openDispute } from "./dispute-api";
import { BuyerDisputeAction } from "./dispute-order-action";

vi.mock(import("./dispute-api"), async (original) => ({
  ...await original(),
  openDispute: vi.fn(),
}));

const order = "orden-prueba";
const revision = 3;
const sub: SubOrder = {
  id: "suborden-prueba", version: 2, status: "PREPARANDO", shipment: null, reviewed: null, dispute: null,
  address: { id: "direccion", street: "Calle Ficticia 42", city: "La Plata", provinceCode: "AR-B", postalCode: "1900", country: "AR", recipient: null, phone: null, deliveryDetails: null },
  group: { sellerId: "vendedor", sellerName: "Tienda ficticia", items: [], merchandise: "100.00", shipping: "0.00", subtotal: "100.00" },
};

const current = orderKey(revision, order);
const previous = orderKey(revision - 1, order);
const unrelated = orderKey(revision, "otra-orden");

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

async function openForm() {
  const user = userEvent.setup();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  for (const key of [current, previous, unrelated]) client.setQueryData(key, { order });
  render(<QueryClientProvider client={client}>
    <MemoryRouter>
      <Routes>
        <Route path="/" element={<BuyerDisputeAction sub={sub} orderId={order} paid revision={revision}
          serverNow="2026-11-23T12:00:00Z" />} />
        <Route path="/reclamos/reclamo-prueba" element={<h1>Detalle del reclamo</h1>} />
      </Routes>
    </MemoryRouter>
  </QueryClientProvider>);
  await user.click(screen.getByRole("button", { name: "Abrir un reclamo" }));
  const reason = screen.getByRole<HTMLInputElement>("textbox", { name: /Motivo/ });
  const description = screen.getByRole<HTMLTextAreaElement>("textbox", { name: /Qué pasó/ });
  const submit = screen.getByRole("button", { name: "Confirmar reclamo" });
  return { reason, description, submit, user, client };
}

describe("apertura de un reclamo privado", () => {
  test("enfoca el motivo y exige los campos antes de enviar", async () => {
    const { reason, description, user, submit } = await openForm();
    expect(document.activeElement).toBe(reason);
    await user.type(reason, "Problema con el pedido");
    await user.click(submit);
    expect(description.validity.valueMissing).toBe(true);
    expect(openDispute).not.toHaveBeenCalled();
  });

  test("conserva los datos ante un fallo y al recuperar invalida solo la caché de la orden y sesión vigentes", async () => {
    vi.mocked(openDispute).mockRejectedValueOnce(new ApiError(503, null))
      .mockResolvedValueOnce({ id: "reclamo-prueba", status: "ABIERTA" });
    const { reason, description, submit, user, client } = await openForm();
    await user.type(reason, "  Problema con el pedido  ");
    await user.type(description, "  Detalle ficticio  ");
    await user.click(submit);
    expect((await screen.findByRole("alert")).textContent).toContain("No pudimos abrir el reclamo.");
    expect(reason.value).toBe("  Problema con el pedido  ");
    expect(description.value).toBe("  Detalle ficticio  ");
    expect(client.getQueryState(current)?.isInvalidated).toBe(false);

    await user.click(submit);
    await screen.findByRole("heading", { name: "Detalle del reclamo" });
    expect(openDispute).toHaveBeenCalledTimes(2);
    expect(openDispute).toHaveBeenLastCalledWith(sub.id, "Problema con el pedido", "Detalle ficticio", 2);
    await waitFor(() => expect(client.getQueryState(current)?.isInvalidated).toBe(true));
    expect(client.getQueryState(previous)?.isInvalidated).toBe(false);
    expect(client.getQueryState(unrelated)?.isInvalidated).toBe(false);
  });
});
