import { useSyncExternalStore } from "react";

export type ThemeChoice = "system" | "light" | "dark";
const KEY = "stonks.theme";
const listeners = new Set<() => void>();
const media = window.matchMedia("(prefers-color-scheme: dark)");

function stored(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

let choice = stored();

function apply() {
  if (choice === "system") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = choice;
  listeners.forEach((l) => l());
}

media.addEventListener("change", () => listeners.forEach((l) => l()));
apply();

export function setTheme(next: ThemeChoice) {
  choice = next;
  try {
    localStorage.setItem(KEY, next);
  } catch {
    /* private mode */
  }
  apply();
}

export function useThemeChoice(): ThemeChoice {
  return useSyncExternalStore(
    (l) => (listeners.add(l), () => listeners.delete(l)),
    () => choice,
  );
}

/** The theme actually shown. */
export function useResolvedTheme(): "light" | "dark" {
  const c = useThemeChoice();
  const systemDark = useSyncExternalStore(
    (l) => (listeners.add(l), () => listeners.delete(l)),
    () => media.matches,
  );
  return c === "system" ? (systemDark ? "dark" : "light") : c;
}

/** Reads a CSS custom property from the root element (for canvas-drawn charts). */
export function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
