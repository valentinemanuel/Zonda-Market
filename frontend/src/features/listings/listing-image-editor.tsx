import { useRef, type FormEvent } from "react";
import { ProfileInput, profileControl } from "@/features/auth/profile-fields";
import { ApiError } from "@/shared/api/http-client";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { deleteImage, editImage, uploadImage, type ListingImage } from "./listing-api";
import { useSave } from "./listing-forms";

const maxBytes = 5 * 1024 * 1024;

export function ListingImageEditor({ listingId, images, changed }: {
  listingId: string;
  images: ListingImage[];
  changed: (images: ListingImage[]) => void;
}) {
  const state = useSave();
  const fileInput = useRef<HTMLInputElement>(null);
  const fileError = ["IMAGE_INVALID", "IMAGE_DIMENSIONS_INVALID", "IMAGE_TOO_LARGE"].includes(state.error?.code ?? "");

  function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    void state.submit(async () => {
      const file = fields.get("file");
      if (file instanceof File && file.size > maxBytes) {
        throw new ApiError(413, null, "IMAGE_TOO_LARGE");
      }
      const image = await uploadImage(listingId, fields);
      changed([...images, image].sort((left, right) => left.position - right.position));
      form.reset();
    }, "Imagen agregada.");
  }

  function editAlt(event: FormEvent<HTMLFormElement>, image: ListingImage) {
    event.preventDefault();
    const alt = String(new FormData(event.currentTarget).get("alt"));
    void state.submit(async () => {
      changed(await editImage(listingId, image.id, { alt }));
    }, "Texto alternativo guardado.");
  }

  function move(image: ListingImage, offset: number) {
    void state.submit(async () => {
      changed(await editImage(listingId, image.id, { position: image.position + offset }));
      requestAnimationFrame(() => document.getElementById("image-title-" + image.id)?.focus());
    }, "Orden actualizado.");
  }

  function remove(image: ListingImage) {
    void state.submit(async () => {
      await deleteImage(listingId, image.id);
      changed(images.filter(({ id }) => id !== image.id).map((item, index) => ({ ...item, position: index })));
      requestAnimationFrame(() => fileInput.current?.focus());
    }, "Imagen retirada.");
  }

  return (
    <section aria-labelledby="image-editor" className="space-y-4">
      <Card className="rounded-lg">
        <CardContent className="space-y-3 px-4 sm:px-6">
          <h2 className="text-lg font-semibold" id="image-editor">Imágenes de la publicación</h2>
          <p id="upload-help" className="text-sm text-muted-foreground">Hasta seis imágenes JPEG, PNG o WebP de 5 MiB cada una.</p>
          {images.length === 0 && <p>Todavía no agregaste imágenes.</p>}
          {images.length < 6 && (
            <form onSubmit={upload}>
              <fieldset disabled={state.busy} className="min-w-0 space-y-3">
                <div>
                  <label className="mb-1.5 block text-sm font-medium" htmlFor="image-file">Archivo de imagen</label>
                  <input ref={fileInput} type="file" id="image-file" name="file" required aria-invalid={fileError || undefined}
                    aria-describedby={fileError ? "upload-help image-editor-error" : "upload-help"}
                    accept="image/jpeg,image/png,image/webp" className={profileControl + " text-sm file:mr-2 file:font-medium"} />
                </div>
                <ProfileInput id="new-image-alt" name="alt" label="Texto alternativo para la imagen nueva" maxLength={160} />
                <Button type="submit" className="min-h-11">Subir imagen</Button>
              </fieldset>
            </form>
          )}
          <p role="status" className="text-sm">{state.busy ? "Guardando imágenes…" : state.success}</p>
          {state.error && <p id="image-editor-error" role="alert" className="text-sm text-destructive">{state.error.message}</p>}
        </CardContent>
      </Card>
      <ol className="space-y-3">
        {images.map((image, index) => (
          <li key={image.id}>
            <Card className="rounded-lg">
              <CardContent className="grid gap-4 px-4 sm:grid-cols-[7rem_minmax(0,1fr)] sm:px-6">
                <img className="aspect-square w-24 rounded-md border bg-muted object-cover sm:w-28"
                  src={image.thumbnailUrl} alt={image.alt || "Imagen " + (index + 1)} loading="lazy" decoding="async" />
                <div className="space-y-3">
                  <h3 tabIndex={-1} id={"image-title-" + image.id} className="text-sm font-semibold">Imagen {index + 1}</h3>
                  <form onSubmit={(event) => editAlt(event, image)}>
                    <fieldset disabled={state.busy} className="space-y-2">
                      <ProfileInput id={"image-alt-" + image.id} name="alt" label="Texto alternativo"
                        maxLength={160} defaultValue={image.alt} />
                      <Button type="submit" variant="outline" className="min-h-11">Guardar texto</Button>
                    </fieldset>
                  </form>
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" variant="outline" className="min-h-11"
                      disabled={state.busy || index === 0} aria-label={"Subir posición de imagen " + (index + 1)}
                      onClick={() => move(image, -1)}>Subir posición</Button>
                    <Button type="button" variant="outline" className="min-h-11"
                      disabled={state.busy || index === images.length - 1} aria-label={"Bajar posición de imagen " + (index + 1)}
                      onClick={() => move(image, 1)}>Bajar posición</Button>
                    <Button type="button" variant="outline" className="min-h-11 text-destructive"
                      disabled={state.busy} aria-label={"Retirar imagen " + (index + 1)}
                      onClick={() => remove(image)}>Retirar</Button>
                  </div>
                </div>
              </CardContent>
            </Card>
          </li>
        ))}
      </ol>
    </section>
  );
}
