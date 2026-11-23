import "@fontsource-variable/inter";
import "@/index.css";
import { Package, ShoppingCart } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Link, Route, Routes, useLocation, useParams, useSearchParams } from "react-router-dom";
import { formattedPrice, listingPublicPath } from "@/features/listings/public-listing-format";
import { Button } from "@/shared/ui/button";
import { Card } from "@/shared/ui/card";
import logo from "../public/brand/zonda-isotipo.svg";
import { categories, photoUrl, products } from "./catalog";

const repository = "https://github.com/valentinemanuel/Zonda-Market";
const storageKey = "zonda-market-portfolio-cart-v1";
const stock = 5;
const control = "min-h-11 w-full rounded-lg border border-input bg-card px-3 font-normal";
const price = (amount: number) => formattedPrice(amount.toFixed(2));
const searchable = (value: string) => value.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
type Product = (typeof products)[number];
type Item = { slug: string; variant: string; quantity: number };

function readCart(): Item[] {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(storageKey) ?? "[]");
    if (!Array.isArray(saved)) return [];
    return products.flatMap(({ slug, variants }) => variants.flatMap((variant) => {
      const entry = saved.find((item: Item | null) => item?.slug === slug && item?.variant === variant);
      return entry && Number.isInteger(entry.quantity) && entry.quantity > 0
        ? [{ slug, variant, quantity: Math.min(stock, entry.quantity) }] : [];
    }));
  } catch {
    return [];
  }
}

function ProductPhoto({ product, inCatalog = false }: { product: Product; inCatalog?: boolean }) {
  const [failed, setFailed] = useState(false);
  return <div className="grid aspect-square w-full place-items-center overflow-hidden rounded-lg bg-muted">
    {failed ? <div className="grid justify-items-center gap-2 p-3 text-center text-sm text-muted-foreground">
      <Package aria-hidden="true" /> Imagen no disponible
    </div> : <img src={photoUrl(product.photo, inCatalog ? 480 : 1000)} alt={inCatalog ? "" : product.title}
      width={inCatalog ? 480 : 1000} height={inCatalog ? 480 : 1000} loading={inCatalog ? "lazy" : "eager"}
      decoding="async" className="size-full object-cover" onError={() => setFailed(true)} />}
  </div>;
}

function Catalog() {
  const [query, setQuery] = useSearchParams();
  const results = useRef<HTMLParagraphElement>(null);
  const term = query.get("q") ?? "";
  const category = categories.find((entry) => entry === query.get("categoria")) ?? "";
  const ascending = query.get("orden") === "precio";
  const words = searchable(term.trim()).split(/\s+/u);
  const matches = products.filter((product) => {
    const text = searchable(`${product.title} ${product.description} ${product.category} ${product.seller}`);
    return (!category || category === product.category) && words.every((word) => text.includes(word));
  });
  if (ascending) matches.sort((first, second) => first.price - second.price);

  return <>
    <section className="bg-hero text-white">
      <div className="store-frame grid max-w-6xl gap-3 py-10 sm:py-14">
        <p className="text-sm font-semibold text-brand-gold">Demo interactiva</p>
        <h1 className="font-wordmark text-4xl font-extrabold sm:text-5xl">Explorá Zonda Market</h1>
        <p className="max-w-xl text-hero-muted">Descubrí productos ficticios, elegí variantes y probá el carrito de este marketplace.</p>
      </div>
    </section>
    <section className="store-frame max-w-6xl py-8" aria-labelledby="catalog-title">
      <h2 id="catalog-title" className="mb-4 text-2xl font-bold">Catálogo</h2>
      <form key={query.toString()} aria-label="Filtrar catálogo" className="mb-5 grid items-end gap-3 rounded-panel border bg-card p-4 sm:grid-cols-[2fr_1fr_1fr_auto]"
        onSubmit={(event) => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          const next = new URLSearchParams();
          for (const field of ["q", "categoria", "orden"]) {
            const value = String(form.get(field) ?? "").trim();
            if (value) next.set(field, value);
          }
          results.current?.focus();
          void setQuery(next);
        }}>
        <div className="grid gap-1 text-sm font-semibold">
          <label htmlFor="catalog-search">Buscar productos</label>
          <input id="catalog-search" name="q" type="search" defaultValue={term} maxLength={100} enterKeyHint="search"
            autoCapitalize="none" spellCheck={false} placeholder="Ej. lámpara" className={control} />
        </div>
        <div className="grid gap-1 text-sm font-semibold">
          <label htmlFor="catalog-category">Categoría</label>
          <select id="catalog-category" name="categoria" defaultValue={category} className={control}>
            <option value="">Todas</option>
            {categories.map((entry) => <option key={entry}>{entry}</option>)}
          </select>
        </div>
        <div className="grid gap-1 text-sm font-semibold">
          <label htmlFor="catalog-sort">Orden</label>
          <select id="catalog-sort" name="orden" defaultValue={ascending ? "precio" : ""} className={control}>
            <option value="">Destacados</option><option value="precio">Menor precio</option>
          </select>
        </div>
        <Button type="submit" className="min-h-11">Aplicar filtros</Button>
      </form>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
        <p ref={results} tabIndex={-1} role="status">{matches.length === 1 ? "1 producto" : `${matches.length} productos`}</p>
        {query.size > 0 && <Link to="/" className="font-semibold text-primary underline">Limpiar filtros</Link>}
      </div>
      {matches.length === 0 ? <Card className="p-6">No encontramos productos. Probá con otro término o categoría.</Card> :
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          {matches.map((product) => <li key={product.slug} className="min-w-0">
            <Card className="h-full gap-0 p-0">
              <Link to={listingPublicPath(product.slug)} state={{ catalogSearch: query.toString() }}
                className="group flex h-full flex-col gap-2 rounded-panel p-2.5 sm:p-3">
                <ProductPhoto product={product} inCatalog />
                <p className="text-sm text-muted-foreground">{product.seller}</p>
                <h3 className="font-semibold group-hover:underline">{product.title}</h3>
                <p className="mt-auto border-t pt-2 text-lg font-extrabold tabular-nums sm:text-xl">{price(product.price)}</p>
              </Link>
            </Card>
          </li>)}
        </ul>}
    </section>
  </>;
}

