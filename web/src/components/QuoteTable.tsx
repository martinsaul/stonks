import { useState } from "react";
import { Link } from "react-router-dom";
import type { Quote } from "../api/types";
import { compact, price, SECTORS } from "../lib/format";
import { Change } from "./Change";
import { Flash } from "./Flash";

type Col = "ticker" | "last" | "change" | "changePct" | "volume" | "marketCap" | "sector";

const COLS: { key: Col; label: string; left?: boolean }[] = [
  { key: "ticker", label: "Symbol", left: true },
  { key: "last", label: "Price" },
  { key: "change", label: "Change" },
  { key: "changePct", label: "% Change" },
  { key: "volume", label: "Volume" },
  { key: "marketCap", label: "Market cap" },
  { key: "sector", label: "Sector", left: true },
];

function value(q: Quote, c: Col): number | string {
  switch (c) {
    case "ticker": return q.ticker;
    case "sector": return q.sector;
    default: return q[c] ?? -Infinity;
  }
}

/** Yahoo-style quote table. Sortable when [sortable] is set. */
export function QuoteTable({
  quotes,
  sortable = false,
  columns = ["ticker", "last", "change", "changePct", "volume", "marketCap"],
  caption,
}: {
  quotes: Quote[];
  sortable?: boolean;
  columns?: Col[];
  caption?: string;
}) {
  const [sort, setSort] = useState<{ col: Col; desc: boolean }>();
  const rows = sort
    ? [...quotes].sort((a, b) => {
        const x = value(a, sort.col), y = value(b, sort.col);
        const r = typeof x === "string" ? x.localeCompare(String(y)) : x - (y as number);
        return sort.desc ? -r : r;
      })
    : quotes;
  const cols = COLS.filter((c) => columns.includes(c.key));

  return (
    <div className="table-wrap">
      <table className="data">
        {caption && <caption className="visually-hidden" style={{ position: "absolute", left: -9999 }}>{caption}</caption>}
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c.key} className={c.left ? "left" : ""} aria-sort={sort?.col === c.key ? (sort.desc ? "descending" : "ascending") : undefined}>
                {sortable ? (
                  <button onClick={() => setSort((s) => ({ col: c.key, desc: s?.col === c.key ? !s.desc : c.key !== "ticker" && c.key !== "sector" }))}>
                    {c.label}{sort?.col === c.key ? (sort.desc ? " ↓" : " ↑") : ""}
                  </button>
                ) : c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((q) => (
            <tr key={q.ticker}>
              {cols.map((c) => {
                switch (c.key) {
                  case "ticker":
                    return (
                      <td key={c.key} className="ticker-cell">
                        <Link to={`/quote/${q.ticker}`}>{q.ticker}</Link>
                        <span className="name">{q.name}</span>
                      </td>
                    );
                  case "last":
                    return <td key={c.key}><Flash value={q.last}><strong>{price(q.last)}</strong></Flash></td>;
                  case "change":
                    return <td key={c.key}><Change cents={q.change} /></td>;
                  case "changePct":
                    return <td key={c.key}><Change pct={q.changePct} /></td>;
                  case "volume":
                    return <td key={c.key}>{compact(q.volume)}</td>;
                  case "marketCap":
                    return <td key={c.key}>{compact(q.marketCap)}</td>;
                  case "sector":
                    return <td key={c.key} className="left">{SECTORS[q.sector] ?? q.sector}</td>;
                }
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
