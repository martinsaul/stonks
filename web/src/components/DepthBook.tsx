import type { Depth } from "../api/types";
import { integer, price } from "../lib/format";

/** Level-2 order book: asks above (best nearest the spread), bids below. */
export function DepthBook({ depth }: { depth?: Depth }) {
  if (!depth || (depth.bids.length === 0 && depth.asks.length === 0)) {
    return <div className="empty">The order book is empty while the market is closed.</div>;
  }
  const max = Math.max(1, ...depth.bids.map((l) => l.size), ...depth.asks.map((l) => l.size));
  const asks = [...depth.asks].reverse();
  const bestBid = depth.bids[0]?.price, bestAsk = depth.asks[0]?.price;
  const row = (side: "bid" | "ask", l: { price: number; size: number }) => (
    <tr key={side + l.price}>
      <td className={side === "bid" ? "up" : "down"}><span>{price(l.price)}</span></td>
      <td>
        <div className={`bar ${side}`} style={{ width: `${(l.size / max) * 100}%` }} />
        <span>{integer(l.size)}</span>
      </td>
    </tr>
  );
  return (
    <table className="depth" aria-label="Order book">
      <thead>
        <tr><th>Price</th><th>Size (shares)</th></tr>
      </thead>
      <tbody>
        {asks.map((l) => row("ask", l))}
        <tr className="spread">
          <td colSpan={2}>
            {bestBid != null && bestAsk != null ? `Spread ${price(bestAsk - bestBid)}` : "—"}
          </td>
        </tr>
        {depth.bids.map((l) => row("bid", l))}
      </tbody>
    </table>
  );
}
