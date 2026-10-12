import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { compact, spendingReport } from "../src/lib/spending";

const ms = (y: number, mo: number, d: number, h = 12) => new Date(y, mo - 1, d, h).getTime();
let n = 0;
const spend = (amount: number, y: number, mo: number, d: number, cat: string | null = "Ăn uống", title = `Chi ${n}`, status: Item["status"] = "OPEN"): Item => ({
  id: `e${n++}`, type: "EXPENSE", status, title, details: "", whenAt: ms(y, mo, d), allDay: true, amount, ...(cat ? { category: cat } : {}), createdAt: 0,
});
const oct = "2026-10";
const now = ms(2026, 10, 10); // day 10 of 31

describe("spendingReport", () => {
  it("totals, cumulative and forecast follow the current pace", () => {
    const r = spendingReport([spend(100_000, 2026, 10, 2), spend(200_000, 2026, 10, 10)], oct, now);
    expect(r.total).toBe(300_000);
    expect(r.cumulative).toHaveLength(10);
    expect(r.cumulative[1]).toBe(100_000);
    expect(r.cumulative.at(-1)).toBe(300_000);
    expect(r.forecast).toBe(930_000); // 300k over 10 days → 930k over 31
  });

  it("no forecast too early or for a finished month", () => {
    expect(spendingReport([spend(100_000, 2026, 10, 2)], oct, ms(2026, 10, 3)).forecast).toBeNull();
    expect(spendingReport([spend(100_000, 2026, 9, 2)], "2026-09", now).forecast).toBeNull();
  });

  it("ignores drafts and invalid amounts", () => {
    const items = [spend(100_000, 2026, 10, 2, "Ăn uống", "a", "DRAFT"), spend(0, 2026, 10, 3), spend(50_000, 2026, 10, 4)];
    expect(spendingReport(items, oct, now).total).toBe(50_000);
  });

  it("compares with last month up to the same day", () => {
    const r = spendingReport([spend(100_000, 2026, 9, 5), spend(400_000, 2026, 9, 25), spend(50_000, 2026, 10, 5)], oct, now);
    expect(r.previousToDate).toBe(100_000);
    expect(r.previousCumulative).toHaveLength(30);
  });

  it("categories are the top five then Khác", () => {
    const cats = ["A", "B", "C", "D", "E", "F", "G"];
    const items = [...cats.map((c, i) => spend((100 - i * 10) * 1000, 2026, 10, 2, c)), spend(5_000, 2026, 10, 3, null)];
    const r = spendingReport(items, oct, now);
    expect(r.categories.map((c) => c.name)).toEqual(["A", "B", "C", "D", "E", "Khác"]);
    expect(r.categories.at(-1)!.amount).toBe((50 + 40 + 5) * 1000);
    expect(r.categories.reduce((s, c) => s + c.share, 0)).toBeCloseTo(1, 9);
  });

  it("the trend has six months, oldest first", () => {
    const r = spendingReport([spend(100_000, 2026, 5, 2), spend(10_000, 2026, 10, 2)], oct, now);
    expect(r.months.map((m) => m.key)).toEqual(["2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10"]);
    expect([r.months[0].total, r.months[5].total]).toEqual([100_000, 10_000]);
  });

  it("flags a huge expense against the usual ones", () => {
    const usual = Array.from({ length: 10 }, (_, i) => spend(50_000 + (i + 1) * 1000, 2026, 9, i + 1));
    const r = spendingReport([...usual, spend(2_000_000, 2026, 10, 4, "Mua sắm", "Tai nghe")], oct, now);
    const alert = r.alerts.find((a) => a.kind === "LARGE_EXPENSE")!;
    expect(alert.message).toContain("Tai nghe");
    expect(alert.itemId).toBeDefined();
  });

  it("no large-expense alert without enough history", () => {
    const few = [1, 2, 3].map((d) => spend(50_000, 2026, 9, d));
    expect(spendingReport([...few, spend(2_000_000, 2026, 10, 4)], oct, now).alerts.some((a) => a.kind === "LARGE_EXPENSE")).toBe(false);
  });

  it("flags a category far above its own average", () => {
    const history = [7, 8, 9].map((m) => spend(1_000_000, 2026, m, 3));
    const r = spendingReport([...history, spend(2_000_000, 2026, 10, 3)], oct, now);
    const alert = r.alerts.find((a) => a.kind === "CATEGORY_SPIKE")!;
    expect(alert.message.startsWith("Ăn uống")).toBe(true);
    expect(alert.message).toContain("gấp 2 lần");
  });

  it("a quiet month raises no alerts", () => {
    const history = [7, 8, 9].map((m) => spend(1_000_000, 2026, m, 3));
    expect(spendingReport([...history, spend(1_000_000, 2026, 10, 3)], oct, now).alerts).toEqual([]);
  });

  it("flags a fast pace against the same day of previous months", () => {
    const history = [8, 9].map((m) => spend(1_000_000, 2026, m, 3, "Khác"));
    expect(spendingReport([...history, spend(3_000_000, 2026, 10, 3, "Khác")], oct, now).alerts.some((a) => a.kind === "PACE")).toBe(true);
  });

  it("rent early in the month is not a pace alert and is not multiplied into the forecast", () => {
    const history = [...[8, 9].map((m) => spend(5_000_000, 2026, m, 3, "Nhà")), ...[8, 9].map((m) => spend(1_000_000, 2026, m, 20))];
    const r = spendingReport([...history, spend(5_000_000, 2026, 10, 3, "Nhà")], oct, now);
    expect(r.forecast).toBe(6_000_000);
    expect(r.alerts.some((a) => a.kind === "PACE")).toBe(false);
  });

  it("flags the same expense entered twice in a day", () => {
    const r = spendingReport([spend(45_000, 2026, 10, 4, "Ăn uống", "Cà phê"), spend(45_000, 2026, 10, 4, "Ăn uống", "ca phe")], oct, now);
    expect(r.alerts.filter((a) => a.kind === "POSSIBLE_DUPLICATE")).toHaveLength(1);
  });

  it("compact amounts", () => {
    expect(compact(850_000)).toBe("850 nghìn");
    expect(compact(1_200_000)).toBe("1,2 triệu");
    expect(compact(2_000_000)).toBe("2 triệu");
  });
});
