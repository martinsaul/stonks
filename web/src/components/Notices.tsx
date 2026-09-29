import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import type { Notice } from "../api/types";
import { money } from "../lib/format";
import { marginHealth, usePortfolio } from "../lib/portfolio";

/** Toasts for new account notices (fills, rejections, margin calls, plan changes). */
export function Toasts() {
  const p = usePortfolio();
  const seen = useRef<number | undefined>(undefined);
  const [toasts, setToasts] = useState<Notice[]>([]);

  useEffect(() => {
    if (!p) return;
    const latest = p.notices[0]?.seq ?? 0;
    if (seen.current === undefined) {
      seen.current = latest; // don't replay history on page load
      return;
    }
    const fresh = p.notices.filter((n) => n.seq > seen.current!).reverse();
    if (fresh.length === 0) return;
    seen.current = latest;
    setToasts((t) => [...t, ...fresh].slice(-4));
    const ids = fresh.map((n) => n.seq);
    setTimeout(() => setToasts((t) => t.filter((x) => !ids.includes(x.seq))), 6000);
  }, [p]);

  if (toasts.length === 0) return null;
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((n) => (
        <div key={n.seq} className={`toast toast-${n.kind}`}>{n.text}</div>
      ))}
    </div>
  );
}

/** Banner shown while the account is near or below maintenance margin. */
export function MarginBanner() {
  const p = usePortfolio();
  if (!p) return null;
  const h = marginHealth(p);
  if (h !== "warning" && h !== "call") return null;
  return (
    <div className="margin-banner" role="alert">
      <strong>{h === "call" ? "Margin call: positions are being liquidated." : "Margin warning:"}</strong>{" "}
      Equity {money(p.equity)} vs. maintenance {money(p.maintenanceRequirement)}. <Link to="/portfolio">Review positions</Link>
    </div>
  );
}
