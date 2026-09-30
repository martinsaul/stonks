import { useMemo, useState } from "react";
import type { AdminOverview, TickerAdmin } from "../../api/admin";
import { dateET, price, timeET } from "../../lib/format";
import { atIso, pretty, StatusLine, useAdminAction, When } from "./shared";

function Controls({ t, o, reload }: { t: TickerAdmin; o: AdminOverview; reload: () => void }) {
  const { busy, status, run } = useAdminAction(reload);
  const [strategy, setStrategy] = useState(t.strategy);
  const [nextSession, setNextSession] = useState(false);
  const [event, setEvent] = useState(o.events[0] ?? "");
  const [when, setWhen] = useState("");
  const [tune, setTune] = useState({ volatility: String(t.volatility), depth: String(t.depth), borrowPool: t.borrowPool == null ? "" : String(t.borrowPool) });
  const [ratio, setRatio] = useState("2");
  const [dividend, setDividend] = useState("");
  const [buyback, setBuyback] = useState("5");
  const [move, setMove] = useState({ percent: "", headline: "" });

  return (
    <section className="card">
      <div className="card-head"><h2>{t.ticker} · {t.name}</h2><span className="pill">{pretty(t.status)}{t.halted ? " · halted" : ""}</span></div>
      <div className="card-body">
        <h3 className="subhead">Strategy (now {pretty(t.strategy)})</h3>
        <div className="admin-row">
          <select aria-label="Strategy" value={strategy} onChange={(e) => setStrategy(e.target.value)}>{o.strategies.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}</select>
          <label className="check"><input type="checkbox" checked={nextSession} onChange={(e) => setNextSession(e.target.checked)} /> at next open</label>
          <button className="btn btn-inline" disabled={busy} onClick={() => run({ type: "strategy", ticker: t.ticker, strategy, nextSession }, "Strategy changed.")}>Apply</button>
        </div>

        <h3 className="subhead">Company event</h3>
        <div className="admin-row">
          <select aria-label="Event" value={event} onChange={(e) => setEvent(e.target.value)}>{o.events.map((e) => <option key={e} value={e}>{pretty(e)}</option>)}</select>
        </div>
        {!["DISTRESS", "BUYOUT"].includes(event) && <When value={when} onChange={setWhen} />}
        <button className="btn btn-inline" disabled={busy} onClick={() => run({ type: "event", ticker: t.ticker, event, at: ["DISTRESS", "BUYOUT"].includes(event) ? undefined : atIso(when) }, "Event queued.")}>Trigger</button>

        <h3 className="subhead">Custom move with headline</h3>
        <div className="admin-row">
          <input aria-label="Move (%)" placeholder="Move %" inputMode="decimal" value={move.percent} onChange={(e) => setMove({ ...move, percent: e.target.value })} />
          <input aria-label="Headline" placeholder="Headline" className="grow" value={move.headline} onChange={(e) => setMove({ ...move, headline: e.target.value })} />
          <button className="btn btn-inline" disabled={busy || !move.headline.trim() || !move.percent} onClick={() => run({ type: "shock", scope: "TICKER", target: t.ticker, percent: Number(move.percent), headline: move.headline }, "Applied.")}>Apply</button>
        </div>

        <h3 className="subhead">Trading</h3>
        <div className="admin-row">
          <button className={`btn btn-inline ${t.halted ? "" : "btn-sell"}`} disabled={busy} onClick={() => run({ type: "halt", ticker: t.ticker, halted: !t.halted }, t.halted ? "Resumed." : "Halted.")}>
            {t.halted ? "Resume trading" : "Halt trading"}
          </button>
        </div>
        <div className="admin-row">
          <label className="admin-field"><span>Volatility ×</span><input inputMode="decimal" value={tune.volatility} onChange={(e) => setTune({ ...tune, volatility: e.target.value })} /></label>
          <label className="admin-field"><span>Depth ×</span><input inputMode="decimal" value={tune.depth} onChange={(e) => setTune({ ...tune, depth: e.target.value })} /></label>
          <label className="admin-field"><span>Borrow pool (0–1)</span><input inputMode="decimal" placeholder="default" value={tune.borrowPool} onChange={(e) => setTune({ ...tune, borrowPool: e.target.value })} /></label>
          <button className="btn btn-inline" disabled={busy} onClick={() => run({
            type: "tune", ticker: t.ticker, volatility: Number(tune.volatility), depth: Number(tune.depth), borrowPool: tune.borrowPool === "" ? undefined : Number(tune.borrowPool),
          }, "Tuned.")}>Tune</button>
        </div>

        <h3 className="subhead">Corporate actions</h3>
        <div className="admin-row">
          <select aria-label="Split ratio" value={ratio} onChange={(e) => setRatio(e.target.value)}>
            {["2", "3", "4", "5", "10"].map((r) => <option key={r} value={r}>{r}-for-1 split</option>)}
            {["2", "5", "10", "20"].map((r) => <option key={`r${r}`} value={String(1 / Number(r))}>1-for-{r} reverse split</option>)}
          </select>
          <button className="btn btn-inline" disabled={busy || t.pendingSplit != null} onClick={() => run({ type: "split", ticker: t.ticker, ratio: Number(ratio) }, "Split announced for next session.")}>Split</button>
        </div>
        <div className="admin-row">
          <input aria-label="Special dividend ($/share)" placeholder="Special dividend $/share" inputMode="decimal" value={dividend} onChange={(e) => setDividend(e.target.value)} />
          <button className="btn btn-inline" disabled={busy || !dividend} onClick={() => run({ type: "dividend", ticker: t.ticker, amount: Math.round(Number(dividend) * 100) }, "Special dividend declared.")}>Declare</button>
        </div>
        <div className="admin-row">
          <input aria-label="Buyback (% of shares)" inputMode="decimal" value={buyback} onChange={(e) => setBuyback(e.target.value)} />
          <button className="btn btn-inline" disabled={busy} onClick={() => run({ type: "buyback", ticker: t.ticker, percent: Number(buyback) }, "Buyback announced.")}>Buyback %</button>
        </div>
        <StatusLine status={status} />
      </div>
    </section>
  );
}

