import { direction, signedPct, signedPrice } from "../lib/format";

/** A price change: arrow + sign + color, so direction never relies on color alone. */
export function Change({ cents, pct, className = "" }: { cents?: number | null; pct?: number | null; className?: string }) {
  const dir = direction(cents ?? pct);
  const arrow = dir === "up" ? "▲" : dir === "down" ? "▼" : "";
  return (
    <span className={`${dir} num ${className}`}>
      {arrow && <span aria-hidden="true">{arrow} </span>}
      {cents !== undefined && signedPrice(cents)}
      {cents !== undefined && pct !== undefined && " "}
      {pct !== undefined && (cents !== undefined ? `(${signedPct(pct)})` : signedPct(pct))}
    </span>
  );
}

export function Pct({ pct }: { pct: number | null | undefined }) {
  return <Change pct={pct} />;
}
