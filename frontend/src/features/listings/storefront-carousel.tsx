import { ArrowRight, ChevronLeft, ChevronRight, Pause, Play } from "lucide-react";
import { useEffect, useState, useSyncExternalStore } from "react";
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

const motionQuery = "(prefers-reduced-motion: no-preference)";
const slideDelay = 6000;
const slideControl = "grid size-11 place-items-center rounded-full bg-brand-ink/85 hover:bg-brand-ink focus-visible:outline-brand-gold";

function subscribeToMotion(listener: () => void) {
  const media = window.matchMedia(motionQuery);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}

function motionAllowed() {
  return window.matchMedia(motionQuery).matches;
}

type View = { index: number; previous?: number; direction: 1 | -1 };

function move(view: View, direction: View["direction"]): View {
  return { index: (view.index + direction + slides.length) % slides.length, previous: view.index, direction };
}

export function StorefrontCarousel({ categories }: { categories: CatalogCategoryNode[] }) {
  const [view, setView] = useState<View>({ index: 0, direction: 1 });
  const [playing, setPlaying] = useState(true);
  const [hovering, setHovering] = useState(false);
  const canAnimate = useSyncExternalStore(subscribeToMotion, motionAllowed);
  const rotating = playing && canAnimate && !hovering;

  useEffect(() => {
    if (!rotating) return;
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible") setView((current) => move(current, 1));
    }, slideDelay);
    return () => window.clearInterval(interval);
  }, [rotating]);

  function step(direction: View["direction"]) {
    setPlaying(false);
    setView((current) => move(current, direction));
  }

  return <section aria-roledescription="carrusel" aria-label="Explorá Zonda Market"
    className="store-hero relative flex flex-col text-white"
    onPointerEnter={(event) => { if (event.pointerType === "mouse") setHovering(true); }}
    onPointerLeave={() => setHovering(false)}>
    <div className="order-last mt-2 flex flex-wrap items-center justify-center gap-1.5">
      {canAnimate && <button type="button" className={slideControl}
        aria-label={playing ? "Pausar destacados" : "Reproducir destacados"} onClick={() => setPlaying(!playing)}>
        {playing ? <Pause aria-hidden="true" className="size-4" /> : <Play aria-hidden="true" className="size-4" />}
      </button>}
      <button type="button" className={slideControl} aria-label="Destacado anterior" onFocus={() => setPlaying(false)} onClick={() => step(-1)}>
        <ChevronLeft aria-hidden="true" className="size-4" />
      </button>
      {slides.map(({ name }, position) => <button type="button" key={name} aria-label={name}
        aria-pressed={position === view.index} onFocus={() => setPlaying(false)}
        onClick={() => {
          setPlaying(false);
          setView((current) => position === current.index ? current : {
            index: position, previous: current.index, direction: position > current.index ? 1 : -1,
          });
        }} className="grid size-8 place-items-center rounded-full focus-visible:outline-brand-gold">
        <span aria-hidden="true" className={`h-1.5 rounded-full ${position === view.index ? "w-5 bg-brand" : "w-1.5 bg-white/55"}`} />
      </button>)}
      <button type="button" className={slideControl} aria-label="Siguiente destacado" onFocus={() => setPlaying(false)} onClick={() => step(1)}>
        <ChevronRight aria-hidden="true" className="size-4" />
      </button>
    </div>
    <p className="sr-only" aria-live={rotating ? "off" : "polite"}>
      {slides[view.index].name} · {view.index + 1} de {slides.length}
    </p>
    <div className="grid overflow-x-clip" data-direction={view.direction} onFocus={() => setPlaying(false)}>
      {slides.map((slide, position) => {
        const active = position === view.index;
        const featured = categories.find(({ category }) => category.name === slide.name);
        const path = featured ? categoryCatalogPath(featured.category.id) : "/catalogo?q=" + encodeURIComponent(slide.name);
        const state = active ? (view.previous === undefined ? "active" : "entering") : position === view.previous ? "leaving" : "inactive";
        return <div key={slide.name} inert={!active} data-state={state}
          className="store-hero-slide store-frame col-start-1 row-start-1 grid items-center gap-5 py-7 sm:gap-7 md:min-h-[21rem] md:grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] md:py-8">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/10 px-3 py-1 text-[0.68rem] font-bold text-brand-gold">
              <span aria-hidden="true" className="size-1.5 rounded-full bg-brand" /> {slide.label}
            </p>
            <p className="mt-3 max-w-xl font-wordmark text-[clamp(2rem,4.25vw,4.5rem)] leading-[0.98] font-extrabold tracking-[-0.045em]">
              {slide.title} <span className="block bg-linear-to-r from-orange-400 to-brand-gold bg-clip-text text-transparent">{slide.accent}</span>
            </p>
            <p className="mt-3 max-w-md text-sm leading-relaxed text-slate-200 sm:text-base">{slide.copy}</p>
            <div className="mt-5 flex flex-wrap gap-2 sm:gap-3">
              <Button asChild className="min-h-11 px-4 shadow-float focus-visible:outline-brand-gold">
                <a href="#productos">Explorar catálogo <ArrowRight aria-hidden="true" /></a>
              </Button>
              <Button asChild variant="outline" className="min-h-11 border-white/15 bg-white/10 text-white hover:bg-hero-panel hover:text-white focus-visible:outline-brand-gold">
                <Link to={path}>Ver categoría</Link>
              </Button>
            </div>
          </div>
          <Link to={path} aria-label={slide.feature} className="relative isolate rounded-[1.3125rem] border border-white/15 bg-white/10 p-2 focus-visible:outline-brand-gold">
            <div className="relative isolate aspect-[4/3] overflow-hidden rounded-xl bg-hero">
              <img alt="" fetchPriority={position === 0 ? "high" : "low"} decoding="async"
                src={`https://images.pexels.com/photos/${slide.image}/pexels-photo-${slide.image}.jpeg?auto=compress&cs=tinysrgb&w=1000`}
                className="store-hero-image absolute inset-0 size-full rounded-xl object-cover brightness-75" />
              <div className="absolute inset-0 bg-linear-to-t from-brand-ink via-brand-ink/15 to-brand-ink/20" />
              <span className="absolute inset-x-4 bottom-4 flex items-end justify-between gap-3">
                <span className="text-lg leading-tight font-bold md:text-xl">{slide.feature}</span>
                <span className="grid size-8 shrink-0 place-items-center rounded-full bg-white text-brand-ink"><ArrowRight aria-hidden="true" className="size-4" /></span>
              </span>
            </div>
          </Link>
        </div>;
      })}
    </div>
  </section>;
}
