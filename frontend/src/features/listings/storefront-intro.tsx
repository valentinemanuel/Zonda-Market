import { ArrowRight, Backpack, ChevronLeft, ChevronRight, ClipboardList, House, Laptop, PackageCheck,
  ShoppingBag, ShoppingCart, Store, Tag, UserRound } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { categoryCatalogPath, type CatalogCategoryNode } from "./storefront-categories";

const slides = [
  { label: "TECNOLOGÍA Y HOGAR", title: "Encontrá lo que buscás", accent: "comprá a tu manera",
    copy: "Explorá publicaciones de distintos vendedores y seguí cada pedido desde tu cuenta.",
    image: 8316281, feature: "Tecnología para todos los días", name: "Tecnología" },
  { label: "HOGAR Y DECORACIÓN", title: "Dale a tu espacio", accent: "un toque propio",
    copy: "Encontrá objetos, accesorios y detalles para hacer que tu casa se sienta tuya.",
    image: 9733307, feature: "Hallazgos para tu casa", name: "Hogar y decoración" },
  { label: "MODA Y ACCESORIOS", title: "Elegí tu próximo", accent: "favorito de todos los días",
    copy: "Recorré indumentaria, calzado y accesorios de vendedores independientes.",
    image: 18946896, feature: "Encontrá tu estilo", name: "Calzado" },
];

const shortcuts = [
  { label: "Mi cuenta", description: "Datos y direcciones", action: "Ver mi perfil", to: "/cuenta", icon: UserRound },
  { label: "Tus compras", description: "Seguí tus pedidos", action: "Ver pedidos", to: "/mis-ordenes", icon: ClipboardList },
  { label: "Hasta $20.000", description: "Buscá por precio", action: "Explorar", to: "/catalogo?max=20000", icon: Tag },
  { label: "Disponibles", description: "Productos con stock", action: "Ver catálogo", to: "/catalogo?inStock=true", icon: PackageCheck },
  { label: "Publicá y vendé", description: "Tus publicaciones", action: "Abrir herramientas", to: "/vendedor", icon: Store },
  { label: "Tu carrito", description: "Revisá tus productos", action: "Abrir carrito", to: "/carrito", icon: ShoppingCart },
];

