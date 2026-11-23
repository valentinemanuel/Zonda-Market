import { ChevronLeft, ChevronRight, ImageOff, X, ZoomIn } from "lucide-react";
import { useRef, useState } from "react";
import { Button } from "@/shared/ui/button";
import type { ListingImage } from "./listing-api";

function ProductImage({ image, title, className }: { image: ListingImage; title: string; className: string }) {
  const [failed, setFailed] = useState(false);
  return failed
    ? <span className="flex min-h-24 items-center justify-center gap-2 px-4 text-sm">
        <ImageOff aria-hidden="true" className="size-5 shrink-0" />No pudimos cargar esta imagen.
      </span>
    : <img className={className} src={image.url} alt={image.alt || title} decoding="async" onError={() => setFailed(true)} />;
}

export function ProductGallery({ images, title }: { images: ListingImage[]; title: string }) {
  const [selectedId, select] = useState<string>();
  const dialog = useRef<HTMLDialogElement>(null);
  const currentIndex = Math.max(0, images.findIndex(({ id }) => id === selectedId));
  const current = images[currentIndex];
  const multiple = images.length > 1;

  function move(direction: number) {
    select(images[(currentIndex + direction + images.length) % images.length].id);
  }

  return <section aria-label="Galería de imágenes" className="product-gallery min-w-0">
    <div className={`grid min-w-0 items-start gap-3 ${multiple ? "sm:grid-cols-[3.5rem_minmax(0,1fr)]" : ""}`}>
      {current ? <button type="button" aria-label="Ampliar imagen del producto" aria-haspopup="dialog"
        onClick={() => dialog.current?.showModal()}
        className="relative grid aspect-square max-h-[460px] w-full min-w-0 cursor-zoom-in place-items-center overflow-hidden rounded-[12px] border bg-white text-muted-foreground">
        <ProductImage key={current.id} image={current} title={title} className="absolute inset-0 size-full object-contain" />
        <span className="absolute right-2.5 bottom-2.5 inline-flex items-center gap-1.5 rounded-full border bg-white/95 px-2.5 py-1.5 text-xs font-semibold text-foreground shadow-sm">
          <ZoomIn aria-hidden="true" className="size-4" />Click para ampliar
        </span>
      </button> : <div className="grid aspect-square max-h-[460px] place-items-center rounded-panel border bg-white p-4 text-sm text-muted-foreground">
        Todavía no hay imágenes.
      </div>}
      {multiple && <ul role="list" aria-label="Miniaturas" className="flex min-w-0 gap-2 overflow-x-auto p-1 sm:col-start-1 sm:row-start-1 sm:max-h-[460px] sm:flex-col sm:overflow-y-auto">
        {images.map((image, index) => <li key={image.id} className="shrink-0">
          <button type="button" aria-label={"Ver imagen " + (index + 1) + (image.alt ? " · " + image.alt : "")}
            aria-pressed={current.id === image.id} onClick={() => select(image.id)}
            className="block size-12 cursor-pointer overflow-hidden rounded-lg border-2 bg-white hover:border-primary/50 aria-pressed:border-primary">
            <img src={image.thumbnailUrl} alt="" loading="lazy" decoding="async" className="size-full object-contain" />
          </button>
        </li>)}
      </ul>}
    </div>
    {current && <dialog ref={dialog} {...{ closedby: "any" }} aria-label="Imágenes ampliadas del producto"
      className="listing-lightbox fixed inset-0 m-auto h-fit max-h-none w-fit max-w-none overflow-visible bg-transparent p-0 text-white"
      onClick={(event) => {
        const element = event.currentTarget;
        if ("closedBy" in HTMLDialogElement.prototype || event.target !== element) return;
        const { top, bottom, left, right } = element.getBoundingClientRect();
        if (event.clientX < left || event.clientX > right || event.clientY < top || event.clientY > bottom) {
          element.close();
        }
      }}
      onKeyDown={(event) => {
        if (!multiple || (event.key !== "ArrowLeft" && event.key !== "ArrowRight")) return;
        event.preventDefault();
        move(event.key === "ArrowLeft" ? -1 : 1);
      }}>
      <Button type="button" variant="ghost" size="icon-lg" ref={(button) => { button?.setAttribute("autofocus", ""); }}
        className="fixed top-4 right-4 size-11 rounded-full border-white/25 bg-black/65 text-white hover:bg-white hover:text-black focus-visible:outline-white"
        aria-label="Cerrar imagen ampliada" onClick={() => dialog.current?.close()}>
        <X aria-hidden="true" />
      </Button>
      <ProductImage key={current.id} image={current} title={title}
        className="block h-auto max-h-[calc(100dvh-7rem)] w-auto max-w-[calc(100vw-2rem)] rounded-lg object-contain sm:max-w-[calc(100vw-8rem)]" />
      {multiple && <>
        <Button type="button" variant="ghost" size="icon-lg" aria-label="Imagen anterior" onClick={() => move(-1)}
          className="fixed top-1/2 left-2 size-11 -translate-y-1/2 rounded-full border-white/25 bg-black/65 text-white hover:bg-white hover:text-black focus-visible:outline-white sm:left-5">
          <ChevronLeft aria-hidden="true" />
        </Button>
        <Button type="button" variant="ghost" size="icon-lg" aria-label="Imagen siguiente" onClick={() => move(1)}
          className="fixed top-1/2 right-2 size-11 -translate-y-1/2 rounded-full border-white/25 bg-black/65 text-white hover:bg-white hover:text-black focus-visible:outline-white sm:right-5">
          <ChevronRight aria-hidden="true" />
        </Button>
        <p role="status" className="fixed bottom-4 left-1/2 -translate-x-1/2 rounded-full bg-black/65 px-3 py-1.5 text-sm tabular-nums">
          {currentIndex + 1} de {images.length}
        </p>
      </>}
    </dialog>}
  </section>;
}
