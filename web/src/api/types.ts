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

/** A WebSocket `tick` frame. */
export interface TickFrame {
  type: "tick";
  time: string;
  session: SessionInfo;
  regime: string;
  index: IndexQuote;
  quotes: Quote[];
  depth: Record<string, Depth>;
}
