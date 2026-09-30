import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client";
import type { CalendarEvent, CalendarResponse } from "../api/types";
import { dateET, price, timeET } from "../lib/format";
import { useMarket } from "../lib/market";

const KINDS: Record<string, string> = {
  EARNINGS: "Earnings", EX_DIVIDEND: "Ex-dividend", DIVIDEND_PAY: "Dividend paid", SPLIT: "Split",
  DEAL_CLOSE: "Deal closes", IPO: "IPO", RATE_DECISION: "Rate decision",
};
const FILTERS = ["ALL", "EARNINGS", "DIVIDENDS", "CORPORATE", "MACRO"] as const;
type Filter = (typeof FILTERS)[number];
const FILTER_LABEL: Record<Filter, string> = { ALL: "All", EARNINGS: "Earnings", DIVIDENDS: "Dividends", CORPORATE: "Splits, deals & IPOs", MACRO: "Rates" };
const matches = (f: Filter, e: CalendarEvent) =>
  f === "ALL" ||
  (f === "EARNINGS" && e.kind === "EARNINGS") ||
  (f === "DIVIDENDS" && (e.kind === "EX_DIVIDEND" || e.kind === "DIVIDEND_PAY")) ||
  (f === "CORPORATE" && ["SPLIT", "DEAL_CLOSE", "IPO"].includes(e.kind)) ||
  (f === "MACRO" && e.kind === "RATE_DECISION");

/** Upcoming earnings, dividends, corporate actions and rate decisions (next 60 game days). */
export function CalendarPage() {
  const { market } = useMarket();
  const [data, setData] = useState<CalendarResponse>();
  const [error, setError] = useState<string>();
  const [filter, setFilter] = useState<Filter>("ALL");
  const day = market?.session.state === "OPEN" ? market.session.opensAt : market?.session.nextOpen;

  // Reload when the game day changes.
  useEffect(() => {
    api.get<CalendarResponse>("/api/v1/calendar").then(setData, (e: Error) => setError(e.message));
  }, [day]);

  const days = useMemo(() => {
    const by = new Map<string, CalendarEvent[]>();
    for (const e of data?.events ?? []) {
      if (!matches(filter, e)) continue;
      // Weekdays have two sessions: group by calendar date.
      const d = dateET(e.date);
      by.set(d, [...(by.get(d) ?? []), e]);
    }
    return [...by.entries()];
  }, [data, filter]);

  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading calendar…</div>;

  return (
    <div className="grid-2">
      <section className="card" aria-labelledby="cal">
        <div className="card-head">
          <h2 id="cal">Calendar</h2>
          <div className="tabs" role="group" aria-label="Filter">
            {FILTERS.map((f) => <button key={f} aria-pressed={filter === f} onClick={() => setFilter(f)}>{FILTER_LABEL[f]}</button>)}
          </div>
        </div>
        <div className="card-body">
          {days.length === 0 && <div className="empty">Nothing scheduled.</div>}
          {days.map(([date, events]) => (
            <div key={date} className="calendar-day">
              <h3>{date}</h3>
              <div className="table-wrap">
                <table className="data">
                  <tbody>
                    {events.map((e, i) => (
                      <tr key={i}>
                        <td className="cal-kind">{KINDS[e.kind] ?? e.kind}<div className="hint">{timeET(e.date)} ET</div></td>
                        <td>
                          {e.ticker ? <Link to={`/quote/${e.ticker}`}><strong>{e.ticker}</strong></Link> : <strong>Market</strong>}
                          {e.name && <span className="muted"> {e.name}</span>}
                          <div className="muted">{e.detail}</div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}
          <p className="hint">Dates are game sessions; weekend sessions skip earnings and ex-dividend dates, which then move to the next weekday.</p>
        </div>
      </section>

      <aside className="stack">
        <section className="card" aria-labelledby="rate">
          <div className="card-head"><h2 id="rate">Benchmark rate</h2></div>
          <div className="card-body">
            <dl className="stats">
              <div><dt>Fedora Reserve rate</dt><dd>{data.benchmarkRate.toFixed(2)}%</dd></div>
            </dl>
            <p className="hint">Margin interest is this rate plus your plan's spread.</p>
          </div>
        </section>
        <section className="card" aria-labelledby="delisted">
          <div className="card-head"><h2 id="delisted">Recent delistings</h2></div>
          <div className="card-body">
            {data.delistings.length === 0 ? <div className="empty">None yet.</div> : (
              <div className="table-wrap">
              <table className="data">
                <thead><tr><th>Ticker</th><th>Settled at</th><th>Reason</th></tr></thead>
                <tbody>
                  {[...data.delistings].reverse().map((d) => (
                    <tr key={`${d.ticker}-${d.day}`}>
                      <td><strong>{d.ticker}</strong> <span className="muted">{d.name}</span></td>
                      <td className="num">{price(d.price)}</td>
                      <td className="muted">{d.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
            )}
          </div>
        </section>
      </aside>
    </div>
  );
}
