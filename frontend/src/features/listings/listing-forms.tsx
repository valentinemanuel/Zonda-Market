import { useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { ProfileInput, profileControl } from "@/features/auth/profile-fields";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import {
  createListing, editListing, saveSku,
  type Listing, type ListingCategory, type PublicationFields, type Sku,
} from "./listing-api";

const messages: Record<string, string> = {
  INVALID_REQUEST: "Revisá los valores ingresados.",
  STOCK_ADJUSTMENT_INVALID: "Ingresá un ajuste distinto de cero y un motivo válido.",
  INSUFFICIENT_STOCK: "El ajuste dejaría el stock disponible por debajo de cero.",
  STOCK_LIMIT_EXCEEDED: "El ajuste supera el máximo de stock disponible.",
  IDEMPOTENCY_KEY_REUSED: "La clave de este ajuste ya se usó con otros datos.",
  IMAGE_INVALID: "Elegí una imagen JPEG, PNG o WebP válida.",
  IMAGE_DIMENSIONS_INVALID: "La imagen admite hasta 12 megapíxeles y 8192 píxeles por lado.",
  IMAGE_TOO_LARGE: "La imagen admite hasta 5 MiB.",
  IMAGE_LIMIT_REACHED: "La publicación admite hasta seis imágenes.",
  IMAGE_REQUIRED: "Agregá otra imagen o archivá la ficha antes de retirar esta foto.",
  IMAGE_VALIDATION_BUSY: "La validación está ocupada. Volvé a intentar.",
  MEDIA_UNAVAILABLE: "El proveedor de imágenes no está disponible. Volvé a intentar.",
  LISTING_INCOMPLETE: "Completá la ficha, una variante vigente y al menos una imagen para publicar.",
  UNAUTHENTICATED: "Tu sesión venció. Volvé a ingresar.",
  SPECS_FORMAT_INVALID: "Ingresá un objeto JSON válido para las especificaciones.",
  CATEGORY_NOT_LEAF: "Elegí una categoría final disponible.",
  CATEGORY_SKUS_INCOMPATIBLE: "La categoría elegida debe admitir todas las variantes guardadas.",
  LISTING_TEXT_INVALID: "Ingresá un título y una descripción válidos.",
  SPECS_TOO_LARGE: "Las especificaciones admiten hasta 8 KiB de JSON.",
  SPECS_INVALID: "Revisá el contenido de las especificaciones.",
  SKU_CODE_IN_USE: "Ese código SKU ya está en uso.",
  SKU_COMBINATION_IN_USE: "Esa combinación de atributos ya existe en esta publicación.",
  SKU_ATTRIBUTES_INVALID: "Elegí valores válidos para todos los atributos de la categoría.",
  VERSION_CONFLICT: "Hay cambios más recientes. Recargá la publicación para seguir editando.",
};

export function useSave() {
  const submitting = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ code: string; message: string }>();
  const [success, setSuccess] = useState("");

  async function submit(action: () => Promise<void>, confirmation: string) {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError(undefined);
    setSuccess("");
    try {
      await action();
      setSuccess(confirmation);
    } catch (failure) {
      const code = failure instanceof InvalidSpecs ? "SPECS_FORMAT_INVALID"
        : failure instanceof ApiError ? failure.status === 401 ? "UNAUTHENTICATED" : failure.status === 413 ? "IMAGE_TOO_LARGE" : failure.code ?? "" : "";
      const message = messages[code] ?? "No pudimos guardar los cambios. Volvé a intentar.";
      setError({ code, message });
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  return { busy, error, success, submit };
}

function SaveFeedback({
  state, reload, errorId,
}: { state: ReturnType<typeof useSave>; reload?: () => void; errorId: string }) {
  return (
    <>
      <p role="status" className="text-sm">{state.busy ? "Guardando…" : state.success}</p>
      {state.error && <p id={errorId} role="alert" className="text-sm text-destructive">{state.error.message}</p>}
      {state.error?.code === "VERSION_CONFLICT" && reload &&
        <Button type="button" variant="outline" onClick={reload}>Recargar publicación</Button>}
      {state.error?.code === "UNAUTHENTICATED" && <Link to="/ingresar" className="underline">Ingresar</Link>}
    </>
  );
}

class InvalidSpecs extends Error {}

function parseSpecs(value: string): PublicationFields["specs"] {
  try {
    const parsed: unknown = JSON.parse(value);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as PublicationFields["specs"];
    }
  } catch {
    throw new InvalidSpecs();
  }
  throw new InvalidSpecs();
}

export function PublicationForm({
  listing, categories, saved, reload,
}: {
  listing?: Listing;
  categories: ListingCategory[];
  saved: (listing: Listing) => void;
  reload?: () => void;
}) {
  const state = useSave();
  const [version, setVersion] = useState(listing?.version ?? 0);
  const categoryMissing = listing && !categories.some((category) => category.id === listing.categoryId);
  const specsInvalid = state.error?.code === "SPECS_FORMAT_INVALID";
  const initialSpecs = JSON.stringify(listing?.specs ?? {}, null, 2);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    void state.submit(async () => {
      const specs = String(form.get("specs"));
      const fields = {
        categoryId: String(form.get("categoryId")),
        title: String(form.get("title")),
        description: String(form.get("description")),
      };
      const result = listing
        ? await editListing(listing, specs === initialSpecs ? fields : { ...fields, specs: parseSpecs(specs) }, version)
        : await createListing({ ...fields, specs: parseSpecs(specs) });
      setVersion(result.version);
      saved(result);
    }, "Ficha guardada.");
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <fieldset disabled={state.busy} className="min-w-0 space-y-4">
        <div>
          <label className="mb-1.5 block text-sm font-medium" htmlFor="listing-category">Categoría</label>
          <select className={profileControl} id="listing-category" name="categoryId" required defaultValue={categoryMissing ? "" : listing?.categoryId ?? ""}>
            <option value="" disabled>Elegí una categoría</option>
            {categories.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
          </select>
        </div>
        <ProfileInput id="listing-title" name="title" label="Título" defaultValue={listing?.title} maxLength={160} required />
        <div>
          <label className="mb-1.5 block text-sm font-medium" htmlFor="listing-description">Descripción</label>
          <textarea className={profileControl} id="listing-description" name="description" rows={4}
            defaultValue={listing?.description} maxLength={5000} required />
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium" htmlFor="listing-specs">Especificaciones</label>
          <textarea className={profileControl + " font-mono text-sm"} id="listing-specs" name="specs" rows={4}
            aria-describedby={specsInvalid ? "listing-specs-help listing-form-error" : "listing-specs-help"}
            aria-invalid={specsInvalid || undefined} defaultValue={initialSpecs} required />
          <p id="listing-specs-help" className="text-sm text-muted-foreground">
            Formato JSON. Por ejemplo, <code>{'{"material": "algodón"}'}</code>. Usá <code>{"{}"}</code> si está vacío.
          </p>
        </div>
      </fieldset>
      <Button type="submit" className="min-h-11" disabled={state.busy || state.error?.code === "VERSION_CONFLICT"}>
        {listing ? "Guardar ficha" : "Crear borrador"}
      </Button>
      <SaveFeedback state={state} reload={reload} errorId="listing-form-error" />
    </form>
  );
}

export function attributeEntries<T>(attributes: Record<string, T>) {
  return Object.entries(attributes).sort(([left], [right]) => left.localeCompare(right, "es-AR"));
}

export function attributeLabel(key: string) {
  return key.charAt(0).toUpperCase() + key.slice(1);
}

export function SkuForm({
  listingId, category, sku, saved, reload,
}: {
  listingId: string;
  category: ListingCategory;
  sku?: Sku;
  saved: (sku: Sku) => void;
  reload: () => void;
}) {
  const state = useSave();
  const [version, setVersion] = useState(sku?.version ?? 0);
  const attributesError = state.error?.code === "SKU_ATTRIBUTES_INVALID" || state.error?.code === "SKU_COMBINATION_IN_USE";
  const fields = attributeEntries(category.attributeSchema);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const element = event.currentTarget;
    const form = new FormData(element);
    const attributes = Object.fromEntries(fields.map(([key]) => [key, String(form.get("attribute-" + key))]));
    const price = String(form.get("price")).replace(",", ".");
    void state.submit(async () => {
      const result = await saveSku(listingId, sku
        ? { expectedVersion: version, attributes, price }
        : { code: String(form.get("code")), initialStock: Number(form.get("initialStock")), attributes, price }, sku?.id);
      setVersion(result.version);
      if (!sku) element.reset();
      saved(result);
    }, sku ? "Variante actualizada." : "Variante agregada.");
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <fieldset disabled={state.busy} className="grid min-w-0 gap-4 sm:grid-cols-2">
        {!sku && <ProfileInput id="sku-code" name="code" label="Código SKU" maxLength={64} required
          pattern="[A-Za-z0-9]([A-Za-z0-9_]|-){0,63}" title="Letras, números, guion o guion bajo. Empezá con una letra o un número."
          aria-invalid={state.error?.code === "SKU_CODE_IN_USE" || undefined}
          aria-describedby={state.error?.code === "SKU_CODE_IN_USE" ? "sku-form-error" : undefined} />}
        {fields.map(([key, options], index) => (
          <div key={key}>
            <label className="mb-1.5 block text-sm font-medium" htmlFor={"sku-attribute-" + index}>{attributeLabel(key)}</label>
            <select className={profileControl} id={"sku-attribute-" + index} name={"attribute-" + key} required
              aria-invalid={attributesError || undefined} aria-describedby={attributesError ? "sku-form-error" : undefined}
              defaultValue={sku?.attributes[key] ?? ""}>
              <option value="" disabled>Elegí un valor</option>
              {options.map((option) => <option key={option}>{option}</option>)}
            </select>
          </div>
        ))}
        <ProfileInput id="sku-price" name="price" label="Precio (ARS)" inputMode="decimal" pattern="[0-9]{1,17}([.,][0-9]{1,2})?"
          title="Ingresá un importe positivo con hasta dos decimales." defaultValue={sku?.price} required />
        {!sku && <ProfileInput id="sku-stock" name="initialStock" label="Stock inicial" type="number" min={0}
          max={2147483647} step={1} defaultValue={0} required />}
      </fieldset>
      <Button type="submit" className="min-h-11" disabled={state.busy || state.error?.code === "VERSION_CONFLICT"}>
        {sku ? "Guardar variante" : "Agregar variante"}
      </Button>
      <SaveFeedback state={state} reload={reload} errorId="sku-form-error" />
    </form>
  );
}
