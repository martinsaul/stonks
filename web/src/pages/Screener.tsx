import { useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { QuoteTable } from "../components/QuoteTable";
import { SECTORS } from "../lib/format";
import { useMarket } from "../lib/market";

export function Screener() {
  const { market } = useMarket();
  const [params, setParams] = useSearchParams();
  const sector = params.get("sector") ?? "";
  const text = params.get("q") ?? "";
  const move = params.get("move") ?? "";

  const set = (k: string, v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set(k, v); else next.delete(k);
    setParams(next, { replace: true });
  };

  const rows = useMemo(() => {
    const t = text.trim().toLowerCase();
    return (market?.quotes ?? []).filter((q) =>
      (!sector || q.sector === sector) &&
      (!t || q.ticker.toLowerCase().includes(t) || q.name.toLowerCase().includes(t)) &&
      (!move || (move === "up" ? (q.changePct ?? 0) > 0 : (q.changePct ?? 0) < 0)),
    );
  }, [market, sector, text, move]);

  return (
    <>
      <h1 className="page-title">Stock screener</h1>
      <section className="card">
        <div className="filters" role="group" aria-label="Filters">
          <input type="search" placeholder="Filter by name or ticker" aria-label="Filter by name or ticker" value={text} onChange={(e) => set("q", e.target.value)} />
          <select aria-label="Sector" value={sector} onChange={(e) => set("sector", e.target.value)}>
            <option value="">All sectors</option>
            {Object.entries(SECTORS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <select aria-label="Today's move" value={move} onChange={(e) => set("move", e.target.value)}>
            <option value="">Any move</option>
            <option value="up">Up today</option>
            <option value="down">Down today</option>
          </select>
          <span className="muted" style={{ alignSelf: "center" }}>{rows.length} of {market?.quotes.length ?? 0}</span>
        </div>
        <div className="card-body" style={{ paddingTop: 0 }}>
          {market ? <QuoteTable quotes={rows} sortable columns={["ticker", "sector", "last", "change", "changePct", "volume", "marketCap"]} /> : <div className="empty">Loading…</div>}
        </div>
      </section>
    </>
  );
}