function ProductPage({ cart, update }: { cart: Item[]; update: (item: Item) => void }) {
  const { slug } = useParams();
  const product = products.find((entry) => entry.slug === slug);
  const { state } = useLocation();
  const search = typeof state?.catalogSearch === "string" ? state.catalogSearch : "";
  if (!product) return <Missing title="No encontramos esa publicación" />;

  return <section className="store-frame max-w-6xl py-8">
    <Link to={{ pathname: "/", search }} className="mb-4 inline-block text-sm font-semibold text-primary underline">Volver al catálogo</Link>
    <Card className="grid items-start gap-6 p-4 sm:grid-cols-2 sm:p-6">
      <ProductPhoto key={product.slug} product={product} />
      <div className="grid gap-4 sm:py-4">
        <p className="text-sm text-muted-foreground">{product.category} · {product.seller}</p>
        <h1 className="font-wordmark text-3xl font-extrabold">{product.title}</h1>
        <p className="text-2xl font-extrabold">{price(product.price)}</p>
        <p className="text-muted-foreground">{product.description}</p>
        <ProductOptions key={product.slug} product={product} cart={cart} update={update} />
      </div>
    </Card>
  </section>;
}

function ProductOptions({ product, cart, update }: { product: Product; cart: Item[]; update: (item: Item) => void }) {
  const [variant, setVariant] = useState(product.variants[0]);
  const quantity = cart.find((item) => item.slug === product.slug && item.variant === variant)?.quantity ?? 0;
  return <div className="grid max-w-sm gap-2">
    <label htmlFor="product-variant" className="text-sm font-semibold">{product.option}</label>
    <select id="product-variant" value={variant} onChange={(event) => setVariant(event.currentTarget.value)} className={control}>
      {product.variants.map((entry) => <option key={entry}>{entry}</option>)}
    </select>
    <Button type="button" className="min-h-11" aria-disabled={quantity >= stock} onClick={() => {
      if (quantity < stock) update({ slug: product.slug, variant, quantity: quantity + 1 });
    }}>Agregar al carrito</Button>
    <p className="text-sm text-muted-foreground">{quantity >= stock ? "Alcanzaste el stock de esta variante." : `${stock - quantity} unidades disponibles para agregar.`}</p>
    {quantity > 0 && <Link to="/carrito" className="w-fit text-sm font-semibold text-primary underline">Ver carrito</Link>}
  </div>;
}

