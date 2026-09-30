import { ColorType, createChart, LineSeries, type IChartApi, type UTCTimestamp } from "lightweight-charts";
import { useEffect, useRef, useState } from "react";
import type { AuditEntry, EconomyDashboard, SnapshotRow, StatRow } from "../../api/admin";
import { api, ApiError } from "../../api/client";
import { compact, dateET, money, timeET } from "../../lib/format";
import { cssVar, useResolvedTheme } from "../../lib/theme";
import { StatusLine, useAdminGet, type Status } from "./shared";

/** One measure over game days: a single series, its own axis, crosshair readout. */
function Trend({ title, rows, value, format }: { title: string; rows: StatRow[]; value: (r: StatRow) => number; format: (v: number) => string }) {
  const el = useRef<HTMLDivElement>(null);
  const theme = useResolvedTheme();
  const [hover, setHover] = useState<StatRow>();
  useEffect(() => {
    if (!el.current || rows.length === 0) return;
    const chart: IChartApi = createChart(el.current, {
      autoSize: true,
      height: 180,
      layout: { background: { type: ColorType.Solid, color: "transparent" }, textColor: cssVar("--text-2"), attributionLogo: false },
      grid: { vertLines: { visible: false }, horzLines: { color: cssVar("--grid") } },
      rightPriceScale: { borderVisible: false },
      timeScale: { borderVisible: false },
      handleScroll: false,
      handleScale: false,
    });
    const series = chart.addSeries(LineSeries, {
      color: cssVar("--brand"), lineWidth: 2, priceLineVisible: false, lastValueVisible: false,
      priceFormat: { type: "custom", formatter: format },
    });
    const byTime = new Map<number, StatRow>();
    series.setData(rows.map((r) => {
      const t = Math.floor(Date.parse(r.at) / 1000) as UTCTimestamp;
      byTime.set(t, r);
      return { time: t, value: value(r) };
    }));
    chart.timeScale().fitContent();
    chart.subscribeCrosshairMove((p) => setHover(p.time ? byTime.get(p.time as number) : undefined));
    return () => chart.remove();
  }, [rows, theme, value, format]);
  const shown = hover ?? rows[rows.length - 1];
  return (
    <figure className="trend">
      <figcaption>
        <strong>{title}</strong>
        {shown && <span className="muted"> {format(value(shown))} · day {shown.day}, {dateET(shown.at)}</span>}
      </figcaption>
      <div ref={el} />
    </figure>
  );
}

const worth = (r: StatRow) => r.totalWorth / 100;
const players = (r: StatRow) => r.players;
const debt = (r: StatRow) => r.totalDebt / 100;
const usd = (v: number) => "$" + compact(v);
const count = (v: number) => String(Math.round(v));