export function CompaniesTab({ o, reload }: { o: AdminOverview; reload: () => void }) {
  const [sel, setSel] = useState(o.tickers[0]?.ticker ?? "");
  const [filter, setFilter] = useState("");
  const t = o.tickers.find((x) => x.ticker === sel);
  const rows = useMemo(() => o.tickers.filter((x) => !filter || `${x.ticker} ${x.name}`.toLowerCase().includes(filter.toLowerCase())), [o, filter]);
  return (
    <div className="grid-2 admin-companies">
      <section className="card">
        <div className="card-head"><h2>Companies</h2><input aria-label="Filter" placeholder="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} /></div>
        <div className="card-body table-wrap">
          <table className="data">
            <thead><tr><th>Ticker</th><th>Last</th><th>Strategy</th><th>Status</th><th>Vol ×</th><th>Depth ×</th></tr></thead>
            <tbody>
              {rows.map((x) => (
                <tr key={x.ticker} className={x.ticker === sel ? "you-row" : undefined} onClick={() => setSel(x.ticker)} style={{ cursor: "pointer" }}>
                  <td><button className="btn-link" onClick={() => setSel(x.ticker)}>{x.ticker}</button>{x.halted && <span className="pill">halted</span>}</td>
                  <td className="num">{price(x.last)}</td>
                  <td>{pretty(x.strategy)}</td>
                  <td>{pretty(x.status)}</td>
                  <td>{x.volatility}</td>
                  <td>{x.depth}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <aside className="stack">{t && <Controls key={t.ticker} t={t} o={o} reload={reload} />}</aside>
    </div>
  );
}

export function ScheduledTab({ o }: { o: AdminOverview }) {
  return (
    <section className="card">
      <div className="card-head"><h2>Scheduled</h2></div>
      <div className="card-body">
        <p className="hint">Everything queued, including random events players can't see yet. Tick -1 = at the open.</p>
        {o.scheduled.length === 0 ? <div className="empty">Nothing scheduled.</div> : (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>When</th><th>Day / tick</th><th>Kind</th><th>Target</th><th>Detail</th></tr></thead>
              <tbody>
                {o.scheduled.map((s, i) => (
                  <tr key={i}>
                    <td>{s.date ? `${dateET(s.date)} ${timeET(s.date)}` : "—"}</td>
                    <td>{s.day} / {s.tick}</td>
                    <td>{pretty(s.kind)}</td>
                    <td>{s.target ?? "Market"}</td>
                    <td>{s.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
