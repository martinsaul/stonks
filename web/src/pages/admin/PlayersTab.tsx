import { useState } from "react";
import { api, ApiError } from "../../api/client";
import type { PlayerDetail, PlayerRow, ReviewRow } from "../../api/admin";
import { dateET, money, price, timeET } from "../../lib/format";
import { StatusLine, useAdminAction, useAdminGet, type Status } from "./shared";

function Detail({ id, onChanged }: { id: number; onChanged: () => void }) {
  const { data: d, error, reload } = useAdminGet<PlayerDetail>(`/api/v1/admin/players/${id}`);
  const { busy, status, setStatus, run } = useAdminAction(reload);
  const [cash, setCash] = useState({ amount: "", reason: "" });
  const [banReason, setBanReason] = useState("");
  const post = async (path: string, body: unknown | undefined, done: string) => {
    setStatus(undefined);
    try {
      await api.post(path, body);
      setStatus({ kind: "ok", text: done });
      reload();
      onChanged();
    } catch (e) {
      setStatus({ kind: "error", text: (e as ApiError).message });
    }
  };
  if (error) return <div className="error-banner">{error}</div>;
  if (!d) return <div className="empty">Loading…</div>;
  const p = d.player;
  const st = d.portfolio?.standing;
  return (
    <div className="stack">
      <section className="card">
        <div className="card-head"><h2>{p.name}</h2>{p.bannedAt && <span className="pill">Banned</span>}</div>
        <div className="card-body">
          <dl className="stats">
            <div><dt>Account</dt><dd>#{p.id} · {p.email}</dd></div>
            <div><dt>Joined</dt><dd>{dateET(p.createdAt)}</dd></div>
            <div><dt>Net worth</dt><dd>{st ? money(st.netWorth) : "—"}</dd></div>
            <div><dt>Cash</dt><dd>{d.portfolio ? money(d.portfolio.cash) : "—"}</dd></div>
            <div><dt>Plan / level</dt><dd>{d.portfolio?.plan ?? "—"} / {st?.cashLevel ?? 0}</dd></div>
            <div><dt>Shame / bankruptcies</dt><dd>{st?.shame ?? 0} / {st?.bankruptcies ?? 0}</dd></div>
            <div><dt>Millionaire review</dt><dd>{d.review ?? "—"}</dd></div>
          </dl>
          <h3 className="subhead">Flags</h3>
          {d.flags.length === 0 ? <p className="hint">None.</p> : <ul className="flags">{d.flags.map((f, i) => <li key={i}><strong>{f.kind}</strong> {f.detail}{f.accountId ? ` (#${f.accountId})` : ""}</li>)}</ul>}

          <h3 className="subhead">Adjust cash</h3>
          <div className="admin-row">
            <input aria-label="Amount ($, negative to debit)" placeholder="Amount $ (±)" inputMode="decimal" value={cash.amount} onChange={(e) => setCash({ ...cash, amount: e.target.value })} />
            <input aria-label="Reason" placeholder="Reason (shown to the player)" className="grow" value={cash.reason} onChange={(e) => setCash({ ...cash, reason: e.target.value })} />
            <button className="btn btn-inline" disabled={busy || !cash.amount || !cash.reason.trim()} onClick={() => run({ type: "adjust_cash", accountId: p.id, amount: Math.round(Number(cash.amount) * 100), reason: cash.reason }, "Cash adjusted.")}>Apply</button>
          </div>

          <h3 className="subhead">Ban</h3>
          {p.bannedAt ? (
            <button className="btn btn-inline" onClick={() => post(`/api/v1/admin/players/${p.id}/ban`, { banned: false }, "Unbanned.")}>Unban</button>
          ) : (
            <div className="admin-row">
              <input aria-label="Ban reason" placeholder="Reason" className="grow" value={banReason} onChange={(e) => setBanReason(e.target.value)} />
              <button className="btn btn-inline btn-sell" disabled={!banReason.trim()} onClick={() => post(`/api/v1/admin/players/${p.id}/ban`, { banned: true, reason: banReason }, "Banned: sessions revoked.")}>Ban</button>
            </div>
          )}
          <StatusLine status={status} />
        </div>
      </section>

      <section className="card">
        <div className="card-head"><h2>Fills</h2></div>
        <div className="card-body table-wrap">
          {d.fills.length === 0 ? <div className="empty">No fills.</div> : (
            <table className="data">
              <thead><tr><th>When</th><th>Trade</th><th>Price</th><th>Realized</th><th>Counterparty</th><th /></tr></thead>
              <tbody>
                {d.fills.map((f) => (
                  <tr key={f.id} className={f.voided ? "muted" : undefined}>
                    <td>{dateET(f.at)} {timeET(f.at)}</td>
                    <td>{f.side} {f.quantity} {f.ticker}{f.liquidation ? " (liq.)" : ""}</td>
                    <td className="num">{price(f.price)}</td>
                    <td className="num">{money(f.realized)}</td>
                    <td>{f.counterparty ? `#${f.counterparty}` : "—"}</td>
                    <td>{f.voided ? "Voided" : <button className="btn-link" onClick={() => post(`/api/v1/admin/fills/${encodeURIComponent(f.id)}/void`, undefined, "Fill voided.")}>Void</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      <section className="card">
        <div className="card-head"><h2>Sessions & economy</h2></div>
        <div className="card-body table-wrap">
          <table className="data">
            <thead><tr><th>IP</th><th>Device</th><th>Last seen</th></tr></thead>
            <tbody>{d.sessions.map((s, i) => <tr key={i} className={s.revoked ? "muted" : undefined}><td>{s.ip ?? "—"}</td><td>{(s.userAgent ?? "").slice(0, 60)}</td><td>{dateET(s.lastSeenAt)}</td></tr>)}</tbody>
          </table>
          {d.economy.length > 0 && (
            <table className="data">
              <thead><tr><th>When</th><th>Event</th><th>Amount</th></tr></thead>
              <tbody>{d.economy.map((e, i) => <tr key={i}><td>{dateET(e.at)}</td><td>{e.kind}: {e.detail}</td><td className="num">{money(e.amount)}</td></tr>)}</tbody>
            </table>
          )}
        </div>
      </section>
    </div>
  );
}

export function PlayersTab() {
  const [q, setQ] = useState("");
  const [rows, setRows] = useState<PlayerRow[]>();
  const [sel, setSel] = useState<number>();
  const [status, setStatus] = useState<Status>();
  const search = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!q.trim()) return;
    try {
      setRows(await api.get<PlayerRow[]>(`/api/v1/admin/players?q=${encodeURIComponent(q.trim())}`));
      setStatus(undefined);
    } catch (err) {
      setStatus({ kind: "error", text: (err as ApiError).message });
    }
  };
  return (
    <div className="grid-2 admin-companies">
      <section className="card">
        <div className="card-head"><h2>Players</h2></div>
        <div className="card-body">
          <form className="inline-form" onSubmit={search}>
            <input aria-label="Search players" placeholder="Name, email or id" value={q} onChange={(e) => setQ(e.target.value)} />
            <button className="btn btn-inline">Search</button>
          </form>
          <StatusLine status={status} />
          {rows && (rows.length === 0 ? <div className="empty">No match.</div> : (
            <table className="data">
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className={r.id === sel ? "you-row" : undefined}>
                    <td><button className="btn-link" onClick={() => setSel(r.id)}>{r.name}</button>{r.bannedAt && <span className="pill">banned</span>}</td>
                    <td className="muted">{r.email}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
        </div>
      </section>
      <aside>{sel != null && <Detail key={sel} id={sel} onChanged={() => void search()} />}</aside>
    </div>
  );
}

export function ReviewsTab() {
  const { data, error, reload } = useAdminGet<ReviewRow[]>("/api/v1/admin/reviews");
  const [status, setStatus] = useState<Status>();
  const decide = async (id: number, s: string) => {
    try {
      await api.post(`/api/v1/admin/reviews/${id}`, { status: s });
      setStatus({ kind: "ok", text: `Marked ${s.toLowerCase()}.` });
      reload();
    } catch (e) {
      setStatus({ kind: "error", text: (e as ApiError).message });
    }
  };
  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading…</div>;
  return (
    <section className="card">
      <div className="card-head"><h2>Millionaire reviews</h2></div>
      <div className="card-body">
        <p className="hint">Check each player's flags and fills (Players tab) before approving. Approved players appear on the Millionaires leaderboard.</p>
        <StatusLine status={status} />
        {data.length === 0 ? <div className="empty">Nobody has reached $1M yet.</div> : (
          <table className="data">
            <thead><tr><th>Player</th><th>Qualified</th><th>Status</th><th /></tr></thead>
            <tbody>
              {data.map((r) => (
                <tr key={r.accountId}>
                  <td>{r.name} <span className="muted">#{r.accountId}</span></td>
                  <td>{dateET(r.firstQualifiedAt)}</td>
                  <td>{r.status}{r.reviewer ? ` (${r.reviewer})` : ""}</td>
                  <td>
                    {r.status !== "APPROVED" && <button className="btn-link" onClick={() => decide(r.accountId, "APPROVED")}>Approve</button>}{" "}
                    {r.status !== "REJECTED" && <button className="btn-link" onClick={() => decide(r.accountId, "REJECTED")}>Reject</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
