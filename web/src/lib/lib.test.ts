import { describe, expect, it } from "vitest";
import type { Candle } from "../api/types";
import { aggregate, withLive } from "./candles";
import { direction, duration, money, signedPct, signedPrice } from "./format";

const c = (time: string, o: number, h: number, l: number, cl: number, v: number): Candle => ({ time, open: o, high: h, low: l, close: cl, volume: v });

describe("candles", () => {
  it("aggregates 1m candles into 5m buckets", () => {
    const out = aggregate(
      [
        c("2026-09-29T14:30:00Z", 100, 105, 99, 104, 10),
        c("2026-09-29T14:31:00Z", 104, 110, 103, 108, 5),
        c("2026-09-29T14:35:00Z", 108, 109, 90, 95, 7),
      ],
      300,
    );
    expect(out).toEqual([
      c("2026-09-29T14:30:00.000Z", 100, 110, 99, 108, 15),
      c("2026-09-29T14:35:00.000Z", 108, 109, 90, 95, 7),
    ]);
  });

  it("appends or replaces the live candle", () => {
    const a = c("2026-09-29T14:30:00Z", 1, 1, 1, 1, 1);
    const live = c("2026-09-29T14:30:00Z", 1, 3, 1, 2, 9);
    expect(withLive([a], live)).toEqual([live]);
    const next = { ...live, time: "2026-09-29T14:31:00Z" };
    expect(withLive([a], next)).toEqual([a, next]);
    expect(withLive([a], null)).toEqual([a]);
  });
});

describe("format", () => {
  it("formats cents and signed changes", () => {
    expect(money(123456)).toBe("$1,234.56");
    expect(signedPrice(-150)).toBe("−1.50");
    expect(signedPrice(5)).toBe("+0.05");
    expect(signedPct(1.234)).toBe("+1.23%");
    expect(direction(0)).toBe("flat");
    expect(duration(3_900_000)).toBe("1h 05m");
    expect(duration(61_000)).toBe("1m 01s");
  });
});
