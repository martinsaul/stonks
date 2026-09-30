import { useState } from "react";
import type { AdminOverview } from "../../api/admin";
import { dateET, money, timeET } from "../../lib/format";
import { atIso, pretty, StatusLine, useAdminAction, When } from "./shared";

export function MarketTab({ o, reload }: { o: AdminOverview; reload: () => void }) {
  const { busy, status, run } = useAdminAction(reload);
  const [regime, setRegime] = useState(o.regime);
  const [rate, setRate] = useState(o.benchmarkRate.toFixed(2));
  const [shock, setShock] = useState({ scope: "MARKET", target: "", percent: "-5", headline: "", when: "" });
  const [bond, setBond] = useState({ name: "Stonks Savings Bond", returnPct: "8", windowDays: "5", termDays: "52", cap: "5000" });
  const [ipoSector, setIpoSector] = useState("");
  const [ipoDays, setIpoDays] = useState("3");
  const halted = o.tickers.filter((t) => t.halted).length;

  return (
    <div className="grid-2">
      <div className="stack">
        <section className="card">
          <div className="card-head"><h2>Macro</h2></div>
          <div className="card-body">
            <dl className="stats">
              <div><dt>Game day</dt><dd>{o.day}{o.open ? `, tick ${o.tick}` : " (closed)"}</dd></div>
              <div><dt>Players</dt><dd>{o.players}</dd></div>
              <div><dt>Halted tickers</dt><dd>{halted}</dd></div>
              <div><dt>Next rate decision</dt><dd>{o.nextRateDecision ? `${dateET(o.nextRateDecision)} ${timeET(o.nextRateDecision)}` : "—"}</dd></div>
            </dl>
            <div className="admin-row">
              <label className="admin-field"><span>Regime</span>
                <select value={regime} onChange={(e) => setRegime(e.target.value)}>{o.regimes.map((r) => <option key={r} value={r}>{pretty(r)}</option>)}</select>
              </label>
              <button className="btn btn-inline" disabled={busy || regime === o.regime} onClick={() => run({ type: "regime", regime }, `Regime set to ${pretty(regime)}.`)}>Set regime</button>
            </div>
            <div className="admin-row">
              <label className="admin-field"><span>Benchmark rate (%)</span><input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} /></label>
              <button className="btn btn-inline" disabled={busy} onClick={() => run({ type: "rate", percent: Number(rate) }, `Rate set to ${rate}%.`)}>Set rate</button>
            </div>
            <div className="admin-row">
              <button className="btn btn-inline btn-sell" disabled={busy} onClick={() => run({ type: "halt", halted: true }, "All trading halted.")}>Halt all trading</button>
              <button className="btn btn-inline" disabled={busy} onClick={() => run({ type: "halt", halted: false }, "All trading resumed.")}>Resume all</button>
            </div>
          </div>
        </section>

        <section className="card">
          <div className="card-head"><h2>Market or sector shock</h2></div>
          <div className="card-body">
            <p className="hint">Appears in the news feed as ordinary news. Scaled by each stock's market or sector beta.</p>
            <div className="admin-row">
              <label className="admin-field"><span>Scope</span>
                <select value={shock.scope} onChange={(e) => setShock({ ...shock, scope: e.target.value, target: "" })}>
                  <option value="MARKET">Whole market</option><option value="SECTOR">Sector</option><option value="TICKER">One stock</option>
                </select>
              </label>
              {shock.scope === "SECTOR" && (
                <label className="admin-field"><span>Sector</span>
                  <select value={shock.target} onChange={(e) => setShock({ ...shock, target: e.target.value })}>
                    <option value="">Choose…</option>{o.sectors.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}
                  </select>
                </label>
              )}
              {shock.scope === "TICKER" && (
                <label className="admin-field"><span>Ticker</span>
                  <select value={shock.target} onChange={(e) => setShock({ ...shock, target: e.target.value })}>
                    <option value="">Choose…</option>{o.tickers.map((t) => <option key={t.ticker} value={t.ticker}>{t.ticker}</option>)}
                  </select>
                </label>
              )}
              <label className="admin-field"><span>Move (%)</span><input inputMode="decimal" value={shock.percent} onChange={(e) => setShock({ ...shock, percent: e.target.value })} /></label>
            </div>
            <label className="admin-field wide"><span>Headline</span><input value={shock.headline} maxLength={200} onChange={(e) => setShock({ ...shock, headline: e.target.value })} /></label>
            <When value={shock.when} onChange={(when) => setShock({ ...shock, when })} />
            <button className="btn btn-inline" disabled={busy || !shock.headline.trim()} onClick={() => run({
              type: "shock", scope: shock.scope, target: shock.target || undefined, percent: Number(shock.percent), headline: shock.headline, at: atIso(shock.when),
            }, shock.when ? "Shock scheduled." : "Shock applied.")}>{shock.when ? "Schedule" : "Apply now"}</button>
          </div>
        </section>
        <StatusLine status={status} />
      </div>

      <aside className="stack">
        <section className="card">
          <div className="card-head"><h2>Bond offering</h2></div>
          <div className="card-body">
            <label className="admin-field wide"><span>Name</span><input value={bond.name} onChange={(e) => setBond({ ...bond, name: e.target.value })} /></label>
            <div className="admin-row">
              <label className="admin-field"><span>Return (%)</span><input inputMode="decimal" value={bond.returnPct} onChange={(e) => setBond({ ...bond, returnPct: e.target.value })} /></label>
              <label className="admin-field"><span>Cap per player ($)</span><input inputMode="numeric" value={bond.cap} onChange={(e) => setBond({ ...bond, cap: e.target.value })} /></label>
            </div>
            <div className="admin-row">
              <label className="admin-field"><span>Window (sessions)</span><input inputMode="numeric" value={bond.windowDays} onChange={(e) => setBond({ ...bond, windowDays: e.target.value })} /></label>
              <label className="admin-field"><span>Term (sessions)</span><input inputMode="numeric" value={bond.termDays} onChange={(e) => setBond({ ...bond, termDays: e.target.value })} /></label>
            </div>
            <p className="hint">About 12 sessions per week: 1 month ≈ 52, 3 months ≈ 156.</p>
            <button className="btn btn-inline" disabled={busy} onClick={() => run({
              type: "bonds", name: bond.name, returnPct: Number(bond.returnPct), windowDays: Number(bond.windowDays), termDays: Number(bond.termDays), cap: Math.round(Number(bond.cap) * 100),
            }, "Bond offering issued.")}>Issue</button>
            {o.bonds.length > 0 && (
              <table className="data">
                <thead><tr><th>Offering</th><th>Return</th><th>Cap</th><th>State</th></tr></thead>
                <tbody>{o.bonds.map((b) => <tr key={b.id}><td>{b.name}</td><td>{b.returnPct.toFixed(1)}%</td><td>{money(b.capPerPlayer)}</td><td>{b.open ? "Open" : "Closed"}</td></tr>)}</tbody>
              </table>
            )}
          </div>
        </section>
        <section className="card">
          <div className="card-head"><h2>IPO</h2></div>
          <div className="card-body">
            <div className="admin-row">
              <label className="admin-field"><span>Sector</span>
                <select value={ipoSector} onChange={(e) => setIpoSector(e.target.value)}>
                  <option value="">Random</option>{o.sectors.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}
                </select>
              </label>
              <label className="admin-field"><span>Lists in (sessions)</span><input inputMode="numeric" value={ipoDays} onChange={(e) => setIpoDays(e.target.value)} /></label>
            </div>
            <button className="btn btn-inline" disabled={busy} onClick={() => run({ type: "ipo", sector: ipoSector || undefined, days: Number(ipoDays) }, "IPO scheduled.")}>Schedule IPO</button>
          </div>
        </section>
      </aside>
    </div>
  );
}
