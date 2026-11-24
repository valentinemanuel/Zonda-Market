import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ownSeller, ownSellerKey } from "@/features/auth/profile-api";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { changePublicationState, type Listing, type ListingCategory } from "./listing-api";
import { ListingDetailView } from "./listing-detail";
import { listingPublicPath } from "./public-listing-format";
import { useSave } from "./listing-forms";

export function ListingPreview({ listing, category, sessionRevision, updated, editImages }: {
  listing: Listing;
  category?: ListingCategory;
  sessionRevision: number;
  updated: (listing: Listing) => void;
  editImages: () => void;
}) {
  const state = useSave();
  const seller = useQuery({
    queryKey: ownSellerKey(sessionRevision),
    queryFn: ({ signal }) => ownSeller(signal),
    retry: false,
  });
  const published = listing.status === "ACTIVA";
  const canShare = published && listing.slug;

  function change() {
    void state.submit(async () => {
      updated(await changePublicationState(listing.id, published ? "archive" : "publish"));
    }, published ? "Publicación archivada." : "Publicación visible.");
  }

  return (
    <div className="space-y-5">
      <Card className="rounded-lg">
        <CardContent className="space-y-3 px-4 sm:px-6">
          <h2 className="text-lg font-semibold">Vista previa</h2>
          <p className="text-sm text-muted-foreground">La vista previa usa los cambios guardados.</p>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" className="min-h-11" variant={published ? "outline" : "default"}
              disabled={state.busy} onClick={change}>
              {published ? "Archivar publicación" : listing.status === "ARCHIVADA" ? "Volver a publicar" : "Publicar ficha"}
            </Button>
            {canShare &&
              <Button variant="outline" className="min-h-11" asChild>
                <Link to={listingPublicPath(canShare)}>Ver ficha pública</Link>
              </Button>}
            {!listing.images.length &&
              <Button type="button" variant="outline" className="min-h-11" onClick={editImages}>Agregar imágenes</Button>}
          </div>
          <p role="status" className="text-sm">{state.busy ? "Actualizando publicación…" : state.success}</p>
          {state.error && <p role="alert" className="text-sm text-destructive">{state.error.message}</p>}
        </CardContent>
      </Card>
      <ListingDetailView preview detail={{
        ...listing,
        category,
        seller: seller.data,
        skus: listing.skus.map((sku) => ({ ...sku, available: sku.availableStock > 0 })),
      }} />
    </div>
  );
}
