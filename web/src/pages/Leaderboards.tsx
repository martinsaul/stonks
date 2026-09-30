import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { MillionairesResponse, SeasonResponse } from "../api/types";
import { money, signedPct } from "../lib/format";

/** Seasons are UTC calendar months: "2026-10" → "October 2026". */
export function seasonLabel(id: string): string {
  return new Date(`${id}-15T12:00:00Z`).toLocaleString("en-US", { month: "long", year: "numeric", timeZone: "UTC" });
}

function Player({ name }: { name: string }) {
  return <Link to={`/player/${encodeURIComponent(name)}`}>{name}</Link>;
}

function Millionaires() {
  const [data, setData] = useState<MillionairesResponse>();
  const [error, setError] = useState<string>();
  useEffect(() => { api.get<MillionairesResponse>("/api/v1/leaderboards/millionaires").then(setData, (e: ApiError) => setError(e.message)); }, []);
  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading…</div>;
  return (
    <>
      <p className="hint">
        Players worth over $1,000,000, listed after a manual review. Ranked by fewest outstanding badges of shame, then net worth.
        {data.pendingReviews > 0 && ` ${data.pendingReviews} awaiting review.`}
      </p>
      {data.yourStatus === "PENDING" && <div className="status-banner">You qualify! Your entry is waiting for review.</div>}
      {data.yourStatus === "REJECTED" && <div className="status-banner warn">Your entry was not approved.</div>}
      {data.entries.length === 0 ? <div className="empty">No millionaires yet. Be the first.</div> : (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>#</th><th>Player</th><th>Net worth</th><th>Badges of shame</th><th>Plan</th></tr></thead>
            <tbody>
              {data.entries.map((e) => (
                <tr key={e.rank}>
                  <td>{e.rank}</td>
                  <td><Player name={e.name} /></td>
                  <td className="num">{money(e.netWorth)}</td>
                  <td>{e.eternalShame ? "Eternal Shame" : e.shame}</td>
                  <td>{e.plan.charAt(0) + e.plan.slice(1).toLowerCase()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function Season({ id, onPick }: { id?: string; onPick: (s: string) => void }) {
  const [data, setData] = useState<SeasonResponse>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    setData(undefined);
    api.get<SeasonResponse>(`/api/v1/leaderboards/season${id ? `?season=${id}` : ""}`).then(setData, (e: ApiError) => setError(e.message));
  }, [id]);
  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading…</div>;
  const you = data.you;
  return (
    <>
      <div className="inline-form" style={{ justifyContent: "space-between", alignItems: "center" }}>
        <p className="hint" style={{ margin: 0 }}>
          {seasonLabel(data.season)}{data.finalized ? " · final" : " · live"}.
          Ranked by return since joining, net of weekly claims. Needs {data.minTrades} trades on {data.minActiveDays} trading days;
          a reset or bankruptcy disqualifies.
        </p>
        {data.seasons.length > 1 && (
          <select aria-label="Season" value={data.season} onChange={(e) => onPick(e.target.value)}>
            {data.seasons.map((s) => <option key={s} value={s}>{seasonLabel(s)}</option>)}
          </select>
        )}
      </div>
      {you && (
        <div className="status-banner">
          You: {you.rank ? `#${you.rank}` : "unranked"}
          {you.returnPct != null && <> · <span className={you.returnPct >= 0 ? "up" : "down"}>{signedPct(you.returnPct)}</span></>}
          {` · ${you.trades} trades · ${you.activeDays} active days`}
          {you.unranked && ` · ${you.unranked}`}
        </div>
      )}
      {data.entries.length === 0 ? <div className="empty">Nobody has qualified yet.</div> : (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>#</th><th>Player</th><th>Return</th><th>Net worth</th><th>Trades</th></tr></thead>
            <tbody>
              {data.entries.map((e) => (
                <tr key={e.rank} className={you && you.rank === e.rank ? "you-row" : undefined}>
                  <td>{e.rank}</td>
                  <td><Player name={e.name} /></td>
                  <td className={(e.returnPct ?? 0) >= 0 ? "up" : "down"}>{signedPct(e.returnPct)}</td>
                  <td className="num">{money(e.netWorth)}</td>
                  <td>{e.trades}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

export function LeaderboardsPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") === "millionaires" ? "millionaires" : "season";
  const season = params.get("season") ?? undefined;
  return (
    <>
      <h1 className="page-title">Leaderboards</h1>
      <section className="card">
        <div className="card-head">
          <div className="tabs" role="group" aria-label="Leaderboard">
            <button aria-pressed={tab === "season"} onClick={() => setParams({})}>This season</button>
            <button aria-pressed={tab === "millionaires"} onClick={() => setParams({ tab: "millionaires" })}>Millionaires</button>
          </div>
        </div>
        <div className="card-body">
          {tab === "season" ? <Season id={season} onPick={(s) => setParams({ season: s })} /> : <Millionaires />}
        </div>
      </section>
    </>
  );
}
