import type { components } from "./schema";

type S = components["schemas"];
export type Quote = S["Quote"];
export type SessionInfo = S["SessionInfo"];
export type IndexQuote = S["IndexQuote"];
export type Depth = S["Depth"];
export type DepthLevel = S["DepthLevel"];
export type Candle = S["Candle"];
export type MarketResponse = S["MarketResponse"];
export type QuoteResponse = S["QuoteResponse"];
export type CandlesResponse = S["CandlesResponse"];
export type MeResponse = S["MeResponse"];
export type LoginResponse = S["LoginResponse"];
export type OtpRequested = S["OtpRequested"];
export type Portfolio = S["Portfolio"];
export type Position = S["Position"];
export type Order = S["Order"];
export type Fill = S["Fill"];
export type Notice = S["Notice"];
export type LegRequest = S["LegRequest"];
export type PlaceOrderRequest = S["PlaceOrderRequest"];
export type PlaceOrderResponse = S["PlaceOrderResponse"];
export type NewsItem = S["NewsItem"];
export type NewsResponse = S["NewsResponse"];
export type CalendarEvent = S["CalendarEvent"];
export type CalendarResponse = S["CalendarResponse"];
export type Delisting = S["Delisting"];
export type Fundamentals = S["Fundamentals"];

/** A WebSocket `tick` frame (every 5 s). Between ticks, `{type: "account", account}` frames update only the player's own account. */
export interface TickFrame {
  type: "tick";
  time: string;
  session: SessionInfo;
  regime: string;
  index: IndexQuote;
  quotes: Quote[];
  depth: Record<string, Depth>;
  /** The signed-in player's portfolio (absent until the account exists). */
  account?: Portfolio | null;
  /** Central bank rate, percent. */
  benchmarkRate: number;
  /** Newest news id: fetch `/news?after=` when it grows. */
  latestNewsId: number;
  /** Changes when companies list or delist: refetch `/market`. */
  listings: number;
}
