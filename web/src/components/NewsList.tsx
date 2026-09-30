import { Link } from "react-router-dom";
import type { NewsItem } from "../api/types";
import { dateET, SECTORS, timeET } from "../lib/format";
import { useNews } from "../lib/news";

const CATEGORY: Record<string, string> = {
  EARNINGS: "Earnings", DIVIDEND: "Dividend", CORPORATE: "Company", REGULATORY: "Regulatory", LEGAL: "Legal",
  ANALYST: "Analysts", RUMOR: "Rumor", SECTOR: "Sector", MACRO: "Macro", DEAL: "Deal", LISTING: "IPO",
  SPLIT: "Split", DISTRESS: "Distress",
};

function when(iso: string): string {
  const today = dateET(Date.now());
  return dateET(iso) === today ? timeET(iso) : `${dateET(iso)}, ${timeET(iso)}`;
}

function Item({ n, showTicker }: { n: NewsItem; showTicker: boolean }) {
  const tone = n.tone === "positive" ? "up" : n.tone === "negative" ? "down" : "";
  return (
    <li className={`news-item ${n.category === "RUMOR" ? "rumor" : ""}`}>
      <div className="news-meta">
        <span className={`pill news-cat ${tone}`}>{CATEGORY[n.category] ?? n.category}</span>
        {showTicker && n.ticker && <Link to={`/quote/${n.ticker}`} className="news-ticker">{n.ticker}</Link>}
        {!n.ticker && n.sector && <span className="muted">{SECTORS[n.sector] ?? n.sector}</span>}
        <time className="muted" dateTime={n.time}>{when(n.time)} ET</time>
      </div>
      <p className="news-headline">{n.headline}</p>
    </li>
  );
}

/** Live news list. Rumors are marked: some turn out to be false. */
export function NewsList({ ticker, limit = 15 }: { ticker?: string; limit?: number }) {
  const { items, error, loadMore } = useNews(ticker, limit);
  if (error) return <div className="muted">News unavailable: {error}</div>;
  if (!items) return <div className="empty">Loading news…</div>;
  if (items.length === 0) return <div className="empty">No news yet.</div>;
  return (
    <>
      <ul className="news">
        {items.map((n) => <Item key={n.id} n={n} showTicker={!ticker || n.ticker !== ticker} />)}
      </ul>
      {items.length >= limit && <button className="btn-link" onClick={loadMore}>Older news</button>}
    </>
  );
}
