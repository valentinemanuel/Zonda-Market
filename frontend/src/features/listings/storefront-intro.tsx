import { ArrowRight, Backpack, ChevronRight, ClipboardList, House, Laptop, PackageCheck,
  ShoppingBag, ShoppingCart, Store, Tag, UserRound } from "lucide-react";
import { useEffect } from "react";
import { Link, useLocation } from "react-router-dom";
import { Button } from "@/shared/ui/button";
import { categoryCatalogPath, type CatalogCategoryNode } from "./storefront-categories";
import { StorefrontCarousel } from "./storefront-carousel";

const shortcuts = [
  { label: "Mi cuenta", description: "Datos y direcciones", action: "Ver mi perfil", to: "/cuenta", icon: UserRound },
  { label: "Tus compras", description: "Seguí tus pedidos", action: "Ver pedidos", to: "/cuenta/ordenes", icon: ClipboardList },
  { label: "Hasta $20.000", description: "Buscá por precio", action: "Explorar", to: "/catalogo?max=20000", icon: Tag },
  { label: "Disponibles", description: "Productos con stock", action: "Ver catálogo", to: "/catalogo?inStock=true", icon: PackageCheck },
  { label: "Publicá y vendé", description: "Tus publicaciones", action: "Abrir herramientas", to: "/vendedor", icon: Store },
  { label: "Tu carrito", description: "Revisá tus productos", action: "Abrir carrito", to: "/carrito", icon: ShoppingCart },
];

export function StorefrontIntro({ categories }: { categories: CatalogCategoryNode[] }) {
  const { hash } = useLocation();
  const hasCategories = categories.length > 0;
  useEffect(() => {
    if (hasCategories && hash === "#categorias-populares") document.getElementById("categorias-populares")?.scrollIntoView();
  }, [hasCategories, hash]);
  return <div className="store-intro">
    <StorefrontCarousel categories={categories} />
    <section aria-label="Accesos a la tienda" className="store-frame store-shortcuts relative grid grid-cols-2 gap-2.5 sm:grid-cols-3 lg:grid-cols-6">
      {shortcuts.map(({ label, description, action, to, icon: Icon }) => (
        <Link key={label} to={to} className="min-w-0 rounded-panel border bg-card p-3 shadow-panel hover:border-primary/40">
          <span className="mb-1.5 grid size-8 place-items-center rounded-full bg-accent text-foreground"><Icon aria-hidden="true" className="size-4" /></span>
          <span className="block text-xs font-bold">{label}</span>
          <span className="mt-0.5 block text-xs text-muted-foreground">{description}</span>
          <span className="mt-1.5 inline-flex items-center justify-center gap-2 text-xs font-semibold text-primary">{action} <ChevronRight aria-hidden="true" className="size-3" /></span>
        </Link>
      ))}
    </section>
    {hasCategories && <section aria-labelledby="categorias-populares" className="store-frame mt-8 space-y-3">
      <h2 id="categorias-populares" className="store-section-title scroll-mt-28">Explorá por categorías populares</h2>
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
