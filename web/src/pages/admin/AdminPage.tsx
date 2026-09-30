import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "../../api/client";
import type { AdminOverview } from "../../api/admin";
import { CompaniesTab, ScheduledTab } from "./CompaniesTab";
import { AuditTab, DangerTab, EconomyTab } from "./EconomyTab";
import { MarketTab } from "./MarketTab";
import { PlayersTab, ReviewsTab } from "./PlayersTab";
import { useAdminGet } from "./shared";

const TABS = [
  ["market", "Market"], ["companies", "Companies"], ["scheduled", "Scheduled"], ["players", "Players"],
  ["reviews", "Reviews"], ["economy", "Economy"], ["audit", "Audit"], ["danger", "Rollback"],
] as const;

function KeyGate({ onKey }: { onKey: () => void }) {
  const [key, setKey] = useState("");
  return (
    <section className="card" style={{ maxWidth: 480 }}>
      <div className="card-head"><h2>Admin key</h2></div>
      <div className="card-body">
        <form onSubmit={(e) => { e.preventDefault(); api.setAdminKey(key.trim()); onKey(); }}>
          <div className="field">
            <label htmlFor="adminKey">Enter the server's admin key (STONKS_ADMIN_KEY)</label>
            <input id="adminKey" type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} />
          </div>
          <button className="btn" disabled={key.trim().length < 16}>Unlock</button>
          <p className="hint">Kept for this browser tab only.</p>
        </form>
      </div>
    </section>
  );
}

export function AdminPage() {
  const [unlocked, setUnlocked] = useState(!!api.adminKey);
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "market";
  const { data: o, error, reload } = useAdminGet<AdminOverview>(unlocked ? "/api/v1/admin/overview" : null);

  if (!unlocked) return <><h1 className="page-title">Admin</h1><KeyGate onKey={() => setUnlocked(true)} /></>;
  if (error) {
    return (
      <>
        <h1 className="page-title">Admin</h1>
        <div className="error-banner">{error}</div>
        <button className="btn btn-inline" onClick={() => { api.setAdminKey(null); setUnlocked(false); }}>Enter a different key</button>
      </>
    );
  }
  return (
    <>
      <h1 className="page-title">Admin</h1>
      <div className="tabs admin-tabs" role="group" aria-label="Admin section">
        {TABS.map(([k, label]) => <button key={k} aria-pressed={tab === k} onClick={() => setParams({ tab: k })}>{label}</button>)}
      </div>
      {!o ? <div className="empty">Loading…</div> : (
        <>
          {tab === "market" && <MarketTab o={o} reload={reload} />}
          {tab === "companies" && <CompaniesTab o={o} reload={reload} />}
          {tab === "scheduled" && <ScheduledTab o={o} />}
        </>
      )}
      {tab === "players" && <PlayersTab />}
      {tab === "reviews" && <ReviewsTab />}
      {tab === "economy" && <EconomyTab />}
      {tab === "audit" && <AuditTab />}
      {tab === "danger" && <DangerTab />}
    </>
  );
}
