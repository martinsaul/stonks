// Display formatting. Prices arrive as integer cents.

const ET = "America/New_York";

const usd = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2 });
const num = new Intl.NumberFormat("en-US");
const compactFmt = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 2 });

export function money(cents: number | null | undefined): string {
  return cents == null ? "—" : usd.format(cents / 100);
}

/** A price without the currency symbol, as quote tables show it. */
export function price(cents: number | null | undefined): string {
  return cents == null ? "—" : (cents / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function integer(n: number | null | undefined): string {
  return n == null ? "—" : num.format(n);
}

export function compact(n: number | null | undefined): string {
  return n == null ? "—" : compactFmt.format(n);
}

export type Direction = "up" | "down" | "flat";

export function direction(n: number | null | undefined): Direction {
  if (n == null || n === 0) return "flat";
  return n > 0 ? "up" : "down";
}

/** Signed change in dollars from cents, e.g. "+1.23". */
export function signedPrice(cents: number | null | undefined): string {
  if (cents == null) return "—";
  const s = (Math.abs(cents) / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (cents > 0 ? "+" : cents < 0 ? "−" : "") + s;
}

export function signedNumber(n: number | null | undefined, digits = 2): string {
  if (n == null) return "—";
  const s = Math.abs(n).toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  return (n > 0 ? "+" : n < 0 ? "−" : "") + s;
}

export function signedPct(p: number | null | undefined): string {
  return p == null ? "—" : `${signedNumber(p)}%`;
}

export function timeET(iso: string | number | Date, withDate = false): string {
  return new Date(iso).toLocaleString("en-US", {
    timeZone: ET,
    hour: "numeric",
    minute: "2-digit",
    ...(withDate ? { month: "short", day: "numeric" } : {}),
  });
}

export function dateET(iso: string | number | Date): string {
  return new Date(iso).toLocaleDateString("en-US", { timeZone: ET, month: "short", day: "numeric", year: "numeric" });
}

/** "2h 05m" / "4m 12s" */
export function duration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return `${h}h ${String(m).padStart(2, "0")}m`;
  return `${m}m ${String(s % 60).padStart(2, "0")}s`;
}

export const SECTORS: Record<string, string> = {
  TECH: "Technology",
  CONSUMER: "Consumer",
  ENERGY: "Energy",
  PHARMA: "Pharma",
  FINANCE: "Financials",
  INDUSTRIAL: "Industrials",
};

export const REGIMES: Record<string, string> = {
  NEUTRAL: "Neutral",
  BULL: "Bull market",
  BEAR: "Bear market",
  CRASH: "Crash",
  BUBBLE: "Bubble",
};
