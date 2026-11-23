import { authenticatedRequest } from "@/features/auth/session-api";

export type AttributeSchema = Record<string, string[]>;

export type Category = {
  id: string;
  parentId: string | null;
  name: string;
  slug: string;
  commissionBps: number;
  attributeSchema: AttributeSchema;
  version: number;
  archived: boolean;
  activeListings: number;
  hasChildren: boolean;
};

export type CategoryInput = Pick<Category, "parentId" | "name" | "slug" | "commissionBps" | "attributeSchema">;
const path = "/api/v1/admin/categories";

export async function listCategories(signal: AbortSignal): Promise<Category[]> {
  return (await authenticatedRequest(path, { signal })).json();
}

export async function saveCategory(category: Category | null, input: CategoryInput): Promise<Category> {
  return (await authenticatedRequest(category ? `${path}/${category.id}` : path, {
    method: category ? "PATCH" : "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...input, expectedVersion: category?.version }),
  })).json();
}

export async function archiveCategory(category: Category): Promise<Category> {
  return (await authenticatedRequest(`${path}/${category.id}/archive`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ expectedVersion: category.version }),
  })).json();
}
