import { useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import type { LegRequest, Order, PlaceOrderRequest, PlaceOrderResponse, Portfolio, Quote } from "../api/types";
import { money, price, timeET } from "../lib/format";
import { estimateCommission, PLAN_LABEL } from "../lib/portfolio";

type Side = "BUY" | "SELL";
type Type = LegRequest["type"];
type Mode = "simple" | "bracket" | "oco";

const TYPES: { value: Type; label: string }[] = [
  { value: "MARKET", label: "Market" },
  { value: "LIMIT", label: "Limit" },
  { value: "STOP", label: "Stop" },
  { value: "STOP_LIMIT", label: "Stop limit" },
  { value: "TRAILING_STOP", label: "Trailing stop" },
  { value: "TRAILING_STOP_LIMIT", label: "Trailing stop limit" },
  { value: "TWAP", label: "TWAP (time-sliced)" },
  { value: "VWAP", label: "VWAP (volume-weighted)" },
];

const toCents = (s: string): number | undefined => {
  const n = Number(s);
  return s.trim() !== "" && Number.isFinite(n) && n > 0 ? Math.round(n * 100) : undefined;
};

/** Order entry for one ticker, with a live cost and margin estimate. */
export function TradeTicket({ ticker, quote, portfolio }: { ticker: string; quote: Quote; portfolio?: Portfolio }) {
  const [side, setSide] = useState<Side>("BUY");
  const [mode, setMode] = useState<Mode>("simple");
  const [type, setType] = useState<Type>("MARKET");
  const [qty, setQty] = useState("10");
  const [limit, setLimit] = useState("");
  const [stop, setStop] = useState("");
  const [trailBy, setTrailBy] = useState<"percent" | "amount">("percent");
  const [trail, setTrail] = useState("2");
  const [offset, setOffset] = useState("0.05");
  const [tif, setTif] = useState<"DAY" | "GTC" | "IOC">("DAY");
  const [minutes, setMinutes] = useState("15");
  const [takeProfit, setTakeProfit] = useState("");
  const [stopLoss, setStopLoss] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string }>();

  const quantity = Math.floor(Number(qty)) || 0;
  const held = portfolio?.positions.find((p) => p.ticker === ticker)?.quantity ?? 0;
  const entryType: Type = mode === "oco" ? "LIMIT" : type;
  const needsLimit = entryType === "LIMIT" || entryType === "STOP_LIMIT";
  const needsStop = entryType === "STOP" || entryType === "STOP_LIMIT";
  const trailing = entryType === "TRAILING_STOP" || entryType === "TRAILING_STOP_LIMIT";
  const algo = entryType === "TWAP" || entryType === "VWAP";

  // Reference price for the estimate.
  const touch = side === "BUY" ? quote.ask ?? quote.last : quote.bid ?? quote.last;
  const ref = (needsLimit ? toCents(limit) : needsStop ? toCents(stop) : undefined) ?? touch;
  const notional = quantity * ref;
  const closable = side === "SELL" ? Math.max(0, held) : Math.max(0, -held);
  const opening = Math.max(0, quantity - closable);
  const marginFraction = side === "BUY" ? (portfolio?.initialMargin ?? 0.5) : 0.5;
  const margin = Math.ceil(opening * ref * marginFraction);
  const fee = quantity > 0 ? estimateCommission(portfolio?.plan ?? "ROOKIE", quantity, ref) : 0;
  const available = portfolio?.availableEquity ?? 0;
  const affordable = margin + fee <= available;
  const shortSale = side === "SELL" && quantity > closable;

  function legs(): { structure: PlaceOrderRequest["structure"]; legs: LegRequest[] } {
    const opposite: Side = side === "BUY" ? "SELL" : "BUY";
    if (mode === "oco") {
      return {
        structure: "OCO",
        legs: [
          { side, quantity, type: "LIMIT", limitPrice: toCents(takeProfit), timeInForce: "GTC" },
          { side, quantity, type: "STOP", stopPrice: toCents(stopLoss), timeInForce: "GTC" },
        ],
      };
    }
    const entry: LegRequest = {
      side,
      quantity,
      type,
      timeInForce: type === "MARKET" && tif === "GTC" ? "DAY" : tif,
      limitPrice: needsLimit ? toCents(limit) : undefined,
      stopPrice: needsStop ? toCents(stop) : undefined,
      trailPercent: trailing && trailBy === "percent" ? Number(trail) : undefined,
      trailAmount: trailing && trailBy === "amount" ? toCents(trail) : undefined,
      limitOffset: entryType === "TRAILING_STOP_LIMIT" ? Math.round(Number(offset) * 100) : undefined,
      durationMinutes: algo ? Math.floor(Number(minutes)) : undefined,
    };
    if (mode === "bracket") {
      return {
        structure: "BRACKET",
        legs: [
          entry,
          { side: opposite, quantity, type: "LIMIT", limitPrice: toCents(takeProfit), timeInForce: "GTC" },
          { side: opposite, quantity, type: "STOP", stopPrice: toCents(stopLoss), timeInForce: "GTC" },
        ],
      };
    }
    return { structure: "SINGLE", legs: [entry] };
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setResult(undefined);
    try {
      const r = await api.post<PlaceOrderResponse>("/api/v1/orders", { ticker, ...legs() });
      setResult({
        ok: true,
        text: r.executesAt ? `Queued. Executes at the ${timeET(r.executesAt)} tick.` : "Queued for the next market open.",
      });
    } catch (err) {
      setResult({ ok: false, text: err instanceof ApiError ? err.message : "Could not place the order." });
    } finally {
      setBusy(false);
    }
  }

  const verb = mode === "oco" ? `Place exit (${side === "BUY" ? "buy" : "sell"})` : `${side === "BUY" ? "Buy" : "Sell"} ${ticker}`;

  return (
    <form className="ticket" onSubmit={submit}>
      <div className="ticket-sides" role="group" aria-label="Side">
        <button type="button" className="buy" aria-pressed={side === "BUY"} onClick={() => setSide("BUY")}>Buy</button>
        <button type="button" className="sell" aria-pressed={side === "SELL"} onClick={() => setSide("SELL")}>Sell</button>
      </div>

      <div className="tabs ticket-modes" role="group" aria-label="Order kind">
        <button type="button" aria-pressed={mode === "simple"} onClick={() => setMode("simple")}>Order</button>
        <button type="button" aria-pressed={mode === "bracket"} onClick={() => { setMode("bracket"); if (type !== "MARKET" && type !== "LIMIT") setType("MARKET"); }}>Bracket</button>
        <button type="button" aria-pressed={mode === "oco"} onClick={() => setMode("oco")}>OCO exit</button>
      </div>

      <div className="ticket-grid">
        {mode !== "oco" && (
          <label>Order type
            <select value={type} onChange={(e) => setType(e.target.value as Type)}>
              {TYPES.filter((t) => mode === "simple" || t.value === "MARKET" || t.value === "LIMIT").map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
          </label>
        )}
        <label>Shares
          <input inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value.replace(/\D/g, ""))} required />
        </label>
        {mode !== "oco" && needsLimit && (
          <label>Limit price
            <input inputMode="decimal" value={limit} placeholder={price(touch)} onChange={(e) => setLimit(e.target.value)} required />
          </label>
        )}
        {mode !== "oco" && needsStop && (
          <label>Stop price
            <input inputMode="decimal" value={stop} placeholder={price(quote.last)} onChange={(e) => setStop(e.target.value)} required />
          </label>
        )}
        {mode !== "oco" && trailing && (
          <>
            <label>Trail by
              <select value={trailBy} onChange={(e) => setTrailBy(e.target.value as "percent" | "amount")}>
                <option value="percent">Percent</option>
                <option value="amount">Dollars</option>
              </select>
            </label>
            <label>{trailBy === "percent" ? "Trail %" : "Trail $"}
              <input inputMode="decimal" value={trail} onChange={(e) => setTrail(e.target.value)} required />
            </label>
            {type === "TRAILING_STOP_LIMIT" && (
              <label>Limit offset $
                <input inputMode="decimal" value={offset} onChange={(e) => setOffset(e.target.value)} required />
              </label>
            )}
          </>
        )}
        {mode !== "oco" && algo && (
          <label>Over (minutes)
            <input inputMode="numeric" value={minutes} onChange={(e) => setMinutes(e.target.value.replace(/\D/g, ""))} required />
          </label>
        )}
        {mode !== "oco" && !algo && (
          <label>Time in force
            <select value={tif} onChange={(e) => setTif(e.target.value as "DAY" | "GTC" | "IOC")}>
              <option value="DAY">Day</option>
              {type !== "MARKET" && <option value="GTC">Good till cancelled</option>}
              {(type === "MARKET" || type === "LIMIT") && <option value="IOC">Immediate or cancel</option>}
            </select>
          </label>
        )}
        {mode !== "simple" && (
          <>
            <label>Take profit (limit)
              <input inputMode="decimal" value={takeProfit} onChange={(e) => setTakeProfit(e.target.value)} required />
            </label>
            <label>Stop loss (stop)
              <input inputMode="decimal" value={stopLoss} onChange={(e) => setStopLoss(e.target.value)} required />
            </label>
          </>
        )}
      </div>

      <dl className="stats ticket-estimate">
        <div><dt>Est. {side === "BUY" ? "cost" : "proceeds"}</dt><dd>{money(notional)}</dd></div>
        <div><dt>Commission ({PLAN_LABEL[portfolio?.plan ?? "ROOKIE"]})</dt><dd>~{money(fee)}</dd></div>
        <div><dt>Margin required</dt><dd>{money(margin)}</dd></div>
        <div><dt>Buying power</dt><dd>{money(portfolio?.buyingPower)}</dd></div>
        {held !== 0 && <div><dt>You hold</dt><dd>{held > 0 ? `${held} shares` : `${-held} short`}</dd></div>}
      </dl>
      {shortSale && mode !== "oco" && <p className="hint">This sells {quantity - closable} shares short (borrowed, with a daily fee).</p>}
      {!affordable && quantity > 0 && <p className="form-error">Not enough buying power for this order.</p>}

      <button className={`btn ${side === "BUY" ? "btn-buy" : "btn-sell"}`} disabled={busy || quantity <= 0 || !portfolio}>
        {busy ? "Placing…" : verb}
      </button>
      <p className="hint">Orders queue now and execute on the next 5-second tick. Nothing is guaranteed to fill.</p>
      {result && <p className={result.ok ? "hint" : "form-error"} role="status">{result.text}</p>}
    </form>
  );
}

