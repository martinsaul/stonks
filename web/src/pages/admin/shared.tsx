import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../../api/client";
import type { AdminAction } from "../../api/admin";

export type Status = { kind: "ok" | "error"; text: string } | undefined;

export function StatusLine({ status }: { status: Status }) {
  if (!status) return null;
  return <p className={status.kind === "ok" ? "form-ok" : "form-error"} role="status">{status.text}</p>;
}

/** Sends game-master actions; [onDone] runs after a success (e.g. reload the overview). */
export function useAdminAction(onDone?: () => void) {
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<Status>();
  const run = async (action: AdminAction, done = "Done.") => {
    setBusy(true);
    setStatus(undefined);
    try {
      await api.post("/api/v1/admin/actions", action);
      setStatus({ kind: "ok", text: done });
      onDone?.();
    } catch (e) {
      setStatus({ kind: "error", text: (e as ApiError).message });
    } finally {
      setBusy(false);
    }
  };
  return { busy, status, setStatus, run };
}

/** Loads [path] (and reloads on demand). */
export function useAdminGet<T>(path: string | null) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const load = useCallback(() => {
    if (!path) return;
    api.get<T>(path).then((d) => { setData(d); setError(undefined); }, (e: ApiError) => setError(e.message));
  }, [path]);
  useEffect(load, [load]);
  return { data, error, reload: load };
}

/** "When": now, or a local date-time sent as ISO (the server maps it to a game tick). */
export function When({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <label className="admin-field">
      <span>When</span>
      <input type="datetime-local" value={value} onChange={(e) => onChange(e.target.value)} />
      <span className="hint">Empty = now (or the next open while closed)</span>
    </label>
  );
}

export const atIso = (local: string) => (local ? new Date(local).toISOString() : undefined);

export function pretty(name: string) {
  return name.toLowerCase().replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
}
