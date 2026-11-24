import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, MapPin, Pencil, Phone, Plus, Trash2 } from "lucide-react";
import { type FormEvent, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Navigate } from "react-router-dom";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { type Address, addressesKey, getAddresses, removeAddress, saveAddress, setDefaultAddress } from "./profile-api";
import { AccountView } from "./account-layout";
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

type AddressDialog =
  | { mode: "create" }
  | { mode: "edit" | "remove"; address: Address };

export function AddressesPage() {
  const revision = useSyncExternalStore(subscribeToSession, getSessionRevision);
  return <Addresses key={revision} revision={revision} />;
}

function Addresses({ revision }: { revision: number }) {
  const [editor, setEditor] = useState<AddressDialog>();
  const [notice, setNotice] = useState("");
  const addButton = useRef<HTMLButtonElement>(null);
  const client = useQueryClient();
  const queryKey = addressesKey(revision);
  const addresses = useQuery({ queryKey, queryFn: ({ signal }) => getAddresses(signal), retry: false });
  const makeDefault = useMutation({
    mutationFn: setDefaultAddress,
    onSuccess: async (address) => {
      setNotice("Dirección predeterminada actualizada.");
      await client.invalidateQueries({ queryKey });
      document.getElementById("address-" + address.id)?.focus();
    },
  });

  if (addresses.error instanceof ApiError && addresses.error.status === 401) {
    return <Navigate to="/ingresar" replace />;
  }

  return (
    <AccountView title="Mis direcciones" description="Elegí dónde querés recibir tus compras."
      action={addresses.isSuccess && (
        <Button ref={addButton} type="button" className="min-h-11 bg-brand text-brand-ink hover:bg-orange-300"
          onClick={() => setEditor({ mode: "create" })}>
          <Plus aria-hidden="true" />Agregar dirección
        </Button>
      )}>
      {notice && <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-success">{notice}</p>}
      {addresses.isPending && <p role="status" className="rounded-panel border bg-card p-5 text-muted-foreground">Cargando direcciones…</p>}
      {addresses.isError && (
        <div className="space-y-3 rounded-panel border bg-card p-5">
          <p role="alert" className="text-destructive">No pudimos cargar tus direcciones.</p>
          <Button type="button" onClick={() => void addresses.refetch()}>Reintentar</Button>
        </div>
      )}
      {addresses.data?.length === 0 && (
        <div className="grid justify-items-center gap-3 rounded-panel border border-dashed bg-card px-5 py-10 text-center">
          <span className="grid size-12 place-items-center rounded-xl bg-accent text-accent-foreground"><MapPin aria-hidden="true" /></span>
          <p className="font-semibold">Todavía no guardaste direcciones.</p>
          <p className="text-sm text-muted-foreground">Agregá un domicilio para seleccionarlo al comprar.</p>
        </div>
      )}
      {addresses.data && addresses.data.length > 0 && (
        <ul aria-label="Direcciones guardadas" className="grid gap-4 lg:grid-cols-2">
          {addresses.data.map((address) => {
            const province = provinces[address.provinceCode as keyof typeof provinces] ?? address.provinceCode;
            const updating = makeDefault.variables === address.id;
            return (
              <li key={address.id} className="flex min-w-0 flex-col rounded-panel border bg-card shadow-panel">
                <div className="flex flex-wrap items-center justify-between gap-2 px-5 pt-5">
                  <span className="grid size-9 place-items-center rounded-lg bg-accent text-accent-foreground"><MapPin aria-hidden="true" className="size-[18px]" /></span>
                  {address.defaultAddress && <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-success">
                    <Check aria-hidden="true" className="size-3.5" />Predeterminada
                  </span>}
                </div>
                <div className="grow space-y-1 px-5 py-4 text-sm wrap-anywhere">
                  <h2 id={"address-" + address.id} tabIndex={-1} className="text-base font-bold">{address.recipient || address.street}</h2>
                  {address.recipient && <p className="font-medium">{address.street}</p>}
                  {address.deliveryDetails && <p className="text-muted-foreground">{address.deliveryDetails}</p>}
                  <p className="text-muted-foreground">{address.city}, {province} · {address.postalCode}</p>
                  {address.phone && <p className="inline-flex items-center gap-2 pt-1.5 text-muted-foreground">
                    <Phone aria-hidden="true" className="size-3.5 shrink-0" />{address.phone}
                  </p>}
                  {(!address.recipient || !address.phone) && <p className="pt-1.5 text-xs text-muted-foreground">Completá los datos de contacto para la entrega.</p>}
                </div>
                <div className="flex flex-wrap items-center gap-x-2 border-t px-3 py-1.5">
                  <Button type="button" variant="ghost" size="sm" className="min-h-11 gap-2"
                    aria-label={`Editar ${address.street}`} onClick={() => setEditor({ mode: "edit", address })}>
                    <Pencil aria-hidden="true" />Editar
                  </Button>
                  <Button type="button" variant="ghost" size="sm" className="min-h-11 gap-2 text-destructive hover:text-destructive"
                    aria-label={`Eliminar ${address.street}`} onClick={() => setEditor({ mode: "remove", address })}>
                    <Trash2 aria-hidden="true" />Eliminar
                  </Button>
                  {!address.defaultAddress && (
                    <Button type="button" variant="ghost" size="sm" className="min-h-11 text-primary whitespace-normal"
                      aria-describedby={"address-" + address.id} aria-disabled={makeDefault.isPending}
                      onClick={() => { if (!makeDefault.isPending) { setNotice(""); makeDefault.mutate(address.id); } }}>
                      {updating && makeDefault.isPending ? "Actualizando…" : "Marcar como predeterminada"}
                    </Button>
                  )}
                  {updating && makeDefault.isError && <p role="alert" className="w-full px-2 pb-2 text-sm text-destructive">
                    No pudimos actualizar la dirección predeterminada. Volvé a intentar.
                  </p>}
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {editor && <AddressEditor editor={editor} first={addresses.data?.length === 0}
        onClose={() => setEditor(undefined)}
        onSaved={async (message) => {
          setNotice(message);
          await client.invalidateQueries({ queryKey });
          if (editor.mode === "remove") addButton.current?.focus();
        }} />}
    </AccountView>
  );
}

function AddressEditor({ editor, first, onClose, onSaved }: {
  editor: AddressDialog; first: boolean; onClose: () => void; onSaved: (message: string) => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const address = editor.mode === "create" ? undefined : editor.address;
  const deleting = editor.mode === "remove";
  const title = deleting ? "Eliminar dirección" : address ? "Editar dirección" : "Nueva dirección";
  const alwaysDefault = first || address?.defaultAddress === true;

  const mutation = useMutation({
    mutationFn: async (data: FormData) => {
      if (deleting) {
        await removeAddress(editor.address.id);
        return "Dirección eliminada.";
      }
      await saveAddress({
        recipient: String(data.get("recipient")), phone: String(data.get("phone")),
        street: String(data.get("street")), deliveryDetails: String(data.get("deliveryDetails")),
        city: String(data.get("city")), provinceCode: String(data.get("provinceCode")),
        postalCode: String(data.get("postalCode")), country: "AR",
        defaultAddress: data.has("defaultAddress"),
      }, address?.id);
      return address ? "Dirección actualizada." : "Dirección agregada.";
    },
    onSuccess: async (message) => {
      closeEditor();
      await onSaved(message);
    },
  });

  useEffect(() => {
    const element = dialog.current;
    element?.querySelector("[data-initial-focus]")?.setAttribute("autofocus", "");
    element?.showModal();
    return () => element?.close();
  }, []);

  function closeEditor() {
    dialog.current?.close();
    onClose();
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate(new FormData(event.currentTarget));
  }

  return (
    <dialog ref={dialog} aria-labelledby="address-dialog-title" onCancel={(event) => {
      event.preventDefault();
      if (!mutation.isPending) closeEditor();
    }}
      className="address-dialog fixed inset-0 m-auto max-h-[calc(100dvh-1.5rem)] w-[calc(100%-1.5rem)] max-w-xl overflow-y-auto rounded-2xl border bg-card p-5 text-foreground shadow-float backdrop:bg-brand-ink/55 sm:p-6">
      <h2 id="address-dialog-title" className="font-wordmark text-2xl font-bold tracking-tight">{title}</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {deleting ? `¿Querés quitar ${editor.address.street} de tus direcciones guardadas?` : "Ingresá los datos de entrega en Argentina."}
      </p>
      <form className="mt-5 space-y-4" aria-busy={mutation.isPending} onSubmit={submit}>
        {!deleting && <fieldset disabled={mutation.isPending} className="grid gap-4 sm:grid-cols-2">
          <ProfileInput id="address-recipient" name="recipient" label="Nombre y apellido del destinatario" data-initial-focus
            autoComplete="shipping name" maxLength={120} defaultValue={address?.recipient ?? ""} required />
          <ProfileInput id="address-phone" name="phone" label="Teléfono" type="tel" autoComplete="shipping tel"
            minLength={6} maxLength={32} defaultValue={address?.phone ?? ""} required />
          <ProfileInput id="address-street" name="street" label="Calle y número" autoComplete="shipping address-line1"
            maxLength={140} defaultValue={address?.street} required />
          <ProfileInput id="address-details" name="deliveryDetails" label="Piso / Depto / Referencias de entrega"
            autoComplete="shipping address-line2" maxLength={250} defaultValue={address?.deliveryDetails ?? ""} />
          <div>
            <label htmlFor="address-province" className="mb-1.5 block text-sm font-medium">Provincia</label>
            <select id="address-province" name="provinceCode" className={profileControl} autoComplete="shipping address-level1"
              defaultValue={address?.provinceCode ?? ""} required>
              <option value="" disabled>Elegí una provincia</option>
              {Object.entries(provinces).map(([code, name]) => <option key={code} value={code}>{name}</option>)}
            </select>
          </div>
          <ProfileInput id="address-city" name="city" label="Localidad" autoComplete="shipping address-level2"
            maxLength={80} defaultValue={address?.city} required />
          <ProfileInput id="address-postal-code" name="postalCode" label="Código postal" autoComplete="shipping postal-code"
            pattern="[0-9]{4}|[A-Za-z][0-9]{4}[A-Za-z]{3}" maxLength={8} defaultValue={address?.postalCode} required />
          <label className="flex min-h-11 items-center gap-2 rounded-lg bg-muted/70 px-3 text-sm font-medium sm:col-span-2">
            <input type="checkbox" name="defaultAddress" defaultChecked={alwaysDefault} disabled={alwaysDefault}
              className="size-4 shrink-0 accent-primary" />
            Usar como dirección principal
          </label>
        </fieldset>}
        {mutation.isError && <p role="alert" className="text-sm text-destructive">
          {deleting ? "No pudimos eliminar la dirección. Volvé a intentar."
            : mutation.error instanceof ApiError && mutation.error.status === 400
              ? "Revisá el teléfono, el código postal y los demás datos ingresados."
              : "No pudimos guardar la dirección. Volvé a intentar."}
        </p>}
        <div className="flex flex-wrap justify-end gap-2 border-t pt-4">
          <Button type="button" variant="outline" className="min-h-11" disabled={mutation.isPending} data-initial-focus={deleting || undefined}
            onClick={closeEditor}>Cancelar</Button>
          <Button type="submit" variant={deleting ? "destructive" : "default"} className="min-h-11" disabled={mutation.isPending}>
            {deleting ? mutation.isPending ? "Eliminando…" : "Confirmar eliminación"
              : mutation.isPending ? "Guardando…" : "Guardar dirección"}
          </Button>
        </div>
      </form>
    </dialog>
  );
}