export function EconomyTab() {
  const { data, error } = useAdminGet<EconomyDashboard>("/api/v1/admin/economy");
  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading…</div>;
  const rows = data.stats;
  const last = rows[rows.length - 1];
  return (
    <div className="stack">
      <div className="tiles">
        <div className="tile"><span>Players</span><strong>{last ? last.players : 0}</strong></div>
        <div className="tile"><span>Total net worth</span><strong>{last ? money(last.totalWorth) : "—"}</strong></div>
        <div className="tile"><span>Median net worth</span><strong>{last ? money(last.medianWorth) : "—"}</strong></div>
        <div className="tile"><span>Margin debt</span><strong>{last ? money(last.totalDebt) : "—"}</strong></div>
        <div className="tile"><span>In debt</span><strong>{last ? last.inDebt : 0}</strong></div>
        <div className="tile"><span>Millionaires</span><strong>{last ? last.millionaires : 0}</strong><span className="hint">{data.pendingReviews} awaiting review</span></div>
      </div>
      {rows.length < 2 ? <div className="empty">Charts appear after a couple of sessions.</div> : (
        <section className="card">
          <div className="card-head"><h2>After each session</h2></div>
          <div className="card-body trends">
            <Trend title="Total player net worth" rows={rows} value={worth} format={usd} />
            <Trend title="Players" rows={rows} value={players} format={count} />
            <Trend title="Total margin debt" rows={rows} value={debt} format={usd} />
          </div>
        </section>
      )}
      <details className="card">
        <summary className="card-head"><h2>Table</h2></summary>
        <div className="card-body table-wrap">
          <table className="data">
            <thead><tr><th>Day</th><th>Date</th><th>Players</th><th>Total worth</th><th>Median</th><th>Debt</th><th>In debt</th><th>Millionaires</th></tr></thead>
            <tbody>
              {[...rows].reverse().map((r) => (
                <tr key={r.day}><td>{r.day}</td><td>{dateET(r.at)}</td><td>{r.players}</td><td className="num">{money(r.totalWorth)}</td>
                  <td className="num">{money(r.medianWorth)}</td><td className="num">{money(r.totalDebt)}</td><td>{r.inDebt}</td><td>{r.millionaires}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}

export function AuditTab() {
  const { data, error } = useAdminGet<AuditEntry[]>("/api/v1/admin/audit");
  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading…</div>;
  return (
    <section className="card">
      <div className="card-head"><h2>Audit log</h2></div>
      <div className="card-body table-wrap">
        {data.length === 0 ? <div className="empty">No admin actions yet.</div> : (
          <table className="data">
            <thead><tr><th>When</th><th>Admin</th><th>Action</th><th>Target</th><th>Result</th><th>Details</th></tr></thead>
            <tbody>
              {data.map((a) => (
                <tr key={a.id}>
                  <td>{dateET(a.at)} {timeET(a.at)}</td><td>{a.admin}</td><td>{a.action}</td><td>{a.target ?? "—"}</td><td>{a.result}</td>
                  <td className="muted"><code>{a.payload.slice(0, 120)}</code></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}

export function DangerTab() {
  const { data, error } = useAdminGet<SnapshotRow[]>("/api/v1/admin/snapshots");
  const [sel, setSel] = useState<number>();
  const [typed, setTyped] = useState("");
  const [status, setStatus] = useState<Status>();
  if (error) return <div className="error-banner">{error}</div>;
  if (!data) return <div className="empty">Loading…</div>;
  const rollback = async () => {
    try {
      await api.post("/api/v1/admin/rollback", { snapshotId: sel, confirm: typed });
      setStatus({ kind: "ok", text: "Rollback scheduled. The server is restarting; reload in a minute." });
    } catch (e) {
      setStatus({ kind: "error", text: (e as ApiError).message });
    }
  };
  return (
    <section className="card danger">
      <div className="card-head"><h2>World rollback</h2></div>
      <div className="card-body">
        <p className="hint">
          Restores the world to the end of a past session and restarts the server. Every player action after that point
          (orders, fills, claims, resets, admin actions) is discarded and the market re-simulates from there. For exploits only.
        </p>
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th /><th>Snapshot</th><th>After session</th><th>Market closed at</th></tr></thead>
            <tbody>
              {data.map((s) => (
                <tr key={s.id}>
                  <td><input type="radio" name="snap" aria-label={`Snapshot ${s.id}`} checked={sel === s.id} onChange={() => setSel(s.id)} /></td>
                  <td>#{s.id}</td><td>{s.sessionsCompleted}</td><td>{s.lastClose ? `${dateET(s.lastClose)} ${timeET(s.lastClose)}` : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="admin-row">
          <input aria-label="Type ROLLBACK to confirm" placeholder="Type ROLLBACK" value={typed} onChange={(e) => setTyped(e.target.value)} />
          <button className="btn btn-inline btn-sell" disabled={sel == null || typed !== "ROLLBACK"} onClick={rollback}>Roll back and restart</button>
        </div>
        <StatusLine status={status} />
      </div>
    </section>
  );
}
