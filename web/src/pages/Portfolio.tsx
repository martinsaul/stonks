import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import type { Fill } from "../api/types";
import { Change } from "../components/Change";
import { OpenOrders } from "../components/TradeTicket";
import { integer, money, price, timeET } from "../lib/format";
import { marginHealth, nextPlan, PLAN_LABEL, usePortfolio } from "../lib/portfolio";

export function PortfolioPage() {
  const p = usePortfolio();
  const [fills, setFills] = useState<Fill[]>();
  const fillCount = p?.notices.filter((n) => n.kind === "fill").length ?? 0;

  useEffect(() => {
    api.get<Fill[]>("/api/v1/fills?limit=50").then(setFills, () => undefined);
  }, [fillCount]);

  if (!p) return <div className="empty">Loading your portfolio…</div>;
  const dayChange = p.positions.reduce((s, x) => s + (x.dayChange ?? 0), 0);
  const unrealized = p.positions.reduce((s, x) => s + x.unrealized, 0);
  const next = nextPlan(p.plan);
  const health = marginHealth(p);
  const gross = p.longValue + p.shortValue;

  return (
    <>
      <h1 className="page-title">My portfolio</h1>
      <div className="tiles">
        <div className="tile"><span>Net worth</span><strong className="num">{money(p.equity)}</strong><Change cents={dayChange} /></div>
        <div className="tile"><span>Cash</span><strong className="num">{money(p.cash)}</strong>{p.marginDebt > 0 && <span className="down">Margin loan {money(p.marginDebt)}</span>}</div>
        <div className="tile"><span>Buying power</span><strong className="num">{money(p.buyingPower)}</strong><span className="muted">{(1 / p.initialMargin).toFixed(0)}× leverage</span></div>
        <div className="tile">
          <span>Margin</span>
          <strong className="num">{gross === 0 ? "—" : `${((p.equity / gross) * 100).toFixed(1)}%`}</strong>
          <span className={health === "ok" || health === "none" ? "muted" : "down"}>
            {health === "none" ? "No positions" : `Liquidation below ${(p.maintenanceMargin * 100).toFixed(0)}%`}
          </span>
        </div>
        <div className="tile">
          <span>Plan</span>
          <strong>{PLAN_LABEL[p.plan]}</strong>
          <span className="muted">{next ? `${PLAN_LABEL[next[0]]} at ${money(next[1])} realized (${money(p.lifetimeRealized)} so far)` : "Top plan"}</span>
        </div>
      </div>

      <div className="stack">
        <section className="card" aria-labelledby="positions">
          <div className="card-head"><h2 id="positions">Positions</h2><span className="muted">Unrealized <Change cents={unrealized} /></span></div>
          <div className="card-body table-wrap">
            {p.positions.length === 0 ? (
              <div className="empty">No positions yet. Find a stock in the <Link to="/screener">screener</Link> and place your first trade.</div>
            ) : (
              <table className="data">
                <thead><tr><th>Symbol</th><th>Shares</th><th>Avg. cost</th><th>Last</th><th>Market value</th><th>Day change</th><th>Unrealized P/L</th></tr></thead>
                <tbody>
                  {p.positions.map((x) => (
                    <tr key={x.ticker}>
                      <td className="ticker-cell"><Link to={`/quote/${x.ticker}`}>{x.ticker}</Link>{x.quantity < 0 && <span className="name">Short</span>}</td>
                      <td>{integer(x.quantity)}</td>
                      <td>{price(Math.round(x.avgPrice))}</td>
                      <td>{price(x.last)}</td>
                      <td>{money(x.marketValue)}</td>
                      <td><Change cents={x.dayChange} /></td>
                      <td><Change cents={x.unrealized} pct={x.unrealizedPct} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>

        <section className="card" aria-labelledby="open">
          <div className="card-head"><h2 id="open">Open orders</h2></div>
          <div className="card-body"><OpenOrders orders={p.openOrders} /></div>
        </section>

        <section className="card" aria-labelledby="history">
          <div className="card-head"><h2 id="history">Recent trades</h2><span className="muted">Commissions paid {money(p.commissionsPaid)} · Interest & fees {money(p.interestPaid)}</span></div>
          <div className="card-body table-wrap">
            {!fills ? <div className="empty">Loading…</div> : fills.length === 0 ? <div className="empty">No trades yet.</div> : (
              <table className="data">
                <thead><tr><th>Time</th><th>Symbol</th><th className="left">Side</th><th>Shares</th><th>Price</th><th>Commission</th><th>Realized</th></tr></thead>
                <tbody>
                  {fills.map((f) => (
                    <tr key={f.id}>
                      <td>{timeET(f.at, true)}</td>
                      <td><Link to={`/quote/${f.ticker}`}>{f.ticker}</Link></td>
                      <td className="left">{f.side === "BUY" ? "Buy" : "Sell"}{f.liquidation ? " (liquidation)" : ""}</td>
                      <td>{integer(f.quantity)}</td>
                      <td>{price(f.price)}</td>
                      <td>{money(f.commission)}</td>
                      <td>{f.realized === 0 ? "—" : <Change cents={f.realized} />}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
      </div>
    </>
  );
}
