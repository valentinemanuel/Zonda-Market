import { useQuery } from "@tanstack/react-query";
import { Check, Package, ShoppingCart } from "lucide-react";
import { useState } from "react";
import { Popover } from "radix-ui";
import { Link, useNavigate } from "react-router-dom";
import { cartEditError, findCartItem, signInRequired, useCart, useCartEdit } from "@/features/cart/cart-api";
import { profileControl } from "@/features/auth/profile-fields";
import { Button } from "@/shared/ui/button";
import { fetchPublicListing } from "./listing-api";
import { attributeEntries, attributeLabel } from "./listing-forms";
import { formattedPrice, listingPublicPath } from "./public-listing-format";

export type CatalogItem = {
  id: string; slug: string; title: string; categoryId: string; seller: string; sellerName: string;
  thumbnailUrl: string | null; price: string; available: boolean; condition: string | null; quickSkuId: string | null;
};

const conditionSuffix = /\s+·\s+(nuevo|como nuevo|buen estado|segunda mano|usado)\s*$/iu;

export function CatalogProductCard({ item, catalogSearch }: { item: CatalogItem; catalogSearch: string }) {
  const title = item.title.replace(conditionSuffix, "");
  const [whole, cents] = formattedPrice(item.price).split(",");
  const condition = item.condition;
  return <li className="store-product relative h-full min-w-0 rounded-panel border border-border bg-card shadow-panel">
    <Link to={listingPublicPath(item.slug)} state={{ catalogSearch }}
      className="group flex h-full min-w-0 flex-col rounded-panel p-2.5 sm:p-3">
      <div className="store-photo relative grid aspect-square w-full place-items-center overflow-hidden rounded-lg bg-muted">
        {item.thumbnailUrl ? <img src={item.thumbnailUrl} alt="" loading="lazy" decoding="async" className="size-full object-cover" />
          : <Package aria-hidden="true" className="text-muted-foreground" />}
        {condition && <span className={`absolute top-2 left-2 rounded-full border border-black/10 px-2 py-0.5 text-xs font-semibold capitalize shadow-sm ${condition === "nuevo" ? "bg-white text-brand-ink" : "bg-[#fff2df] text-[#7c3611]"}`}>
          {condition}
        </span>}
      </div>
      <div className="flex flex-1 flex-col gap-1 pt-2.5">
        <p className="truncate text-xs text-muted-foreground">{item.sellerName}</p>
        <h3 className="line-clamp-2 text-sm leading-5 font-semibold [overflow-wrap:anywhere] group-hover:underline">{title}</h3>
        <div className="mt-auto pt-2">
          <p className="border-t pt-2 text-xl leading-tight font-extrabold tracking-tight tabular-nums [overflow-wrap:anywhere] sm:text-2xl">
            {whole}{cents && <span className="align-super text-[0.6em] font-semibold text-muted-foreground">,{cents}</span>}
          </p>
          <p className={`mt-1 text-xs font-semibold ${item.available ? "text-success" : "text-muted-foreground"}`}>
            {item.available ? "Disponible" : "Sin stock"}
          </p>
        </div>
      </div>
    </Link>
    {item.available && <div className="pointer-events-none absolute inset-x-2.5 top-2.5 aspect-square sm:inset-x-3 sm:top-3">
      <QuickCartAction item={item} title={title} catalogSearch={catalogSearch} />
    </div>}
  </li>;
}

function QuickCartAction({ item, title, catalogSearch }: { item: CatalogItem; title: string; catalogSearch: string }) {
  const { query: cart, revision } = useCart();
  return <QuickCartControl key={revision} item={item} title={title} catalogSearch={catalogSearch}
    cart={cart} revision={revision} />;
}

