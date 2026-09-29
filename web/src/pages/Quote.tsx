import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import { useFeedFrame, useLiveQuotes } from "../api/feed";
import type { QuoteResponse } from "../api/types";
import { Change } from "../components/Change";
import { DepthBook } from "../components/DepthBook";
import { Flash } from "../components/Flash";
import { OpenOrders, TradeTicket } from "../components/TradeTicket";
import { usePortfolio } from "../lib/portfolio";
import { PriceChart } from "../components/PriceChart";
import { compact, integer, money, price, SECTORS, timeET } from "../lib/format";
import { useMarket } from "../lib/market";
import { loadRange, RANGES, type Range, type Series } from "../lib/ranges";
import { toggleWatch, useWatchlist } from "../lib/watchlist";

export function QuotePage() {
  const ticker = (useParams().ticker ?? "").toUpperCase();
  const { market } = useMarket();
  const [detail, setDetail] = useState<QuoteResponse>();
  const [error, setError] = useState<string>();
  const [range, setRange] = useState<Range>("1D");
  const [kind, setKind] = useState<"candle" | "line">("candle");
  const [series, setSeries] = useState<Series>();
  const watch = useWatchlist();
  const portfolio = usePortfolio();

  const live = useLiveQuotes([ticker], [ticker]);
  const frame = useFeedFrame();

  useEffect(() => {
    setDetail(undefined);
    setError(undefined);
    api.get<QuoteResponse>(`/api/v1/quotes/${ticker}`).then(setDetail, (e: ApiError) => setError(e.message));
  }, [ticker]);

  useEffect(() => {
    let cancelled = false;
    setSeries(undefined);
    loadRange(ticker, range).then((s) => !cancelled && setSeries(s), (e: ApiError) => !cancelled && setError(e.message));
    return () => { cancelled = true; };
  }, [ticker, range]);

  if (error && !detail) {
    return <div className="error-banner">{error} <Link to="/">Back to markets</Link></div>;
  }
  if (!detail) return <div className="empty">Loading {ticker}…</div>;

  const q = live.get(ticker) ?? market?.byTicker.get(ticker) ?? detail.quote;
  const session = market?.session ?? detail.session;
  const depth = frame?.depth[ticker] ?? detail.depth;
  const p = detail.profile;
  const watched = watch.includes(ticker);
  const s = detail.stats;

  return (
    <>
      <div className="quote-head">
        <h1>
          {p.name} ({p.ticker})
          <button className="star" aria-pressed={watched} onClick={() => toggleWatch(ticker)}>
            {watched ? "★ Watching" : "☆ Watch"}
          </button>
        </h1>
        <div className="meta">{SECTORS[p.sector] ?? p.sector} · Stonks Exchange · Prices in USD</div>
        <div className="quote-price">
          <Flash value={q.last}><span className="last num">{price(q.last)}</span></Flash>
          <Change className="chg" cents={q.change} pct={q.changePct} />
        </div>
        <div className="meta">
          {session.state === "OPEN" ? `Live · as of ${timeET(market?.time ?? detail.time)} ET` : "At close · market closed"}
        </div>
      </div>

      <div className="grid-2">
        <div className="stack">
          <section className="card" aria-label="Chart">
            <div className="card-head">
              <div className="tabs" role="group" aria-label="Range">
                {RANGES.map((r) => <button key={r} aria-pressed={range === r} onClick={() => setRange(r)}>{r}</button>)}
              </div>
              <div className="tabs" role="group" aria-label="Chart type">
                <button aria-pressed={kind === "candle"} onClick={() => setKind("candle")}>Candles</button>
                <button aria-pressed={kind === "line"} onClick={() => setKind("line")}>Line</button>
              </div>
            </div>
            <div className="card-body">
              <PriceChart series={series} kind={kind} quote={q} session={session} frameTime={frame?.time} />
            </div>
          </section>

          {portfolio && (() => {
            const pos = portfolio.positions.find((x) => x.ticker === ticker);
            const mine = portfolio.openOrders.filter((o) => o.ticker === ticker);
            return (
              <section className="card" aria-labelledby="mine">
                <div className="card-head"><h2 id="mine">Your {ticker}</h2></div>
                <div className="card-body">
                  {pos ? (
                    <dl className="stats">
                      <div><dt>{pos.quantity < 0 ? "Short" : "Shares"}</dt><dd>{integer(Math.abs(pos.quantity))}</dd></div>
                      <div><dt>Avg. cost</dt><dd>{price(Math.round(pos.avgPrice))}</dd></div>
                      <div><dt>Market value</dt><dd>{money(Math.abs(pos.marketValue))}</dd></div>
                      <div><dt>Unrealized P/L</dt><dd><Change cents={pos.unrealized} pct={pos.unrealizedPct} /></dd></div>
                    </dl>
                  ) : <p className="muted">You don't hold {ticker}.</p>}
                  {mine.length > 0 && <><h3 className="subhead">Open orders</h3><OpenOrders orders={mine} showTicker={false} /></>}
                </div>
              </section>
            );
          })()}
        </div>

        <aside className="stack">
          <section className="card" aria-labelledby="ticket">
            <div className="card-head"><h2 id="ticket">Trade {ticker}</h2></div>
            <div className="card-body"><TradeTicket ticker={ticker} quote={q} portfolio={portfolio} /></div>
          </section>
          <section className="card" aria-labelledby="summary">
            <div className="card-head"><h2 id="summary">Summary</h2></div>
            <div className="card-body">
              <dl className="stats">
                <div><dt>Previous close</dt><dd>{price(q.prevClose)}</dd></div>
                <div><dt>Open</dt><dd>{price(q.open)}</dd></div>
                <div><dt>Bid</dt><dd>{q.bid != null ? `${price(q.bid)} × ${integer(q.bidSize)}` : "—"}</dd></div>
                <div><dt>Ask</dt><dd>{q.ask != null ? `${price(q.ask)} × ${integer(q.askSize)}` : "—"}</dd></div>
                <div><dt>Day's range</dt><dd>{q.low != null ? `${price(q.low)} – ${price(q.high)}` : "—"}</dd></div>
                <div><dt>52-week range</dt><dd>{s.low52w != null ? `${price(s.low52w)} – ${price(s.high52w)}` : "—"}</dd></div>
                <div><dt>Volume</dt><dd>{integer(q.volume)}</dd></div>
                <div><dt>Avg. volume (30d)</dt><dd>{integer(s.avgVolume30d)}</dd></div>
                <div><dt>Market cap</dt><dd>{compact(q.marketCap)}</dd></div>
                <div><dt>Shares outstanding</dt><dd>{compact(p.sharesOutstanding)}</dd></div>
              </dl>
            </div>
          </section>
          <section className="card" aria-labelledby="book">
            <div className="card-head"><h2 id="book">Order book</h2></div>
            <div className="card-body"><DepthBook depth={depth} /></div>
          </section>
        </aside>
      </div>
    </>
  );
}
