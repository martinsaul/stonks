import { useSyncExternalStore } from "react";

const KEY = "stonks.watchlist";
const listeners = new Set<() => void>();

function read(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? "[]");
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

let list = read();

export function toggleWatch(ticker: string) {
  list = list.includes(ticker) ? list.filter((t) => t !== ticker) : [...list, ticker];
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* ignore */
  }
  listeners.forEach((l) => l());
}

export function useWatchlist(): string[] {
  return useSyncExternalStore(
    (l) => (listeners.add(l), () => listeners.delete(l)),
    () => list,
  );
}
