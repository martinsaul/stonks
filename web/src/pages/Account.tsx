import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { BondOffering, BondsResponse, EconomyResult, MeResponse, Standing } from "../api/types";
import { dateET, money, timeET } from "../lib/format";
import { useMarket, useNow } from "../lib/market";
import { usePortfolio } from "../lib/portfolio";

type Status = { kind: "ok" | "error"; text: string } | undefined;

/** Runs an economy action; the portfolio (and standing) updates over the feed. */
function useEconomy() {
  const [busy, setBusy] = useState<string>();
  const [status, setStatus] = useState<Status>();
  const run = async (action: string, done: string, body?: unknown) => {
    setBusy(action);
    setStatus(undefined);
    try {
      await api.post<EconomyResult>(`/api/v1/economy/${action}`, body);
      setStatus({ kind: "ok", text: done });
    } catch (e) {
      setStatus({ kind: "error", text: (e as ApiError).message });
    } finally {
      setBusy(undefined);
    }
  };
  return { busy, status, run };
}

function when(iso: string) {
  return `${dateET(iso)}, ${timeET(iso)} ET`;
}

function StatusLine({ status }: { status: Status }) {
  if (!status) return null;
  return <p className={status.kind === "ok" ? "form-ok" : "form-error"} role="status">{status.text}</p>;
}

function NameCard({ me, onRenamed }: { me: MeResponse; onRenamed: (n: string) => void }) {
  const [name, setName] = useState(me.displayName);
  const [status, setStatus] = useState<Status>();
  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await api.post("/api/v1/me/name", { displayName: name.trim() });
      onRenamed(name.trim());
      setStatus({ kind: "ok", text: "Saved." });
    } catch (err) {
      setStatus({ kind: "error", text: (err as ApiError).message });
    }
  };
  return (
    <section className="card" aria-labelledby="profile">
      <div className="card-head"><h2 id="profile">Profile</h2><Link to={`/player/${encodeURIComponent(me.displayName)}`}>Public profile</Link></div>
      <div className="card-body">
        <form onSubmit={save} className="inline-form">
          <div className="field">
            <label htmlFor="displayName">Display name</label>
            <input id="displayName" value={name} maxLength={20} onChange={(e) => setName(e.target.value)} />
          </div>
          <button className="btn btn-inline" disabled={name.trim() === me.displayName}>Save</button>
        </form>
        <p className="hint">Shown on leaderboards and your public profile. Your email is never shown.</p>
        <StatusLine status={status} />
        <dl className="stats">
          <div><dt>Email</dt><dd>{me.email}</dd></div>
          <div><dt>Member since</dt><dd>{dateET(me.createdAt)}</dd></div>
        </dl>
      </div>
    </section>
  );
}

function Money({ st, plan }: { st: Standing; plan: string }) {
  const { busy, status, run } = useEconomy();
  const now = useNow(10_000);
  const claimAt = st.nextClaimAt ? Date.parse(st.nextClaimAt) : 0;
  const canClaim = st.claimEligible && claimAt <= now;
  return (
    <section className="card" aria-labelledby="standing">
      <div className="card-head"><h2 id="standing">Your standing</h2></div>
      <div className="card-body">
        <dl className="stats">
          <div><dt>Net worth</dt><dd>{money(st.netWorth)}</dd></div>
          <div><dt>Plan</dt><dd>{plan.charAt(0) + plan.slice(1).toLowerCase()}</dd></div>
          <div><dt>Starting cash</dt><dd>{money(st.startingCash)} (level {st.cashLevel})</dd></div>
          <div><dt>Game over at</dt><dd>{money(st.gameOverAt)}</dd></div>
        </dl>

        <h3 className="subhead">Weekly claim</h3>
        <p className="hint">Players worth less than $1,000 (including those in debt) can claim $1,000 once a week.</p>
        <button className="btn btn-inline" disabled={!canClaim || busy !== undefined} onClick={() => run("claim", "Claimed $1,000.")}>
          Claim $1,000
        </button>
        {!st.claimEligible && <p className="hint">Available when your net worth is below $1,000.</p>}
        {st.claimEligible && claimAt > now && <p className="hint">Next claim: {when(st.nextClaimAt!)}.</p>}

        <h3 className="subhead">Starting-cash upgrade</h3>
        {st.nextUpgradeCost != null ? (
          <>
            <p className="hint">
              Level {st.cashLevel + 1} costs {money(st.nextUpgradeCost)} and raises the cash you start with after a reset or
              bankruptcy to {money(st.startingCash + 1_000_00)}. Upgrades survive resets; a bankruptcy removes one level.
            </p>
            <button className="btn btn-inline" disabled={busy !== undefined} onClick={() => run("upgrade", `Upgraded to level ${st.cashLevel + 1}.`)}>
              Buy level {st.cashLevel + 1}
            </button>
          </>
        ) : <p className="hint">Maximum level reached.</p>}
        <StatusLine status={status} />
      </div>
    </section>
  );
}

