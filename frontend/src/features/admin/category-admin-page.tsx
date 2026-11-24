import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { AdminPage, LoadError } from "./admin-page";
import { archiveCategory, listCategories, saveCategory, type Category, type CategoryInput } from "./category-admin-api";
import { formatCommission } from "./category-commission";
import { CategoryForm } from "./category-form";

type Editor = { mode: "edit"; category: Category | null } | { mode: "archive"; category: Category };
type Change = { category: Category | null; input: CategoryInput } | { archive: Category };

const errors: Record<string, string> = {
  CATEGORY_CYCLE: "Ese movimiento formaría un ciclo.",
  CATEGORY_DEPTH: "El árbol admite hasta cuatro niveles.",
  CATEGORY_SLUG_IN_USE: "Ese slug ya está en uso.",
  CATEGORY_IN_USE: "El cambio dejaría una publicación activa o una subcategoría sin una categoría válida.",
  CATEGORY_SKUS_INCOMPATIBLE: "El esquema debe admitir las variantes guardadas por los vendedores.",
  CATEGORY_ARCHIVED: "La categoría ya está archivada.",
  VERSION_CONFLICT: "La categoría cambió en otra sesión. Cargá los datos actuales para continuar.",
  INVALID_REQUEST: "Revisá el nombre, la categoría superior, la comisión y los atributos.",
};

export function CategoryAdminPage() {
  return <AdminPage title="Categorías y comisiones">
    {(revision) => <CategoryEditor key={revision} revision={revision} />}
  </AdminPage>;
}

