import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api } from "../api/client";
import { feed, useFeedFrame } from "../api/feed";
import type { IndexQuote, MarketResponse, Quote, SessionInfo } from "../api/types";

export interface MarketData {
  time: string;
  session: SessionInfo;
  regime: string;
  index: IndexQuote;
  quotes: Quote[];
  byTicker: Map<string, Quote>;
  /** Central bank rate, percent. */
  benchmarkRate: number;
  latestNewsId: number;
}

const Ctx = createContext<{ market?: MarketData; error?: string }>({});

/**
 * Loads the market once over REST, then keeps it live from the WebSocket feed, which
 * is subscribed to every ticker for the whole session.
 */
export function MarketProvider({ children }: { children: ReactNode }) {
  const [base, setBase] = useState<MarketResponse>();
  const [error, setError] = useState<string>();
  const frame = useFeedFrame();
  const [listings, setListings] = useState<number>();

  // Companies listed or delisted: reload the list (and resubscribe).
  useEffect(() => {
    if (frame?.listings === undefined) return;
    if (listings !== undefined && frame.listings !== listings) {
      api.get<MarketResponse>("/api/v1/market").then((m) => {
        setBase(m);
        feed.want({ quotes: m.quotes.map((q) => q.ticker), depth: [] });
      }, () => {});
    }
    setListings(frame.listings);
  }, [frame?.listings]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let cancelled = false;
    api.get<MarketResponse>("/api/v1/market").then(
      (m) => {
        if (cancelled) return;
        setBase(m);
        feed.want({ quotes: m.quotes.map((q) => q.ticker), depth: [] });
        // Start streaming only after a signed call succeeded (clock calibrated).
        feed.start();
      },
      (e: Error) => !cancelled && setError(e.message),
    );
    return () => {
      cancelled = true;
      feed.stop();
    };
  }, []);

  const market = useMemo<MarketData | undefined>(() => {
    if (!base) return undefined;
    const byTicker = new Map(base.quotes.map((q) => [q.ticker, q]));
    const useFrame = frame && frame.time >= base.time;
    if (useFrame) frame.quotes.forEach((q) => byTicker.set(q.ticker, q));
    return {
      time: useFrame ? frame.time : base.time,
      session: useFrame ? frame.session : base.session,
      regime: useFrame ? frame.regime : base.regime,
      index: useFrame ? frame.index : base.index,
      quotes: base.quotes.map((q) => byTicker.get(q.ticker) ?? q),
      byTicker,
      benchmarkRate: useFrame ? frame.benchmarkRate : base.benchmarkRate,
      latestNewsId: Math.max(base.latestNewsId, frame?.latestNewsId ?? 0),
    };
  }, [base, frame]);

  return <Ctx.Provider value={{ market, error }}>{children}</Ctx.Provider>;
}

export function useMarket() {
  return useContext(Ctx);
}

/** Re-renders every second (for countdowns). */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(api.now());
  useEffect(() => {
    const t = setInterval(() => setNow(api.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}
