import { useMemo, useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { useFeedStatus } from "../api/feed";
import { duration, price, REGIMES } from "../lib/format";
import { useMarket, useNow } from "../lib/market";
import { setTheme, useThemeChoice, type ThemeChoice } from "../lib/theme";
import { Change } from "./Change";

export function Logo() {
  return (
    <svg className="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="7" fill="var(--brand)" />
      <path d="M6 22l6-6 5 4 9-10" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function Search() {
  const { market } = useMarket();
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const results = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s || !market) return [];
    return market.quotes
      .filter((x) => x.ticker.toLowerCase().startsWith(s) || x.name.toLowerCase().includes(s))
      .slice(0, 8);
  }, [q, market]);

  const go = (ticker: string) => {
    setQ("");
    navigate(`/quote/${ticker}`);
  };

  return (
    <div className="search" role="combobox" aria-expanded={results.length > 0} aria-haspopup="listbox">
      <input
        type="search"
        placeholder="Search for a company or ticker"
        aria-label="Search for a company or ticker"
        value={q}
        onChange={(e) => { setQ(e.target.value); setSel(0); }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") { setSel((s) => Math.min(s + 1, results.length - 1)); e.preventDefault(); }
          if (e.key === "ArrowUp") { setSel((s) => Math.max(s - 1, 0)); e.preventDefault(); }
          if (e.key === "Enter" && results[sel]) go(results[sel].ticker);
          if (e.key === "Escape") setQ("");
        }}
      />
      {results.length > 0 && (
        <div className="search-results" role="listbox">
          {results.map((r, i) => (
            <button key={r.ticker} role="option" aria-selected={i === sel} onMouseDown={() => go(r.ticker)}>
              <strong style={{ width: 52 }}>{r.ticker}</strong>
              <span className="muted" style={{ flex: 1 }}>{r.name}</span>
              <span className="num">{price(r.last)}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

const THEME_NEXT: Record<ThemeChoice, ThemeChoice> = { system: "light", light: "dark", dark: "system" };
const THEME_LABEL: Record<ThemeChoice, string> = { system: "Auto", light: "Light", dark: "Dark" };

export function Header({ email, onSignOut }: { email?: string; onSignOut: () => void }) {
  const theme = useThemeChoice();
  return (
    <header className="topbar">
      <div className="topbar-inner">
        <NavLink to="/" className="brand"><Logo /> stonks</NavLink>
        <nav className="nav" aria-label="Main">
          <NavLink to="/" end>Markets</NavLink>
          <NavLink to="/screener">Screener</NavLink>
        </nav>
        <Search />
        <div className="spacer" />
        <button className="icon-btn" onClick={() => setTheme(THEME_NEXT[theme])} title="Switch theme">
          {THEME_LABEL[theme]}<span className="wide-only"> theme</span>
        </button>
        <div className="account">
          <NavLink to="/account"><span className="wide-only">{email ?? "Account"}</span><span className="narrow-only">Account</span></NavLink>
          <button className="btn-link" onClick={onSignOut}>Sign out</button>
        </div>
      </div>
      <MarketStrip />
    </header>
  );
}

function MarketStrip() {
  const { market } = useMarket();
  const now = useNow();
  const status = useFeedStatus();
  if (!market) return <div className="strip"><div className="strip-inner muted">Loading market…</div></div>;
  const { index, session, regime } = market;
  const open = session.state === "OPEN";
  const until = open ? session.closesAt : session.nextOpen;
  return (
    <div className="strip">
      <div className="strip-inner">
        <span className="strip-item">
          <span className="muted">{index.name}</span>
          <strong className="num">{index.value.toLocaleString("en-US", { maximumFractionDigits: 2, minimumFractionDigits: 2 })}</strong>
          <Change pct={index.changePct} />
        </span>
        <span className="strip-item">
          <span className={`dot ${open ? "open" : ""}`} aria-hidden="true" />
          <strong>{open ? "Market open" : "Market closed"}</strong>
          {until && <span className="muted">{open ? "closes" : "opens"} in {duration(Date.parse(until) - now)}</span>}
          {session.kind === "WEEKEND" && <span className="pill">Weekend session</span>}
        </span>
        <span className="pill" title="Market phase">{REGIMES[regime] ?? regime}</span>
        {status !== "open" && <span className="muted">Live feed {status === "connecting" ? "connecting…" : "reconnecting…"}</span>}
      </div>
    </div>
  );
}
