import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { PlayerProfile } from "../api/types";
import { dateET, money, signedPct } from "../lib/format";
import { seasonLabel } from "./Leaderboards";

export function PlayerPage() {
  const name = useParams().name ?? "";
  const [p, setP] = useState<PlayerProfile>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    setP(undefined);
    setError(undefined);
    api.get<PlayerProfile>(`/api/v1/players/${encodeURIComponent(name)}`).then(setP, (e: ApiError) => setError(e.message));
  }, [name]);
  if (error) return <div className="error-banner">{error} <Link to="/leaderboards">Leaderboards</Link></div>;
  if (!p) return <div className="empty">Loading…</div>;
  return (
    <>
      <h1 className="page-title">{p.name}</h1>
      <div className="grid-2">
        <div className="stack">
          <section className="card" aria-labelledby="badges">
            <div className="card-head"><h2 id="badges">Badges</h2></div>
            <div className="card-body">
              {p.badges.length === 0 ? <div className="empty">No badges yet.</div> : (
                <div className="badges">
                  {p.badges.map((b) => (
                    <div className="badge" key={b.badge}>
                      <strong>{b.title}{b.count > 1 ? ` × ${b.count}` : ""}</strong>
                      <span className="hint">{b.description}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>
          <section className="card" aria-labelledby="seasons">
            <div className="card-head"><h2 id="seasons">Past seasons</h2></div>
            <div className="card-body">
              {p.seasons.length === 0 ? <div className="empty">No finished seasons yet.</div> : (
                <table className="data">
                  <thead><tr><th>Season</th><th>Rank</th><th>Return</th></tr></thead>
                  <tbody>
                    {p.seasons.map((s) => (
                      <tr key={s.season}>
                        <td><Link to={`/leaderboards?season=${s.season}`}>{seasonLabel(s.season)}</Link></td>
                        <td>{s.rank ?? "—"}</td>
                        <td className={(s.returnPct ?? 0) >= 0 ? "up" : "down"}>{signedPct(s.returnPct)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </section>
        </div>
        <aside className="stack">
          <section className="card" aria-labelledby="stats">
            <div className="card-head"><h2 id="stats">Stats</h2></div>
            <div className="card-body">
              <dl className="stats">
                <div><dt>Net worth</dt><dd>{p.netWorth != null ? money(p.netWorth) : "—"}</dd></div>
                <div><dt>Plan</dt><dd>{p.plan ? p.plan.charAt(0) + p.plan.slice(1).toLowerCase() : "—"}</dd></div>
                <div><dt>Starting-cash level</dt><dd>{p.cashLevel}</dd></div>
                <div><dt>Badges of shame</dt><dd>{p.eternalShame ? `${p.shame} · Eternal Shame` : p.shame}</dd></div>
                <div><dt>Bankruptcies</dt><dd>{p.bankruptcies}</dd></div>
                <div><dt>Playing since</dt><dd>{dateET(p.joined)}</dd></div>
              </dl>
            </div>
          </section>
        </aside>
      </div>
    </>
  );
}
