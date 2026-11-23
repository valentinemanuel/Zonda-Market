import { useRef, useState, type FormEvent } from "react";
import { Button } from "@/shared/ui/button";
import type { Category, CategoryInput } from "./category-admin-api";

const control = "min-h-12 w-full rounded-lg border border-input bg-background px-3 py-2 text-base focus-visible:outline-2 focus-visible:outline-ring";
const percent = new Intl.NumberFormat("es-AR", { style: "percent", maximumFractionDigits: 2 });
type Attribute = { id: number; name: string; values: string };

export function CategoryForm({ category, categories, pending, stale, cancel, save }: {
  category: Category | null;
  categories: Category[];
  pending: boolean;
  stale: boolean;
  cancel: () => void;
  save: (input: CategoryInput) => void;
}) {
  const [bps, setBps] = useState(String(category?.commissionBps ?? 0));
  const [attributes, setAttributes] = useState<Attribute[]>(() => Object.entries(category?.attributeSchema ?? {})
    .map(([name, values], id) => ({ id, name, values: values.join("\n") })));
  const nextId = useRef(attributes.length);
  const [error, setError] = useState("");
  const errorText = useRef<HTMLParagraphElement>(null);
  const byId = new Map(categories.map((row) => [row.id, row]));
  const parentOptions = categories.flatMap((row) => {
    const path = [row.name];
    for (let ancestor = row; ;) {
      if (ancestor.id === category?.id) return [];
      const parent = byId.get(ancestor.parentId ?? "");
      if (!parent) break;
      path.unshift(parent.name);
      ancestor = parent;
    }
    return [{ id: row.id, path: path.join(" / "), activeListings: row.activeListings }];
  }).sort((a, b) => a.path.localeCompare(b.path, "es-AR"));
  const preview = attributes.filter((field) => field.name.trim() && field.values.trim());

  function update(id: number, patch: Partial<Attribute>) {
    setAttributes((fields) => fields.map((field) => field.id === id ? { ...field, ...patch } : field));
    setError("");
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || stale) return;
    const form = new FormData(event.currentTarget);
    const fields = attributes.map(({ name, values }) => ({
      name: name.trim(),
      values: values.split("\n").map((value) => value.trim()).filter(Boolean),
    }));
    if (new Set(fields.map(({ name }) => name)).size !== fields.length || fields.some(({ name, values }) =>
      !name || values.length === 0 || values.length > 100 || new Set(values).size !== values.length
      || values.some((value) => value.length > 120))) {
      setError("Usá nombres únicos y de 1 a 100 valores distintos por atributo, con hasta 120 caracteres por valor.");
      errorText.current?.focus();
      return;
    }
    setError("");
    save({
      name: String(form.get("name")).trim(),
      slug: String(form.get("slug")),
      parentId: String(form.get("parentId")) || null,
      commissionBps: Number(bps),
      attributeSchema: Object.fromEntries(fields.map(({ name, values }) => [name, values])),
    });
  }

  return (
    <form method="post" action={category ? `/api/v1/admin/categories/${category.id}` : "/api/v1/admin/categories"}
      aria-labelledby="category-editor" className="space-y-4" onSubmit={submit}>
      <fieldset disabled={pending || stale} className="min-w-0 space-y-4">
        <legend className="sr-only">Datos de la categoría</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="grid gap-1 text-sm font-medium">Nombre (obligatorio)
            <input name="name" className={control} defaultValue={category?.name} required maxLength={120} />
          </label>
          <label className="grid gap-1 text-sm font-medium">Slug (obligatorio)
            <input name="slug" className={control} defaultValue={category?.slug} required maxLength={120}
              pattern="[a-z0-9]+(?:\x2D[a-z0-9]+)*" aria-describedby="category-slug-help"
              autoCapitalize="none" autoCorrect="off" spellCheck={false} />
            <span id="category-slug-help" className="text-xs text-muted-foreground">Minúsculas, números y guiones. Ejemplo: audio-portatil.</span>
          </label>
          <label className="grid min-w-0 gap-1 text-sm font-medium">Categoría superior
            <select name="parentId" className={control} defaultValue={category?.parentId ?? ""}>
              <option value="">Ninguna (nivel raíz)</option>
              {parentOptions.map((row) => <option key={row.id} value={row.id} disabled={row.activeListings > 0}>
                {row.path}{row.activeListings > 0 ? " (con publicaciones activas)" : ""}
              </option>)}
            </select>
          </label>
          <label className="grid gap-1 text-sm font-medium">Comisión en puntos básicos (obligatoria)
            <input name="commissionBps" className={control} type="number" min={0} max={10_000} step={1}
              required value={bps} onChange={(event) => setBps(event.target.value)} aria-describedby="category-rate-help" />
            <span id="category-rate-help" className="text-xs text-muted-foreground">
              100 puntos equivalen a 1 %. Tasa ingresada: {percent.format(Number(bps) / 10_000)}.
            </span>
          </label>
        </div>
        <fieldset className="min-w-0 space-y-3">
          <legend className="font-semibold">Atributos de variantes</legend>
          <p className="text-sm text-muted-foreground">Cada variante debe incluir todos los atributos definidos. Escribí un valor por línea.</p>
          {attributes.map((field, index) => (
            <fieldset key={field.id} className="min-w-0 space-y-2 rounded-xl border px-3 pb-3">
              <legend className="px-1 text-sm font-medium">Atributo {index + 1}</legend>
              <label className="grid gap-1 text-sm">Nombre del atributo
                <input name={`attribute-${field.id}`} className={control} required maxLength={120}
                  value={field.name} onChange={(event) => update(field.id, { name: event.target.value })} />
              </label>
              <label className="grid gap-1 text-sm">Valores permitidos
                <textarea name={`values-${field.id}`} className={control} required rows={3} value={field.values}
                  onChange={(event) => update(field.id, { values: event.target.value })} />
              </label>
              <Button type="button" variant="outline" size="sm" aria-label={`Quitar atributo ${index + 1}`}
                onClick={() => { setAttributes((fields) => fields.filter((item) => item.id !== field.id)); setError(""); }}>Quitar</Button>
            </fieldset>
          ))}
          <Button type="button" variant="outline" disabled={attributes.length >= 16}
            onClick={() => {
              const id = nextId.current++;
              setAttributes((fields) => [...fields, { id, name: "", values: "" }]);
            }}>Agregar atributo</Button>
        </fieldset>
      </fieldset>
      <div className="rounded-xl bg-muted p-3 text-sm">
        <h4 className="font-semibold">Vista previa de atributos</h4>
        {preview.length > 0 ? <dl className="space-y-1 break-words">{preview.map((field) =>
          <div key={field.id}><dt className="inline font-medium">{field.name.trim()}</dt>{" "}
            <dd className="inline">{field.values.split("\n").map((value) => value.trim()).filter(Boolean).join(", ")}</dd></div>,
        )}</dl> : <p>Sin atributos obligatorios.</p>}
      </div>
      <p ref={errorText} tabIndex={-1} role="alert" className={error ? "text-sm text-destructive" : "sr-only"}>{error}</p>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={pending || stale}>{pending ? "Guardando…" : "Guardar categoría"}</Button>
        <Button type="button" variant="outline" disabled={pending} onClick={cancel}>Cancelar</Button>
      </div>
    </form>
  );
}