function CategoryEditor({ revision }: { revision: number }) {
  const client = useQueryClient();
  const queryKey = ["private", "admin-categories", revision];
  const tree = useQuery({ queryKey, queryFn: ({ signal }) => listCategories(signal), retry: false });
  const [editor, setEditor] = useState<Editor | null>(null);
  const [notice, setNotice] = useState("");
  const heading = useRef<HTMLHeadingElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const editorHeading = useRef<HTMLHeadingElement>(null);
  const [formVersion, setFormVersion] = useState(0);
  const change = useMutation({
    mutationFn: (command: Change) => "archive" in command ? archiveCategory(command.archive)
      : saveCategory(command.category, command.input),
    onSuccess: async (saved, command) => {
      setNotice(`${saved.name}: ${"archive" in command ? "categoría archivada" : "cambios guardados"}.`);
      setEditor(null);
      heading.current?.focus();
      await client.invalidateQueries({ queryKey });
    },
  });
  useEffect(() => {
    if (editor) editorHeading.current?.focus();
  }, [editor]);

  function open(next: Editor, button: HTMLButtonElement) {
    trigger.current = button;
    change.reset();
    setNotice("");
    setEditor(next);
  }

  function close() {
    setEditor(null);
    requestAnimationFrame(() => trigger.current?.focus());
  }

  async function reloadCurrent() {
    const result = await tree.refetch();
    if (!result.isSuccess || !editor?.category) return;
    const category = result.data.find((row) => row.id === editor.category?.id);
    change.reset();
    if (!category || category.archived) {
      setNotice("La categoría ya no está vigente.");
      setEditor(null);
      heading.current?.focus();
      return;
    }
    setFormVersion((version) => version + 1);
    setEditor({ ...editor, category });
  }

  function submitArchive(event: FormEvent<HTMLFormElement>, category: Category) {
    event.preventDefault();
    if (!change.isPending) change.mutate({ archive: category });
  }

  const active = (tree.data ?? []).filter((row) => !row.archived);
  const archived = (tree.data ?? []).filter((row) => row.archived);
  const errorCode = change.error instanceof ApiError ? change.error.code : null;
  const stale = errorCode === "VERSION_CONFLICT" || errorCode === "CATEGORY_ARCHIVED";

  return (
    <section className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 ref={heading} tabIndex={-1} className="text-xl font-semibold">Árbol de categorías</h2>
        {tree.isSuccess && <Button type="button" disabled={!!editor || change.isPending}
          onClick={(event) => open({ mode: "edit", category: null }, event.currentTarget)}>Nueva categoría</Button>}
      </div>
      <p className="text-sm text-muted-foreground">Se aplica la comisión de la categoría final al confirmar una compra. Las órdenes ya confirmadas conservan su tasa.</p>
      <p role="status" className={notice ? "text-sm font-medium" : "sr-only"}>{notice}</p>
      {tree.isPending && <p role="status">Cargando categorías…</p>}
      {tree.isError && <LoadError message="No pudimos cargar las categorías." retry={tree.refetch} />}
      {editor && (
        <div className="space-y-4 rounded-2xl border bg-card p-4 sm:p-6">
          <h3 id="category-editor" ref={editorHeading} tabIndex={-1} className="break-words text-lg font-semibold">
            {editor.mode === "archive" ? `Archivar ${editor.category.name}`
              : editor.category ? `Editar ${editor.category.name}` : "Nueva categoría"}
          </h3>
          {change.isError && <p role="alert" className="text-sm text-destructive">
            {(errorCode && errors[errorCode]) || "No pudimos guardar el cambio. Revisá los datos y volvé a intentar."}
          </p>}
          {stale && <Button type="button" variant="outline" disabled={tree.isFetching}
            onClick={() => void reloadCurrent()}>Cargar datos actuales</Button>}
          {editor.mode === "archive" ? (
            <form method="post" action={`/api/v1/admin/categories/${editor.category.id}/archive`}
              aria-labelledby="category-editor" className="space-y-4" onSubmit={(event) => submitArchive(event, editor.category)}>
              <p className="text-sm">La categoría dejará de aparecer en el catálogo y de estar disponible para publicaciones nuevas.</p>
              {(editor.category.hasChildren || editor.category.activeListings > 0) &&
                <p className="text-sm text-destructive">Primero mové sus subcategorías o archivá sus publicaciones activas.</p>}
              <div className="flex flex-wrap gap-2">
                <Button type="submit" variant="destructive" disabled={change.isPending || stale ||
                  editor.category.hasChildren || editor.category.activeListings > 0}>
                  {change.isPending ? "Archivando…" : "Confirmar archivo"}
                </Button>
                <Button type="button" variant="outline" disabled={change.isPending} onClick={close}>Cancelar</Button>
              </div>
            </form>
          ) : <CategoryForm key={`${editor.category?.id ?? "new"}-${formVersion}`} category={editor.category} categories={active}
            pending={change.isPending} stale={stale} cancel={close}
            save={(input) => { if (!change.isPending) change.mutate({ category: editor.category, input }); }} />}
        </div>
      )}
      {tree.isSuccess && (active.length > 0
        ? <CategoryBranch categories={active} parent={null} disabled={!!editor || change.isPending} open={open} />
        : <p className="rounded-2xl border bg-card p-4">No hay categorías vigentes.</p>)}
      {archived.length > 0 && (
        <details className="space-y-3 rounded-2xl border bg-card p-4">
          <summary className="cursor-pointer font-semibold">Archivadas ({archived.length})</summary>
          <ul className="space-y-2">{archived.map((row) => <li key={row.id} className="break-words text-sm">{row.name} · {row.slug}</li>)}</ul>
        </details>
      )}
    </section>
  );
}

function CategoryBranch({ categories, parent, disabled, open }: {
  categories: Category[];
  parent: string | null;
  disabled: boolean;
  open: (editor: Editor, trigger: HTMLButtonElement) => void;
}) {
  const children = categories.filter((row) => row.parentId === parent);
  if (children.length === 0) return null;
  return (
    <ul className={parent ? "ms-2 space-y-2 border-s ps-2 sm:ms-4 sm:ps-4" : "space-y-2"} role="list">
      {children.map((row) => (
        <li key={row.id} className="space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-card p-3 sm:p-4">
            <div className="min-w-0 break-words">
              <p className="font-semibold">{row.name}</p>
              <p className="text-sm text-muted-foreground">{row.hasChildren ? "Categoría superior" :
                `${formatCommission(row.commissionBps)} de comisión · ${row.activeListings} publicaciones activas`}</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="outline" disabled={disabled} aria-label={`Editar ${row.name}`}
                onClick={(event) => open({ mode: "edit", category: row }, event.currentTarget)}>Editar</Button>
              <Button type="button" size="sm" variant="outline" disabled={disabled} aria-label={`Archivar ${row.name}`}
                onClick={(event) => open({ mode: "archive", category: row }, event.currentTarget)}>Archivar</Button>
            </div>
          </div>
          <CategoryBranch categories={categories} parent={row.id} disabled={disabled} open={open} />
        </li>
      ))}
    </ul>
  );
}