function Shame({ st }: { st: Standing }) {
  const { busy, status, run } = useEconomy();
  return (
    <section className="card" aria-labelledby="shame">
      <div className="card-head"><h2 id="shame">Badges of shame</h2></div>
      <div className="card-body">
        <dl className="stats">
          <div><dt>Outstanding</dt><dd>{st.shame}{st.eternalShame ? " · Eternal Shame" : ""}</dd></div>
          <div><dt>Ever received</dt><dd>{st.shameEver}</dd></div>
          <div><dt>Bankruptcies</dt><dd>{st.bankruptcies}</dd></div>
        </dl>
        {st.eternalShame ? (
          <p className="hint">At 30 badges, shame is eternal: they can no longer be cleared.</p>
        ) : st.clearCost != null ? (
          <>
            <p className="hint">Clearing badge #{st.shame} costs {money(st.clearCost)} from your cash. Millionaires are ranked by fewest outstanding badges.</p>
            <button className="btn btn-inline" disabled={busy !== undefined} onClick={() => run("clear-badge", "Badge cleared.")}>
              Clear one badge
            </button>
          </>
        ) : <p className="hint">None. Keep it that way.</p>}
        <StatusLine status={status} />
      </div>
    </section>
  );
}

function Bonds({ st }: { st: Standing }) {
  const [offerings, setOfferings] = useState<BondOffering[]>();
  const [amount, setAmount] = useState<Record<number, string>>({});
  const { busy, status, run } = useEconomy();
  const { market } = useMarket();
  const day = market?.session.opensAt ?? market?.session.nextOpen;
  useEffect(() => {
    api.get<BondsResponse>("/api/v1/bonds").then((r) => setOfferings(r.offerings), () => setOfferings([]));
  }, [day]);
  if (!offerings) return null;
  if (offerings.length === 0 && st.bonds.length === 0) return null;
  return (
    <section className="card" aria-labelledby="bonds">
      <div className="card-head"><h2 id="bonds">Bonds</h2></div>
      <div className="card-body">
        <p className="hint">Guaranteed return at maturity. Locked until then, not usable as margin, and lost on reset or bankruptcy.</p>
        {offerings.map((o) => {
          const held = st.bonds.filter((b) => b.offeringId === o.id).reduce((s, b) => s + b.principal, 0);
          const cents = Math.round(Number(amount[o.id] ?? "") * 100);
          return (
            <div key={o.id} className="bond">
              <div>
                <strong>{o.name}</strong> <span className="up">+{o.returnPct.toFixed(0)}%</span>
                <div className="hint">
                  Up to {money(o.capPerPlayer)} per player{held > 0 ? ` (you hold ${money(held)})` : ""}
                  {o.closesAt ? ` · subscriptions close ${dateET(o.closesAt)}` : ""}
                  {o.maturesAt ? ` · matures ${dateET(o.maturesAt)}` : ""}
                </div>
              </div>
              {o.open && (
                <form className="inline-form" onSubmit={(e) => {
                  e.preventDefault();
                  if (cents > 0) run("buy-bond", `Bought ${money(cents)} of ${o.name}.`, { offeringId: o.id, amount: cents });
                }}>
                  <input aria-label={`Amount for ${o.name}`} inputMode="decimal" placeholder="Amount ($)" value={amount[o.id] ?? ""}
                    onChange={(e) => setAmount({ ...amount, [o.id]: e.target.value })} />
                  <button className="btn btn-inline" disabled={busy !== undefined || !(cents > 0)}>Buy</button>
                </form>
              )}
            </div>
          );
        })}
        {st.bonds.length > 0 && (
          <table className="data">
            <thead><tr><th>Bond</th><th>Paid</th><th>Pays</th><th>Matures</th></tr></thead>
            <tbody>
              {st.bonds.map((b, i) => (
                <tr key={i}><td>{b.name}</td><td>{money(b.principal)}</td><td>{money(b.payout)}</td><td>{b.maturesAt ? dateET(b.maturesAt) : `Day ${b.maturityDay}`}</td></tr>
              ))}
            </tbody>
          </table>
        )}
        <StatusLine status={status} />
      </div>
    </section>
  );
}

