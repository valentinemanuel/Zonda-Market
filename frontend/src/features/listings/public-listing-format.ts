export function listingPublicPath(slug: string) {
  return "/publicaciones/" + encodeURIComponent(slug);
}

export function formattedPrice(price: string) {
  const [whole, cents = "00"] = price.split(".");
  return "$ " + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ".") + "," + cents;
}
