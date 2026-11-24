const percent = new Intl.NumberFormat("es-AR", { style: "percent", maximumFractionDigits: 2 });

export function formatCommission(basisPoints: number) {
  return percent.format(basisPoints / 10_000);
}
