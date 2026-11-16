import { getJson } from "@/shared/api/http-client";

export type Category = {
  id: string;
  name: string;
  slug: string;
  parentId: string | null;
};

export async function getCategory(id: string, signal: AbortSignal): Promise<Category> {
  const category = await getJson(`/api/v1/categories/${encodeURIComponent(id)}`, signal);
  if (
    !category || typeof category !== "object" ||
    !("id" in category) || typeof category.id !== "string" ||
    !("name" in category) || typeof category.name !== "string" ||
    !("slug" in category) || typeof category.slug !== "string" ||
    !("parentId" in category) || (category.parentId !== null && typeof category.parentId !== "string")
  ) {
    throw new Error("La respuesta de categoría no cumple el contrato");
  }

  return { id: category.id, name: category.name, slug: category.slug, parentId: category.parentId };
}
