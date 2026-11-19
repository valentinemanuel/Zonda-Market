import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useRef, useState, useSyncExternalStore } from "react";
import { Link, Navigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { type Address, getAddresses, removeAddress, saveAddress } from "./profile-api";
import { ProfileInput, profileControl, profileLink } from "./profile-fields";
import { getSessionRevision, subscribeToSession } from "./session-api";

const provinces = {
  "AR-B": "Buenos Aires", "AR-K": "Catamarca", "AR-H": "Chaco", "AR-U": "Chubut",
  "AR-C": "Ciudad Autónoma de Buenos Aires", "AR-X": "Córdoba", "AR-W": "Corrientes",
  "AR-E": "Entre Ríos", "AR-P": "Formosa", "AR-Y": "Jujuy", "AR-L": "La Pampa",
  "AR-F": "La Rioja", "AR-M": "Mendoza", "AR-N": "Misiones", "AR-Q": "Neuquén",
  "AR-R": "Río Negro", "AR-A": "Salta", "AR-J": "San Juan", "AR-D": "San Luis",
  "AR-Z": "Santa Cruz", "AR-S": "Santa Fe", "AR-G": "Santiago del Estero",
  "AR-V": "Tierra del Fuego", "AR-T": "Tucumán",
};

export function AddressesPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  return <Addresses key={revision} revision={revision} />;
}

function Addresses({ revision }: { revision: number }) {
  const [editing, setEditing] = useState<Address>();
  const [confirmId, setConfirmId] = useState<string>();
  const [notice, setNotice] = useState("");
  const form = useRef<HTMLFormElement>(null);
  const client = useQueryClient();
  const queryKey = ["private", "addresses", revision];
  const addresses = useQuery({ queryKey, queryFn: ({ signal }) => getAddresses(signal), retry: false });
  const save = useMutation({
    mutationFn: (data: FormData) => saveAddress({
      street: String(data.get("street")), city: String(data.get("city")),
      provinceCode: String(data.get("provinceCode")), postalCode: String(data.get("postalCode")), country: "AR",
    }, editing?.id),
    onSuccess: async () => {
      setNotice(editing ? "Dirección actualizada." : "Dirección agregada.");
      form.current?.reset();
      setEditing(undefined);
      await client.invalidateQueries({ queryKey });
    },
  });
  const remove = useMutation({
    mutationFn: removeAddress,
    onSuccess: async (_, id) => {
      if (editing?.id === id) setEditing(undefined);
      setConfirmId(undefined);
      setNotice("Dirección eliminada.");
      await client.invalidateQueries({ queryKey });
    },
  });
  const busy = save.isPending || remove.isPending;

  if (addresses.error instanceof ApiError && addresses.error.status === 401) {
    return <Navigate to="/ingresar" replace />;
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setNotice("");
    save.mutate(new FormData(event.currentTarget));
  }

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Mis direcciones</h1>
      <Link to="/cuenta" className={profileLink}>Volver a Mi cuenta</Link>
      {addresses.isPending && <p role="status" className="text-muted-foreground">Cargando direcciones…</p>}
      {addresses.isError && (
        <>
          <p role="alert" className="text-destructive">No pudimos cargar tus direcciones.</p>
          <Button type="button" onClick={() => void addresses.refetch()}>Reintentar</Button>
        </>
      )}
      {notice && <p role="status">{notice}</p>}
      {addresses.data && (
        <>
          {addresses.data.length === 0 ? <p className="text-muted-foreground">Todavía no guardaste direcciones.</p> : (
            <ul className="space-y-3">
              {addresses.data.map((address) => (
                <li key={address.id} className="rounded-lg border border-border p-3">
                  <p className="font-medium break-words">{address.street}</p>
                  <p className="text-sm text-muted-foreground">{address.city}, {provinces[address.provinceCode as keyof typeof provinces]} · {address.postalCode}</p>
                  {confirmId === address.id ? (
                    <div className="mt-2 space-y-2">
                      <p className="text-sm">¿Eliminar esta dirección?</p>
                      {remove.isError && <p role="alert" className="text-sm text-destructive">No pudimos eliminar la dirección. Volvé a intentar.</p>}
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" className="min-h-11" disabled={busy}
                          onClick={() => { setNotice(""); remove.mutate(address.id); }}>
                          {remove.isPending ? "Eliminando…" : "Confirmar eliminación"}
                        </Button>
                        <Button type="button" variant="outline" className="min-h-11" disabled={busy}
                          onClick={() => setConfirmId(undefined)}>Cancelar</Button>
                      </div>
                    </div>
                  ) : (
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Button type="button" variant="outline" className="min-h-11" disabled={busy}
                        aria-label={`Editar ${address.street}`} onClick={() => { save.reset(); setEditing(address); }}>
                        Editar
                      </Button>
                      <Button type="button" variant="outline" className="min-h-11" disabled={busy}
                        aria-label={`Eliminar ${address.street}`} onClick={() => { remove.reset(); setConfirmId(address.id); }}>
                        Eliminar
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
          <form ref={form} key={editing?.id ?? "new"} className="space-y-3 border-t border-border pt-4"
            aria-busy={save.isPending} onSubmit={submit}>
            <h2 className="text-lg font-semibold">{editing ? "Editar dirección" : "Agregar dirección"}</h2>
            <p className="text-sm text-muted-foreground">País: Argentina</p>
            <ProfileInput id="street" name="street" label="Calle y número" autoComplete="street-address"
              maxLength={140} defaultValue={editing?.street} required />
            <ProfileInput id="city" name="city" label="Localidad" autoComplete="address-level2"
              maxLength={80} defaultValue={editing?.city} required />
            <div>
              <label htmlFor="province" className="mb-1.5 block text-sm font-medium">Provincia</label>
              <select id="province" name="provinceCode" className={profileControl} autoComplete="address-level1"
                defaultValue={editing?.provinceCode ?? ""} required>
                <option value="" disabled>Elegí una provincia</option>
                {Object.entries(provinces).map(([code, name]) => <option key={code} value={code}>{name}</option>)}
              </select>
            </div>
            <ProfileInput id="postal-code" name="postalCode" label="Código postal" autoComplete="postal-code"
              pattern="[0-9]{4}|[A-Za-z][0-9]{4}[A-Za-z]{3}" maxLength={8}
              defaultValue={editing?.postalCode} required />
            {save.isError && <p role="alert" className="text-sm text-destructive">
              No pudimos guardar la dirección. Revisá los datos y volvé a intentar.
            </p>}
            <div className="flex flex-wrap gap-2">
              <Button type="submit" className="min-h-11" disabled={busy}>
                {save.isPending ? "Guardando…" : "Guardar dirección"}
              </Button>
              {editing && <Button type="button" variant="outline" disabled={busy} className="min-h-11"
                onClick={() => { save.reset(); setEditing(undefined); }}>Cancelar edición</Button>}
            </div>
          </form>
        </>
      )}
    </div>
  );
}