function DangerZone({ st }: { st: Standing }) {
  const { busy, status, run } = useEconomy();
  const [confirmReset, setConfirmReset] = useState(false);
  const [typed, setTyped] = useState("");
  const now = useNow(10_000);
  const resetAt = st.nextResetAt ? Date.parse(st.nextResetAt) : 0;
  const debt = Math.max(0, -st.netWorth);
  const debtK = debt / 100_000;
  const days = Math.round(30 + 0.55 * debtK + 0.015 * debtK * debtK);
  return (
    <section className="card danger" aria-labelledby="danger">
      <div className="card-head"><h2 id="danger">Fresh start</h2></div>
      <div className="card-body">
        <h3 className="subhead">Monthly reset</h3>
        <p className="hint">
          Back to {money(st.startingCash)} on the Rookie plan. Positions, open orders and bonds are wiped{debt > 0 ? `, and so is your ${money(debt)} of debt` : ""}.
          Upgrades and badges stay. The next reset will be available in about {days} days{debt > 0 ? " (longer with debt, and longer again for repeat resets in debt)" : ""}.
          Resetting disqualifies you from this season's leaderboard.
        </p>
        {resetAt > now ? (
          <p className="hint">Next reset available {when(st.nextResetAt!)}.</p>
        ) : confirmReset ? (
          <div className="inline-form">
            <button className="btn btn-inline btn-sell" disabled={busy !== undefined} onClick={() => { setConfirmReset(false); run("reset", "Account reset."); }}>
              Yes, reset my account
            </button>
            <button className="btn-link" onClick={() => setConfirmReset(false)}>Keep playing</button>
          </div>
        ) : (
          <button className="btn btn-inline btn-outline" onClick={() => setConfirmReset(true)}>Reset account…</button>
        )}

        <h3 className="subhead">Declare bankruptcy</h3>
        <p className="hint">
          For negative net worth: an immediate fresh start with no cooldown, but you get a badge of shame and lose a starting-cash level.
          It happens automatically at {money(st.gameOverAt)}.
        </p>
        {st.netWorth < 0 ? (
          <form className="inline-form" onSubmit={(e) => { e.preventDefault(); setTyped(""); run("bankrupt", "Bankruptcy declared. Fresh start."); }}>
            <input aria-label="Type BANKRUPT to confirm" placeholder="Type BANKRUPT" value={typed} onChange={(e) => setTyped(e.target.value)} />
            <button className="btn btn-inline btn-sell" disabled={typed !== "BANKRUPT" || busy !== undefined}>Declare bankruptcy</button>
          </form>
        ) : <p className="hint">Not available: your net worth is positive.</p>}
        <StatusLine status={status} />
      </div>
    </section>
  );
}

export function Account() {
  const [me, setMe] = useState<MeResponse>();
  const portfolio = usePortfolio();
  useEffect(() => { api.get<MeResponse>("/api/v1/me").then(setMe, () => undefined); }, []);
  if (!me) return <div className="empty">Loading…</div>;
  const st = portfolio?.standing;
  return (
    <>
      <h1 className="page-title">Account</h1>
      <div className="grid-2">
        <div className="stack">
          {st && portfolio && <Money st={st} plan={portfolio.plan} />}
          {st && <Bonds st={st} />}
          {st && <DangerZone st={st} />}
        </div>
        <aside className="stack">
          <NameCard me={me} onRenamed={(n) => setMe({ ...me, displayName: n })} />
          {st && <Shame st={st} />}
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
        </aside>
      </div>
    </>
  );
}
