import { ListChecks, Plus, X } from "lucide-react";
import { useEffect, useRef } from "react";
import { ProfileInput } from "@/features/auth/profile-fields";
import { Button } from "@/shared/ui/button";

export type SpecificationRow = {
  id: number; name: string; originalName?: string; text: string; original?: unknown; useOriginal: boolean;
};
export class SpecificationError extends Error {}

function grouped(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function specificationRows(specs: Record<string, unknown>): SpecificationRow[] {
  return Object.entries(specs).map(([name, original], id) => ({
    id, name, originalName: name, original, useOriginal: true,
    text: original === null || grouped(original) ? "" : String(original),
  }));
}

export function readSpecifications(rows: SpecificationRow[]) {
  const entries = new Map<string, unknown>();
  for (const row of rows) {
    const name = row.name === row.originalName ? row.name : row.name.trim();
    const value = row.useOriginal ? row.original : row.text.trim();
    if (row.originalName === undefined && !name && !value) continue;
    if ((!name && row.originalName !== row.name) || (!row.useOriginal && !value)) {
      throw new SpecificationError("Completá el nombre y el valor de cada característica.");
    }
    if (entries.has(name)) throw new SpecificationError("Cada característica debe tener un nombre distinto.");
    entries.set(name, value);
  }
  return Object.fromEntries(entries);
}

export function SpecificationValue({ value }: { value: unknown }) {
  if (grouped(value)) {
    return (
      <ul className="mt-1 space-y-1 border-l pl-2 text-sm">
        {Object.entries(value).map(([name, entry]) => (
          <li key={name} className="[overflow-wrap:anywhere]">
            {!Array.isArray(value) && <span className="font-medium">{name} </span>}<SpecificationValue value={entry} />
          </li>
        ))}
        {Object.keys(value).length === 0 && <li>Sin datos</li>}
      </ul>
    );
  }
  return <span className="whitespace-pre-wrap">{value === null ? "Sin valor" : typeof value === "boolean" ? value ? "Sí" : "No" : String(value)}</span>;
}

const suggestions = ["Marca", "Material", "Medidas"];

export function SpecificationsEditor({ rows, changed }: {
  rows: SpecificationRow[];
  changed: (rows: SpecificationRow[]) => void;
}) {
  const nextId = useRef(rows.length);
  const added = useRef<{ id: number; field: "name" | "value" } | null>(null);
  const addButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!added.current) return;
    document.getElementById("spec-" + added.current.field + "-" + added.current.id)?.focus();
    added.current = null;
  }, [rows]);

  function add(name = "") {
    const id = nextId.current++;
    added.current = { id, field: name ? "value" : "name" };
    changed([...rows, { id, name, text: "", useOriginal: false }]);
  }

  function update(id: number, values: Partial<SpecificationRow>) {
    changed(rows.map((row) => row.id === id ? { ...row, ...values } : row));
  }

  function remove(id: number) {
    changed(rows.filter((row) => row.id !== id));
    addButton.current?.focus();
  }

  const available = suggestions.filter((suggestion) => !rows.some(({ name }) => name.trim().toLocaleLowerCase("es-AR") === suggestion.toLocaleLowerCase("es-AR")));

  return (
    <section className="space-y-4 rounded-xl border bg-card p-4 sm:p-6" aria-labelledby="specifications-title">
      <div className="flex items-start gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-lg bg-secondary text-accent-foreground"><ListChecks size={19} aria-hidden="true" /></span>
        <div className="space-y-1">
          <h2 id="specifications-title" className="font-semibold">Características del producto</h2>
          <p className="text-sm text-muted-foreground">Agregá datos como material, marca o medidas. Son opcionales.</p>
        </div>
      </div>
      {available.length > 0 && (
        <div className="flex flex-wrap gap-1.5" aria-label="Características sugeridas">
          {available.map((name) => <Button key={name} type="button" variant="secondary" size="sm"
            aria-label={"Agregar " + name} onClick={() => add(name)}><Plus />{name}</Button>)}
        </div>
      )}
      {rows.length > 0 && (
        <ul className="divide-y rounded-xl border bg-muted/35">
          {rows.map((row, index) => (
            <li key={row.id} className="grid items-start gap-3 p-3 sm:grid-cols-[minmax(0,.85fr)_minmax(0,1.15fr)_auto] sm:p-4">
              <ProfileInput id={"spec-name-" + row.id} label={"Característica " + (index + 1)} placeholder="Ej. Material"
                value={row.name} onChange={(event) => update(row.id, { name: event.target.value })} />
              {row.useOriginal && grouped(row.original) ? (
                <div className="min-w-0 space-y-2">
                  <details className="rounded-lg border bg-card p-2">
                    <summary className="cursor-pointer text-sm font-medium">Ver valores agrupados</summary>
                    <SpecificationValue value={row.original} />
                  </details>
                  <Button type="button" variant="outline" size="sm" onClick={() => {
                    added.current = { id: row.id, field: "value" };
                    update(row.id, { useOriginal: false });
                  }}>Reemplazar por texto</Button>
                </div>
              ) : (
                <ProfileInput id={"spec-value-" + row.id} label={"Valor " + (index + 1)} placeholder="Ej. Algodón"
                  value={row.text} onChange={(event) => update(row.id, { text: event.target.value, useOriginal: false })} />
              )}
              <Button type="button" variant="ghost" className="min-h-9 justify-self-start text-destructive sm:mt-6"
                aria-label={"Quitar característica " + (index + 1)} onClick={() => remove(row.id)}><X />Quitar</Button>
            </li>
          ))}
        </ul>
      )}
      <Button ref={addButton} type="button" variant="outline" className="min-h-11 text-primary" onClick={() => add()}>
        <Plus /> Agregar característica
      </Button>
    </section>
  );
}