/** Open orders table with cancel buttons. */
export function OpenOrders({ orders, showTicker = true }: { orders: Order[]; showTicker?: boolean }) {
  const [error, setError] = useState<string>();
  if (orders.length === 0) return <div className="empty">No open orders.</div>;
  const cancel = (id: number) => api.signedDelete(`/api/v1/orders/${id}`).catch((e: Error) => setError(e.message));
  return (
    <div className="table-wrap">
      {error && <p className="form-error">{error}</p>}
      <table className="data">
        <thead>
          <tr>
            {showTicker && <th>Symbol</th>}
            <th className="left">Order</th><th>Qty</th><th>Filled</th><th>Limit</th><th>Stop</th><th className="left">Status</th><th />
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => (
            <tr key={o.id}>
              {showTicker && <td><strong>{o.ticker}</strong></td>}
              <td className="left">{o.side === "BUY" ? "Buy" : "Sell"} {o.type.replace(/_/g, " ").toLowerCase()}{o.liquidation ? " (liquidation)" : ""}</td>
              <td>{o.quantity}</td>
              <td>{o.filled}</td>
              <td>{price(o.limitPrice)}</td>
              <td>{price(o.stopPrice)}</td>
              <td className="left">{STATUS[o.status] ?? o.status}</td>
              <td>{!o.liquidation && <button className="btn-link" onClick={() => void cancel(o.id)}>Cancel</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const STATUS: Record<string, string> = {
  WAITING: "Waiting for parent",
  QUEUED: "Queued",
  ARMED: "Armed (stop)",
  WORKING: "Working",
};