function QuickCartControl({ item, title, catalogSearch, cart, revision }: {
  item: CatalogItem; title: string; catalogSearch: string;
  cart: ReturnType<typeof useCart>["query"]; revision: number;
}) {
  const navigate = useNavigate();
  const edit = useCartEdit(revision);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState("");
  const [notice, setNotice] = useState("");
  const variants = useQuery({
    queryKey: ["listing-public", item.slug],
    queryFn: ({ signal }) => fetchPublicListing(item.slug, signal),
    enabled: open,
    retry: false,
  });
  const filters = new URLSearchParams(catalogSearch);
  const options = variants.data?.skus.filter((sku) => sku.available
    && (!filters.has("min") || Number(sku.price) >= Number(filters.get("min")))
    && (!filters.has("max") || Number(sku.price) <= Number(filters.get("max")))
    && filters.getAll("attr").every((entry) => {
      const split = entry.indexOf(":");
      return sku.attributes[entry.slice(0, split)] === entry.slice(split + 1);
    })) ?? [];

  function requireLogin() {
    if (!signInRequired(cart.error) && !signInRequired(edit.error)) return false;
    navigate("/ingresar", { state: { cartListingSlug: item.slug, catalogSearch } });
    return true;
  }

  function add(skuId: string) {
    if (edit.isPending) return;
    setNotice("");
    if (requireLogin()) return;
    if (!cart.data) {
      setNotice("No pudimos consultar el carrito. Volvé a intentar.");
      void cart.refetch();
      return;
    }
    const entry = findCartItem(cart.data, skuId);
    const quantity = entry?.quantity ?? 0;
    if (entry?.reserved || quantity >= 99) {
      setNotice(entry?.reserved ? "Esta variante está incluida en tu reserva." : "Alcanzaste el máximo de 99 unidades.");
      return;
    }
    edit.mutate({ kind: "put", skuId, quantity: quantity + 1, expectedVersion: cart.data.version }, {
      onSuccess: () => { setOpen(false); },
    });
  }

  const label = "Agregar al carrito " + title;
  const icon = edit.isSuccess ? <Check aria-hidden="true" /> : <ShoppingCart aria-hidden="true" />;
  const error = notice || (edit.isError ? cartEditError(edit.error) : "");
  const actionStyle = "store-quick-cart pointer-events-auto absolute right-2 bottom-2 z-10 size-11 rounded-full bg-white p-0 text-brand-ink shadow-md hover:bg-accent";
  const skuId = item.quickSkuId;
  return <>
    {skuId ? <Button type="button" variant="outline" className={actionStyle}
      aria-label={label} aria-busy={edit.isPending} disabled={cart.isPending || edit.isPending} onClick={() => add(skuId)}>
      {icon}
    </Button> : <Popover.Root open={open} onOpenChange={(next) => {
      if (next && requireLogin()) return;
      setOpen(next);
      setNotice("");
      if (next) { setSelected(""); edit.reset(); }
    }}>
      <Popover.Trigger asChild>
        <Button type="button" variant="outline" className={actionStyle} aria-label={label} disabled={cart.isPending}>
          {icon}
        </Button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content align="end" sideOffset={6} aria-label="Elegí una variante"
          className="z-40 w-72 max-w-[calc(100vw-1.5rem)] space-y-3 rounded-panel border bg-card p-4 shadow-float">
          {variants.isPending ? <p role="status">Cargando variantes…</p> : variants.isError ? <>
            <p role="alert" className="text-sm text-destructive">No pudimos cargar las variantes.</p>
            <Button type="button" variant="outline" onClick={() => void variants.refetch()}>Reintentar</Button>
          </> : options.length === 0 ? <p role="status" className="text-sm">No hay variantes disponibles.</p> : <>
            <label className="grid gap-1.5 text-sm font-semibold">
              Elegí una variante
              <select className={profileControl} value={selected} onChange={(event) => setSelected(event.currentTarget.value)}>
                <option value="">Seleccioná una opción</option>
                {options.map((sku, index) => <option key={sku.id} value={sku.id}>
                  {attributeEntries(sku.attributes).map(([name, value]) => attributeLabel(name) + " " + value).join(" · ") || "Opción " + (index + 1)}
                  {" · " + formattedPrice(sku.price)}
                </option>)}
              </select>
            </label>
            <Button type="button" className="w-full" disabled={!options.some((sku) => sku.id === selected) || edit.isPending}
              onClick={() => add(selected)}>{edit.isPending ? "Agregando…" : "Agregar al carrito"}</Button>
          </>}
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>}
    {!open && error && <p role="alert" className="store-cart-feedback text-destructive">{error}</p>}
    {edit.isSuccess && <p role="status" className="store-cart-feedback text-success">Agregaste una unidad al carrito.</p>}
  </>;
}