function CartPage({ cart, update }: { cart: Item[]; update: (item: Item) => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const lines = cart.flatMap((item) => {
    const product = products.find(({ slug }) => slug === item.slug);
    return product ? [{ ...item, product }] : [];
  });
  const subtotal = lines.reduce((total, { product, quantity }) => total + product.price * quantity, 0);
  return <section className="store-frame max-w-6xl py-8">
    <h1 ref={heading} tabIndex={-1} className="mb-5 font-wordmark text-4xl font-extrabold">Tu carrito</h1>
    {lines.length === 0 ? <Card className="items-start gap-4 p-6">
      <p>Tu carrito está vacío.</p>
      <Button asChild><Link to="/">Explorar el catálogo</Link></Button>
    </Card> : <div className="grid items-start gap-4 md:grid-cols-[2fr_1fr]">
      <ul className="grid gap-3">
        {lines.map(({ product, ...item }) => {
          const label = `${product.title}, ${item.variant}`;
          return <li key={`${item.slug}:${item.variant}`}>
            <Card className="gap-2 p-4">
              <Link to={listingPublicPath(item.slug)} className="w-fit font-semibold underline-offset-4 hover:underline">{product.title}</Link>
              <p className="text-sm text-muted-foreground">{product.seller} · {item.variant}</p>
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div className="flex items-end gap-3">
                  <label className="grid gap-1 text-sm font-semibold">Cantidad<span className="sr-only"> de {label}</span>
                    <select className={control} value={item.quantity}
                      onChange={(event) => update({ ...item, quantity: Number(event.currentTarget.value) })}>
                      {Array.from({ length: stock }, (_, index) => <option key={index}>{index + 1}</option>)}
                    </select>
                  </label>
                  <Button type="button" variant="outline" className="min-h-11" aria-label={`Quitar ${label}`} onClick={() => {
                    update({ ...item, quantity: 0 });
                    heading.current?.focus();
                  }}>Quitar</Button>
                </div>
                <p className="font-bold tabular-nums">{price(product.price * item.quantity)}</p>
              </div>
            </Card>
          </li>;
        })}
      </ul>
      <Card className="gap-3 p-5">
        <h2 className="text-lg font-bold">Resumen</h2>
        <p className="flex flex-wrap justify-between gap-2 border-t pt-3">Subtotal <strong>{price(subtotal)}</strong></p>
        <p className="text-sm text-muted-foreground">Carrito de prueba. Esta demo termina antes del pago.</p>
        <Button asChild variant="outline"><Link to="/">Seguir explorando</Link></Button>
      </Card>
    </div>}
  </section>;
}

function Missing({ title }: { title: string }) {
  return <section className="store-frame max-w-6xl space-y-4 py-8">
    <h1 className="text-2xl font-bold">{title}</h1>
    <Button asChild variant="outline"><Link to="/">Volver al catálogo</Link></Button>
  </section>;
}

function App() {
  const [cart, setCart] = useState(readCart);
  const [notice, setNotice] = useState("");
  const main = useRef<HTMLElement>(null);
  const { pathname } = useLocation();
  const lastPath = useRef(pathname);
  const units = cart.reduce((total, item) => total + item.quantity, 0);

  useEffect(() => {
    if (lastPath.current === pathname) return;
    lastPath.current = pathname;
    main.current?.focus();
    window.scrollTo(0, 0);
  }, [pathname]);

  function update(item: Item) {
    const remaining = cart.filter((entry) => entry.slug !== item.slug || entry.variant !== item.variant);
    const next = item.quantity > 0 ? [...remaining, item] : remaining;
    setCart(next);
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
      const count = next.reduce((total, entry) => total + entry.quantity, 0);
      setNotice(`Carrito actualizado. ${count} ${count === 1 ? "unidad" : "unidades"}.`);
    } catch {
      setNotice("Carrito actualizado. Este navegador no pudo guardarlo para la próxima visita.");
    }
  }

  return <div className="flex min-h-dvh flex-col">
    <a href="#contenido" className="sr-only focus:not-sr-only focus:absolute focus:z-10 focus:bg-card focus:p-3">Saltar al contenido</a>
    <header className="store-header text-brand-ink">
      <nav aria-label="Principal" className="store-frame flex min-h-16 max-w-6xl items-center justify-between gap-2">
        <Link to="/" className="flex items-center gap-2 rounded px-1 font-wordmark text-xl font-extrabold sm:text-2xl">
          <img src={logo} alt="" width="32" height="32" /> Zonda Market
          <span className="hidden rounded bg-white/70 px-2 py-1 font-sans text-xs font-bold min-[360px]:inline">Demo</span>
        </Link>
        <Link to="/carrito" aria-current={pathname === "/carrito" ? "page" : undefined} className="store-header-link text-sm hover:bg-white/25">
          <ShoppingCart className="size-5" aria-hidden="true" /> Carrito ({units})
        </Link>
      </nav>
    </header>
    <p role="status" className="sr-only">{notice}</p>
    <main id="contenido" ref={main} tabIndex={-1} className="flex-1">
      <Routes>
        <Route path="/" element={<Catalog />} />
        <Route path="/publicaciones/:slug" element={<ProductPage cart={cart} update={update} />} />
        <Route path="/carrito" element={<CartPage cart={cart} update={update} />} />
        <Route path="*" element={<Missing title="Página no encontrada" />} />
      </Routes>
    </main>
    <footer className="border-t bg-card">
      <div className="store-frame flex max-w-6xl flex-wrap justify-between gap-3 py-5 text-sm text-muted-foreground">
        <p>Demo de Zonda Market · Productos y precios ficticios</p>
        <a href={repository} className="font-semibold text-primary underline">Código fuente en GitHub</a>
      </div>
    </footer>
  </div>;
}

const root = document.getElementById("root");
if (!root) throw new Error("Falta el nodo raíz de la demo");
createRoot(root).render(<BrowserRouter><App /></BrowserRouter>);