export function StorefrontIntro({ categories }: { categories: CatalogCategoryNode[] }) {
  const [index, setIndex] = useState(0);
  const slide = slides[index];
  const featured = categories.find(({ category }) => category.name === slide.name);
  const featuredPath = featured ? categoryCatalogPath(featured.category.id) : "/catalogo?q=" + encodeURIComponent(slide.name);

  return <div>
    <section aria-roledescription="carrusel" aria-label="Explorá Zonda Market"
      className="store-hero relative text-white">
      <div className="store-frame grid items-center gap-5 pt-7 pb-14 sm:gap-7 md:min-h-[22.5rem] md:grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] md:pt-8 md:pb-14">
        <div className="min-w-0">
          <p className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/10 px-3 py-1 text-[0.68rem] font-bold text-brand-gold">
            <span aria-hidden="true" className="size-1.5 rounded-full bg-brand" /> {slide.label}
          </p>
          <p className="mt-3 max-w-xl font-wordmark text-[clamp(2rem,4.25vw,4.5rem)] leading-[0.98] font-extrabold tracking-[-0.045em]">
            {slide.title} <span className="block bg-linear-to-r from-orange-400 to-brand-gold bg-clip-text text-transparent">{slide.accent}</span>
          </p>
          <p className="mt-3 max-w-md text-sm leading-relaxed text-slate-200 sm:text-base">{slide.copy}</p>
          <div className="mt-5 flex flex-wrap gap-2 sm:gap-3">
            <Button asChild className="min-h-11 bg-primary px-4 shadow-float">
              <a href="#productos">Explorar catálogo <ArrowRight aria-hidden="true" /></a>
            </Button>
            <Button asChild variant="outline" className="min-h-11 border-white/15 bg-white/10 text-white hover:bg-hero-panel hover:text-white">
              <Link to={featuredPath}>Ver categoría</Link>
            </Button>
          </div>
        </div>
        <Link to={featuredPath} aria-label={slide.feature} className="relative isolate overflow-hidden rounded-[1.2rem] border border-white/15 bg-white/10 p-2">
          <div className="relative isolate aspect-[4/3] overflow-hidden rounded-xl bg-hero md:aspect-[4/3]">
            <img key={slide.image} alt="" fetchPriority={index === 0 ? "high" : "auto"} decoding="async"
              src={`https://images.pexels.com/photos/${slide.image}/pexels-photo-${slide.image}.jpeg?auto=compress&cs=tinysrgb&w=1000`}
              className="store-hero-image store-hero-art absolute inset-0 size-full rounded-xl object-cover brightness-75" />
            <div className="absolute inset-0 bg-linear-to-t from-brand-ink via-brand-ink/15 to-brand-ink/20" />
            <span className="absolute inset-x-4 bottom-4 flex items-end justify-between gap-3">
              <span className="text-lg leading-tight font-bold md:text-xl">{slide.feature}</span>
              <span className="grid size-8 shrink-0 place-items-center rounded-full bg-white text-brand-ink"><ArrowRight aria-hidden="true" className="size-4" /></span>
            </span>
          </div>
        </Link>
      </div>
      <div className="absolute inset-x-0 bottom-5 flex items-center justify-center gap-1.5 md:bottom-6">
        <button type="button" className="grid size-9 place-items-center rounded-full bg-brand-ink/80 hover:bg-brand-ink"
          aria-label="Destacado anterior" onClick={() => setIndex((current) => (current + slides.length - 1) % slides.length)}>
          <ChevronLeft aria-hidden="true" className="size-4" />
        </button>
        {slides.map(({ name }, position) => <button type="button" key={name} aria-label={name}
          aria-pressed={position === index} onClick={() => setIndex(position)} className="grid size-7 place-items-center rounded-full">
          <span aria-hidden="true" className={`h-1.5 rounded-full ${position === index ? "w-5 bg-brand" : "w-1.5 bg-white/55"}`} />
        </button>)}
        <button type="button" className="grid size-9 place-items-center rounded-full bg-brand-ink/80 hover:bg-brand-ink"
          aria-label="Siguiente destacado" onClick={() => setIndex((current) => (current + 1) % slides.length)}>
          <ChevronRight aria-hidden="true" className="size-4" />
        </button>
      </div>
    </section>
    <section aria-label="Accesos a la tienda" className="relative -mt-2 grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-6">
      {shortcuts.map(({ label, description, action, to, icon: Icon }) => (
        <Link key={label} to={to} className="min-w-0 rounded-panel border bg-card p-3 shadow-panel hover:border-primary/40">
          <span className="mb-1.5 grid size-8 place-items-center rounded-full bg-accent text-foreground"><Icon aria-hidden="true" className="size-4" /></span>
          <span className="block text-xs font-bold">{label}</span>
          <span className="mt-0.5 block text-xs text-muted-foreground">{description}</span>
          <span className="mt-1.5 inline-flex items-center gap-1 text-xs font-semibold text-primary">{action} <ChevronRight aria-hidden="true" className="size-3" /></span>
        </Link>
      ))}
    </section>
    {categories.length > 0 && <section aria-labelledby="categorias-populares" className="mt-8 space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h2 id="categorias-populares" className="store-section-title">Explorá por categorías populares</h2>
        <a href="#filtros" className="inline-flex items-center gap-1 rounded text-sm font-semibold text-primary">Ver filtros <ArrowRight aria-hidden="true" className="size-3.5" /></a>
      </div>
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {categories.slice(0, 6).map(({ category }, position) => {
          const icons = [Backpack, ShoppingBag, PackageCheck, House, Tag, Laptop];
          const Icon = icons[position];
          return <li key={category.id}>
            <Link to={categoryCatalogPath(category.id)}
              className="flex min-h-24 flex-col items-center justify-center gap-2 rounded-panel border bg-card p-2 text-center text-xs font-bold hover:border-primary/50 hover:bg-accent lg:min-h-28">
              <span className="grid size-10 place-items-center rounded-full bg-background"><Icon aria-hidden="true" className="size-5" /></span>
              {category.name}
            </Link>
          </li>;
        })}
      </ul>
    </section>}
  </div>;
}

export function StorefrontEnd() {
  return <section aria-label="Publicar en Zonda Market" className="flex flex-wrap items-center gap-4 rounded-panel bg-linear-to-r from-brand to-orange-400 p-5 text-brand-ink shadow-float sm:p-7">
    <Store aria-hidden="true" className="size-10 rounded-lg bg-white/80 p-2" />
    <h2 className="flex-1 basis-52 font-wordmark text-xl font-extrabold sm:text-2xl">Publicá tus productos en Zonda Market</h2>
    <Button asChild className="bg-brand-ink text-white hover:bg-hero-panel"><Link to="/vendedor">Empezar a vender <ArrowRight aria-hidden="true" /></Link></Button>
  </section>;
}
