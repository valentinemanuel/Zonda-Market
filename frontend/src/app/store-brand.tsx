import { Link } from "react-router-dom";

const wordmark = {
  light: ["text-brand-ink", "text-primary"],
  dark: ["text-white", "text-brand-gold"],
  orange: ["text-white [text-shadow:0_1px_2px_#791c13]", "text-brand-ink"],
};

export function StoreBrand({ tone = "light", label = "Zonda Market" }: {
  tone?: keyof typeof wordmark; label?: string;
}) {
  const [first, last] = wordmark[tone];
  return <Link to="/catalogo" aria-label={label}
    className="inline-flex min-h-10 w-fit min-w-0 items-center gap-1.5 rounded-lg font-extrabold">
    <img src="/brand/zonda-isotipo.svg#color" alt="" width="32" height="32" className="size-7 shrink-0 rounded-lg bg-brand-ink p-0.5 sm:size-8" />
    <span className="whitespace-nowrap font-wordmark text-base leading-none tracking-[-0.035em] sm:text-xl">
      <span className={first}>ZONDA</span><span className={last}>–MARKET</span>
    </span>
  </Link>;
}
