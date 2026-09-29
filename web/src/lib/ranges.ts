import { api } from "../api/client";
import type { Candle, CandlesResponse } from "../api/types";
import { aggregate, withLive } from "./candles";

export const RANGES = ["1D", "5D", "1M", "6M", "YTD", "1Y", "Max"] as const;
export type Range = (typeof RANGES)[number];

export interface Series {
  candles: Candle[];
  /** Bucket size in seconds for live updates; 0 = one bar per session (daily). */
  bucket: number;
}

// Game days per range (a game day = one session; 12 per real week).
const DAILY: Partial<Record<Range, number>> = { "1M": 21, "6M": 126, "1Y": 252, Max: 2000 };

async function candles(ticker: string, q: string): Promise<CandlesResponse> {
  return api.get<CandlesResponse>(`/api/v1/quotes/${ticker}/candles?${q}`);
}

/** Loads the candles a chart range needs. */
export async function loadRange(ticker: string, range: Range): Promise<Series> {
  if (range === "1D" || range === "5D") {
    // Session opens come from the daily candles (the live one if a session is open).
    const days = await candles(ticker, "res=1d&limit=5");
    const opens = withLive(days.candles, days.live).map((c) => c.time);
    const from = range === "1D" ? opens[opens.length - 1] : opens[0];
    if (!from) return { candles: [], bucket: 60 };

    // Page backwards until the range start is covered (limit is 2000 per call).
    let rows: Candle[] = [];
    let to: string | undefined;
    let live: Candle | null | undefined;
    for (let page = 0; page < 3; page++) {
      const r = await candles(ticker, `res=1m&limit=2000&from=${encodeURIComponent(from)}${to ? `&to=${encodeURIComponent(to)}` : ""}`);
      if (page === 0) live = r.live;
      rows = [...r.candles, ...rows];
      const first = r.candles[0];
      if (r.candles.length < 2000 || !first) break;
      to = new Date(Date.parse(first.time) - 1).toISOString();
    }
    rows = withLive(rows, live);
    return range === "1D" ? { candles: rows, bucket: 60 } : { candles: aggregate(rows, 300), bucket: 300 };
  }

  const query = range === "YTD"
    ? `res=1d&limit=2000&from=${new Date(Date.UTC(new Date().getUTCFullYear(), 0, 1, 5)).toISOString()}`
    : `res=1d&limit=${DAILY[range]}`;
  const r = await candles(ticker, query);
  return { candles: withLive(r.candles, r.live), bucket: 0 };
}
