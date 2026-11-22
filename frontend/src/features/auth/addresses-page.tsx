import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MapPin, Pencil, Plus, Trash2 } from "lucide-react";
import { type FormEvent, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Navigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { type Address, getAddresses, removeAddress, saveAddress } from "./profile-api";
import { AccountLayout, AccountSection } from "./account-layout";
import { ProfileInput, profileControl } from "./profile-fields";
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
  const [editor, setEditor] = useState<Address | "new">();
  const editing = editor === "new" ? undefined : editor;
  const [confirmId, setConfirmId] = useState<string>();
  const [notice, setNotice] = useState("");
  const form = useRef<HTMLFormElement>(null);
  const addButton = useRef<HTMLButtonElement>(null);
  const editorTrigger = useRef<HTMLButtonElement>(null);
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
      closeEditor();
      await client.invalidateQueries({ queryKey });
    },
  });
  const remove = useMutation({
    mutationFn: removeAddress,
    onSuccess: async (_, id) => {
      if (editing?.id === id) closeEditor();
      setConfirmId(undefined);
      setNotice("Dirección eliminada.");
      await client.invalidateQueries({ queryKey });
      addButton.current?.focus();
    },
  });
  const busy = save.isPending || remove.isPending;

  useEffect(() => {
    if (editor) form.current?.querySelector("input")?.focus();
  }, [editor]);

  useEffect(() => {
    if (editor || busy || !editorTrigger.current) return;
    const trigger = editorTrigger.current;
    editorTrigger.current = null;
    (trigger.isConnected ? trigger : addButton.current)?.focus();
  }, [editor, busy]);

  function openEditor(next: Address | "new", trigger: HTMLButtonElement) {
    editorTrigger.current = trigger;
    save.reset();
    setConfirmId(undefined);
    setEditor(next);
  }

  function closeEditor() {
    setEditor(undefined);
  }

  if (addresses.error instanceof ApiError && addresses.error.status === 401) {
    return <Navigate to="/ingresar" replace />;
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setNotice("");
    save.mutate(new FormData(event.currentTarget));
  }

  return (
    <AccountLayout title="Mis direcciones" description="Guardá y actualizá tus domicilios."
      action={addresses.isSuccess && (
        <Button ref={addButton} type="button" className="min-h-11 bg-brand text-brand-ink hover:bg-orange-300"
          disabled={busy} onClick={(event) => openEditor("new", event.currentTarget)}>
          <Plus aria-hidden="true" />
          Agregar dirección
        </Button>
      )}>
      <div className="space-y-4">
        {notice && <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-success">{notice}</p>}
        <AccountSection id="saved-addresses" title="Direcciones guardadas" icon={MapPin}>
          {addresses.isPending && <p role="status" className="text-muted-foreground">Cargando direcciones…</p>}
          {addresses.isError && (
            <div className="space-y-3">
              <p role="alert" className="text-destructive">No pudimos cargar tus direcciones.</p>
              <Button type="button" onClick={() => void addresses.refetch()}>Reintentar</Button>
            </div>
          )}
          {addresses.data?.length === 0 && (
            <div className="rounded-xl border border-dashed bg-background px-5 py-8 text-center">
              <p className="font-medium">Todavía no guardaste direcciones.</p>
            </div>
          )}
          {addresses.data && addresses.data.length > 0 && (
            <ul className="grid gap-3 lg:grid-cols-2">
              {addresses.data.map((address) => (
                <li key={address.id} className="min-w-0 rounded-xl border bg-background p-4">
                  <p className="font-semibold wrap-anywhere">{address.street}</p>
                  <p className="mt-1 text-sm text-muted-foreground wrap-anywhere">{address.city}, {provinces[address.provinceCode as keyof typeof provinces]} · {address.postalCode}</p>
                  {confirmId === address.id ? (
                    <div className="mt-2 space-y-2">
                      <p className="text-sm">¿Eliminar esta dirección?</p>
                      {remove.isError && <p role="alert" className="text-sm text-destructive">No pudimos eliminar la dirección. Volvé a intentar.</p>}
                      <div className="flex flex-wrap gap-2">
                        <Button type="button" variant="destructive" className="min-h-11" disabled={busy}
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
                        aria-label={`Editar ${address.street}`} onClick={(event) => openEditor(address, event.currentTarget)}>
                        <Pencil aria-hidden="true" />
                        Editar
                      </Button>
                      <Button type="button" variant="ghost" className="min-h-11 text-destructive hover:text-destructive" disabled={busy}
                        aria-label={`Eliminar ${address.street}`} onClick={() => { remove.reset(); setConfirmId(address.id); }}>
                        <Trash2 aria-hidden="true" />
                        Eliminar
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </AccountSection>
        {editor && (
          <AccountSection id="edit-address" title={editing ? "Editar dirección" : "Nueva dirección"} icon={editing ? Pencil : Plus}>
            <form ref={form} key={editing?.id ?? "new"} className="grid gap-4 sm:grid-cols-2"
              aria-busy={save.isPending} onSubmit={submit}>
              <p className="text-sm text-muted-foreground sm:col-span-2">País: Argentina</p>
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
              {save.isError && <p role="alert" className="text-sm text-destructive sm:col-span-2">
                No pudimos guardar la dirección. Revisá los datos y volvé a intentar.
              </p>}
              <div className="flex flex-wrap gap-2 sm:col-span-2">
                <Button type="submit" className="min-h-11" disabled={busy}>
                  {save.isPending ? "Guardando…" : "Guardar dirección"}
                </Button>
                <Button type="button" variant="outline" disabled={busy} className="min-h-11"
                  onClick={closeEditor}>{editing ? "Cancelar edición" : "Cancelar"}</Button>
              </div>
            </form>
          </AccountSection>
        )}
      </div>
    </AccountLayout>
  );
}
