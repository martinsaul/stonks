import { useEffect, useState } from "react";
import { api } from "../api/client";
import { useFeedFrame } from "../api/feed";
import type { Portfolio } from "../api/types";

let cached: Portfolio | undefined;
/** One shared request for every component mounting before the first response. */
let pending: Promise<Portfolio> | undefined;

/**
 * The player's portfolio: fetched once over REST (which also opens the trading
 * account), then kept live by the WebSocket feed.
 */
export function usePortfolio(): Portfolio | undefined {
  const frame = useFeedFrame();
  const [initial, setInitial] = useState<Portfolio | undefined>(cached);
  useEffect(() => {
    if (cached) return;
    pending ??= api.get<Portfolio>("/api/v1/portfolio").finally(() => { pending = undefined; });
    pending.then((p) => {
      cached = p;
      setInitial(p);
    }, () => undefined);
  }, []);
  const live = frame?.account ?? undefined;
  if (live) cached = live;
  return live ?? initial;
}

export const PLAN_LABEL: Record<string, string> = { ROOKIE: "Rookie", TRADER: "Trader", PRO: "Pro", WHALE: "Whale" };

/** Lifetime net realized profit that unlocks each plan (cents). */
export const PLAN_UNLOCK: [string, number][] = [
  ["ROOKIE", 0],
  ["TRADER", 25_000_00],
  ["PRO", 250_000_00],
  ["WHALE", 1_000_000_00],
];

export function nextPlan(plan: string): [string, number] | undefined {
  const i = PLAN_UNLOCK.findIndex(([p]) => p === plan);
  return PLAN_UNLOCK[i + 1];
}

/** Commission estimate mirroring the server's rules (cents). */
export function estimateCommission(plan: string, qty: number, priceCents: number): number {
  const notional = qty * priceCents;
  if (plan === "ROOKIE") return 495;
  const perShare = plan === "TRADER" ? 100 : plan === "PRO" ? 50 : 35; // hundredths of a cent
  const minimum = plan === "WHALE" ? 35 : 100;
  let c = Math.max(Math.ceil((qty * perShare) / 100), minimum);
  c = Math.min(c, Math.max(1, Math.floor(notional / 100)));
  if (plan === "WHALE") c += Math.floor(Math.max(0, notional - 250_000_00) / 1000);
  return c;
}

export type MarginHealth = "none" | "ok" | "warning" | "call";

/** Margin health: warning within 5 points of position value above maintenance. */
export function marginHealth(p: Portfolio): MarginHealth {
  const gross = p.longValue + p.shortValue;
  if (gross === 0) return "none";
  if (p.equity < p.maintenanceRequirement) return "call";
  if (p.equity < p.maintenanceRequirement + 0.05 * gross) return "warning";
  return "ok";
}
