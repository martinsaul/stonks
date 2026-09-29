import type { Candle } from "../api/types";

/** Merges consecutive candles into buckets of [seconds] (e.g. 1m → 5m). */
export function aggregate(candles: Candle[], seconds: number): Candle[] {
  const out: Candle[] = [];
  let bucket = -1;
  for (const c of candles) {
    const t = Math.floor(Date.parse(c.time) / 1000 / seconds) * seconds;
    const last = out[out.length - 1];
    if (t !== bucket || !last) {
      bucket = t;
      out.push({ ...c, time: new Date(t * 1000).toISOString() });
    } else {
      last.high = Math.max(last.high, c.high);
      last.low = Math.min(last.low, c.low);
      last.close = c.close;
      last.volume += c.volume;
    }
  }
  return out;
}

/** Appends or replaces [live] as the last candle. */
export function withLive(candles: Candle[], live: Candle | null | undefined): Candle[] {
  if (!live) return candles;
  const last = candles[candles.length - 1];
  if (last && last.time === live.time) return [...candles.slice(0, -1), live];
  return [...candles, live];
}
