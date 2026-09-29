import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { MeResponse } from "../api/types";
import { dateET } from "../lib/format";

export function Account() {
  const [me, setMe] = useState<MeResponse>();
  useEffect(() => { api.get<MeResponse>("/api/v1/me").then(setMe, () => undefined); }, []);
  if (!me) return <div className="empty">Loading…</div>;
  return (
    <>
      <h1 className="page-title">Account</h1>
      <div className="stack" style={{ maxWidth: 720 }}>
        <section className="card">
          <div className="card-body">
            <dl className="stats">
              <div><dt>Email</dt><dd>{me.email}</dd></div>
              <div><dt>Member since</dt><dd>{dateET(me.createdAt)}</dd></div>
            </dl>
          </div>
        </section>
        <section className="card" aria-labelledby="badges">
          <div className="card-head"><h2 id="badges">Badges</h2></div>
          <div className="card-body">
            {me.badges.length === 0 ? (
              <div className="empty">No badges yet.</div>
            ) : (
              <div className="badges">
                {me.badges.map((b) => (
                  <div className="badge" key={b.badge}>
                    <strong>{b.title}{b.count > 1 ? ` × ${b.count}` : ""}</strong>
                    <span className="hint">{b.description}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>
      </div>
    </>
  );
}
