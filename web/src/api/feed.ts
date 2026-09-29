import { useEffect, useMemo, useSyncExternalStore } from "react";
import { api } from "./client";
import type { Quote, TickFrame } from "./types";

type Want = { quotes: string[]; depth: string[] };

/**
 * One shared WebSocket for the whole app. Components declare what they need with
 * [want]; the feed subscribes to the union and reconnects with backoff.
 */
class MarketFeed {
  private ws?: WebSocket;
  private wants = new Map<number, Want>();
  private nextId = 1;
  private frame?: TickFrame;
  private listeners = new Set<() => void>();
  private retry = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private stopped = true;
  status: "connecting" | "open" | "closed" = "closed";

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    void this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.ws?.close();
    this.ws = undefined;
    this.frame = undefined;
    this.setStatus("closed");
  }

  want(w: Want): () => void {
    const id = this.nextId++;
    this.wants.set(id, w);
    this.sendSubscriptions();
    return () => {
      this.wants.delete(id);
      this.sendSubscriptions();
    };
  }

  latest(): TickFrame | undefined {
    return this.frame;
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private failedHandshake = false;

  private async connect() {
    this.setStatus("connecting");
    try {
      // A handshake refused before opening is usually clock skew or an expired
      // session; a signed REST call re-syncs the clock (or signs us out).
      if (this.failedHandshake) await api.calibrate();
      const ws = new WebSocket(await api.socketUrl());
      this.ws = ws;
      let opened = false;
      ws.onopen = () => {
        opened = true;
        this.failedHandshake = false;
        this.retry = 0;
        this.setStatus("open");
        this.sendSubscriptions();
      };
      ws.onmessage = (e) => {
        const msg = JSON.parse(e.data as string) as { type: string };
        if (msg.type === "tick") {
          this.frame = mergeFrame(this.frame, msg as TickFrame);
          this.listeners.forEach((l) => l());
        }
      };
      ws.onclose = () => {
        if (!opened) this.failedHandshake = true;
        this.scheduleReconnect();
      };
    } catch {
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect() {
    this.ws = undefined;
    this.setStatus("closed");
    if (this.stopped || !api.signedIn) return;
    const delay = Math.min(30_000, 1000 * 2 ** this.retry++) * (0.5 + Math.random() / 2);
    this.timer = setTimeout(() => void this.connect(), delay);
  }

  private setStatus(s: MarketFeed["status"]) {
    this.status = s;
    this.listeners.forEach((l) => l());
  }

  private pending?: ReturnType<typeof setTimeout>;

  private sendSubscriptions() {
    // Debounced so a page mounting many components sends one message.
    clearTimeout(this.pending);
    this.pending = setTimeout(() => {
      if (this.ws?.readyState !== WebSocket.OPEN) return;
      const quotes = new Set<string>();
      const depth = new Set<string>();
      for (const w of this.wants.values()) {
        w.quotes.forEach((t) => quotes.add(t));
        w.depth.forEach((t) => depth.add(t));
      }
      this.ws.send(JSON.stringify({ op: "subscribe", quotes: [...quotes].slice(0, 50), depth: [...depth].slice(0, 3) }));
    }, 30);
  }
}

/** Keeps quotes for tickers that a newer frame omitted (e.g. right after re-subscribing). */
function mergeFrame(prev: TickFrame | undefined, next: TickFrame): TickFrame {
  if (!prev) return next;
  const quotes = new Map<string, Quote>(prev.quotes.map((q) => [q.ticker, q]));
  next.quotes.forEach((q) => quotes.set(q.ticker, q));
  return { ...next, quotes: [...quotes.values()], depth: { ...prev.depth, ...next.depth } };
}

export const feed = new MarketFeed();

/** Latest frame, re-rendering on every update. */
export function useFeedFrame(): TickFrame | undefined {
  return useSyncExternalStore(feed.subscribe, () => feed.latest());
}

export function useFeedStatus(): MarketFeed["status"] {
  return useSyncExternalStore(feed.subscribe, () => feed.status);
}

/** Subscribes to live quotes (and optionally depth) for the given tickers. */
export function useLiveQuotes(tickers: string[], depth: string[] = []): Map<string, Quote> {
  const key = tickers.join(",") + "|" + depth.join(",");
  useEffect(() => feed.want({ quotes: tickers, depth }), [key]); // eslint-disable-line react-hooks/exhaustive-deps
  const frame = useFeedFrame();
  return useMemo(() => new Map((frame?.quotes ?? []).map((q) => [q.ticker, q])), [frame]);
}
