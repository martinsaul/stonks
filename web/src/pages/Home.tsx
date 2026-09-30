import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { Change } from "../components/Change";
import { NewsList } from "../components/NewsList";
import { QuoteTable } from "../components/QuoteTable";
import { dateET, REGIMES, SECTORS, timeET } from "../lib/format";
import { useMarket } from "../lib/market";
import { useWatchlist } from "../lib/watchlist";

type Mover = "gainers" | "losers" | "active";

export function Home() {
  const { market, error } = useMarket();
  const [tab, setTab] = useState<Mover>("gainers");
  const watch = useWatchlist();

  const movers = useMemo(() => {
    if (!market) return [];
    const q = [...market.quotes];
    if (tab === "active") return q.sort((a, b) => b.volume - a.volume).slice(0, 10);
    const pct = (x: (typeof q)[number]) => x.changePct ?? 0;
    return q.sort((a, b) => (tab === "gainers" ? pct(b) - pct(a) : pct(a) - pct(b))).slice(0, 10);
  }, [market, tab]);

  const sectors = useMemo(() => {
    if (!market) return [];
    const by = new Map<string, number[]>();
    market.quotes.forEach((q) => by.set(q.sector, [...(by.get(q.sector) ?? []), q.changePct ?? 0]));
    return [...by.entries()]
      .map(([s, v]) => ({ sector: s, pct: v.reduce((a, b) => a + b, 0) / v.length, count: v.length }))
      .sort((a, b) => b.pct - a.pct);
  }, [market]);

  if (error) return <div className="error-banner">{error}</div>;
  if (!market) return <div className="empty">Loading market…</div>;
  const watched = watch.map((t) => market.byTicker.get(t)).filter((q) => q !== undefined);
  const s = market.session;

  return (
    <div className="grid-2">
      <div className="stack">
        <section className="card" aria-labelledby="movers">
          <div className="card-head">
            <h2 id="movers">Market movers</h2>
            <div className="tabs" role="group" aria-label="Movers">
              {(["gainers", "losers", "active"] as Mover[]).map((t) => (
                <button key={t} aria-pressed={tab === t} onClick={() => setTab(t)}>
                  {t === "gainers" ? "Top gainers" : t === "losers" ? "Top losers" : "Most active"}
                </button>
              ))}
            </div>
          </div>
          <div className="card-body"><QuoteTable quotes={movers} /></div>
        </section>

        <section className="card" aria-labelledby="news">
          <div className="card-head"><h2 id="news">Latest news</h2><Link to="/calendar">Calendar</Link></div>
          <div className="card-body"><NewsList /></div>
        </section>

        <section className="card" aria-labelledby="sectors">
          <div className="card-head"><h2 id="sectors">Sectors today</h2></div>
          <div className="card-body table-wrap">
            <table className="data">
              <thead><tr><th>Sector</th><th>Companies</th><th>Avg. % change</th></tr></thead>
              <tbody>
                {sectors.map((x) => (
                  <tr key={x.sector}>
                    <td><Link to={`/screener?sector=${x.sector}`}>{SECTORS[x.sector] ?? x.sector}</Link></td>
                    <td>{x.count}</td>
                    <td><Change pct={x.pct} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <aside className="stack">
        <section className="card" aria-labelledby="status">
          <div className="card-head"><h2 id="status">Market status</h2></div>
          <div className="card-body">
            <dl className="stats">
              <div><dt>Status</dt><dd>{s.state === "OPEN" ? "Open" : "Closed"}</dd></div>
              {s.state === "OPEN" ? (
                <div><dt>Closes</dt><dd>{s.closesAt && timeET(s.closesAt)} ET</dd></div>
              ) : (
                <div><dt>Next open</dt><dd>{s.nextOpen && `${dateET(s.nextOpen)}, ${timeET(s.nextOpen)} ET`}</dd></div>
              )}
              <div><dt>Market phase</dt><dd>{REGIMES[market.regime] ?? market.regime}</dd></div>
              <div><dt>{market.index.name}</dt><dd><Change pct={market.index.changePct} /></dd></div>
              <div><dt>Benchmark rate</dt><dd>{market.benchmarkRate.toFixed(2)}%</dd></div>
            </dl>
            <p className="hint">Weekdays 6:00–13:30 and 14:30–22:00 ET; weekends 10:00–20:00 ET (calmer).</p>
          </div>
        </section>

        <section className="card" aria-labelledby="watchlist">
          <div className="card-head"><h2 id="watchlist">My watchlist</h2></div>
          <div className="card-body">
            {watched.length === 0
              ? <div className="empty">Add stocks with the ☆ Watch button on any quote page.</div>
              : <QuoteTable quotes={watched} columns={["ticker", "last", "changePct"]} />}
          </div>
        </section>
      </aside>
    </div>
  );
}
