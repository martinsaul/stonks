import { describe, expect, it } from "vitest";
import type { Portfolio } from "../api/types";
import { estimateCommission, marginHealth, nextPlan } from "./portfolio";

describe("commission estimate (mirrors server Plan.commission)", () => {
  it("matches the server's worked examples", () => {
    expect(estimateCommission("ROOKIE", 100, 5000)).toBe(495);
    expect(estimateCommission("TRADER", 10, 5000)).toBe(100);
    expect(estimateCommission("TRADER", 1000, 5000)).toBe(1000);
    expect(estimateCommission("TRADER", 1, 50)).toBe(1);
    expect(estimateCommission("PRO", 1000, 5000)).toBe(500);
    expect(estimateCommission("WHALE", 10_000, 50_00)).toBe(35_00 + 250_00);
  });
});

describe("margin health", () => {
  const base = { equity: 5_000_00, longValue: 10_000_00, shortValue: 0, maintenanceRequirement: 3_000_00 } as Portfolio;
  it("classifies by distance to maintenance", () => {
    expect(marginHealth({ ...base, longValue: 0, maintenanceRequirement: 0 })).toBe("none");
    expect(marginHealth(base)).toBe("ok");
    expect(marginHealth({ ...base, equity: 3_400_00 })).toBe("warning");
    expect(marginHealth({ ...base, equity: 2_900_00 })).toBe("call");
  });
  it("knows the next plan", () => {
    expect(nextPlan("ROOKIE")).toEqual(["TRADER", 25_000_00]);
    expect(nextPlan("WHALE")).toBeUndefined();
  });
});
