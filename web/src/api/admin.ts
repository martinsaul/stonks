import type { BondOffering, Portfolio } from "./types";

// Admin console wire types (not part of the public API; see docs/API.md, "Admin").

export interface TickerAdmin {
  ticker: string; name: string; sector: string; last: number; strategy: string; status: string;
  halted: boolean; volatility: number; depth: number; borrowPool: number | null; pendingSplit: string | null; deal: number | null;
}
export interface Scheduled { date: string | null; day: number; tick: number; kind: string; target: string | null; detail: string }
export interface AdminOverview {
  day: number; tick: number | null; open: boolean; regime: string; benchmarkRate: number; nextRateDecision: string | null;
  tickers: TickerAdmin[]; scheduled: Scheduled[]; bonds: BondOffering[]; players: number;
  strategies: string[]; events: string[]; regimes: string[]; sectors: string[];
}
export interface PlayerRow { id: number; name: string; email: string; createdAt: string; bannedAt: string | null }
export interface SessionRow { ip: string | null; userAgent: string | null; createdAt: string; lastSeenAt: string; revoked: boolean }
export interface FillRow {
  id: string; ticker: string; side: string; quantity: number; price: number; commission: number; realized: number;
  counterparty: number | null; at: string; voided: boolean; liquidation: boolean;
}
export interface EconomyRow { kind: string; amount: number; detail: string | null; at: string }
export interface Flag { kind: string; detail: string; accountId?: number | null }
export interface PlayerDetail {
  player: PlayerRow; portfolio: Portfolio | null; sessions: SessionRow[]; fills: FillRow[]; economy: EconomyRow[]; flags: Flag[]; review: string | null;
}
export interface ReviewRow { accountId: number; name: string; status: string; firstQualifiedAt: string; reviewer: string | null; note: string | null }
export interface StatRow {
  day: number; at: string; players: number; totalWorth: number; totalCash: number; totalDebt: number; inDebt: number; millionaires: number; medianWorth: number;
}
export interface EconomyDashboard { stats: StatRow[]; pendingReviews: number }
export interface AuditEntry { id: number; at: string; admin: string; action: string; target: string | null; payload: string; result: string | null }
export interface SnapshotRow { id: number; takenAt: string; sessionsCompleted: number; lastClose: string | null }

/** A game-master action (see AdminPayload on the server). */
export type AdminAction = { type: string } & Record<string, unknown>;
