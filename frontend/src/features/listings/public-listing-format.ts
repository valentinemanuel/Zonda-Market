export function listingPublicPath(slug: string) {
  return "/publicaciones/" + encodeURIComponent(slug);
}

const conditionSuffix = /\s+·\s+(nuevo|como nuevo|buen estado|segunda mano|usado)\s*$/iu;

export function publicListingTitle(title: string) {
  return title.replace(conditionSuffix, "");
}

export function listingCondition(title: string, specs: Record<string, unknown>) {
  const value = [specs["Condición"], specs["condición"], title.match(conditionSuffix)?.[1]]
    .find((entry): entry is string => typeof entry === "string" && entry.trim() !== "")?.trim().toLowerCase();
  switch (value) {
    case "nuevo": return "Nuevo";
    case "como nuevo": return "Como nuevo";
    case "buen estado":
    case "segunda mano":
    case "usado": return "Usado";
  }
}

export function formattedPrice(price: string) {
  const [whole, cents = "00"] = price.split(".");
  return "$ " + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ".") + "," + cents;
}
