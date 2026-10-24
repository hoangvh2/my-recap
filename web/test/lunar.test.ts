import { describe, expect, it } from "vitest";
import vectors from "../../shared/lunar-vectors.json";
import { lunarMonth, lunarToSolar, lunarToSolarClamped, nextLunarMonth, nextLunarOccurrence, solarToLunar } from "../../shared/lunar";
import { next, nextAfter } from "../src/lib/schedule";

describe("lunar calendar (UTC+7)", () => {
  it("mùng 1 Tết matches the published dates", () => {
    for (const [iso, year] of vectors.newYear as [string, number][]) {
      expect(solarToLunar(iso), iso).toEqual({ day: 1, month: 1, year, leap: false });
      expect(lunarToSolar(year, 1, 1), String(year)).toBe(iso);
    }
  });

  it("Giỗ Tổ 10/3 matches the published dates", () => {
    for (const [iso, year] of vectors.gioTo as [string, number][]) expect(lunarToSolar(year, 3, 10), String(year)).toBe(iso);
  });

  it("finds the known leap months and no others in 2020-2029", () => {
    const found: [number, number][] = [];
    for (let y = 2020; y <= 2029; y++) for (let m = 1; m <= 12; m++) if (lunarMonth(y, m, true)) found.push([y, m]);
    expect(found).toEqual(vectors.leapMonths.filter(([y]) => y <= 2029));
  });

  it("round-trips every day from 1950 to 2150 and the calendar is continuous", () => {
    const start = Date.UTC(1950, 0, 1);
    let prev: ReturnType<typeof solarToLunar> = null;
    for (let i = 0; i < 201 * 365.25; i += 1) {
      const iso = new Date(start + i * 86_400_000).toISOString().slice(0, 10);
      const l = solarToLunar(iso)!;
      expect(lunarToSolar(l.year, l.month, l.day, l.leap), iso).toBe(iso);
      if (prev) {
        // Either the next day of the same month, or day 1 right after day 29/30.
        if (l.day === 1) expect([29, 30], iso).toContain(prev.day);
        else expect({ d: l.day, m: l.month, y: l.year, p: l.leap }, iso).toEqual({ d: prev.day + 1, m: prev.month, y: prev.year, p: prev.leap });
      }
      prev = l;
    }
  });

  it("a lunar month has 29 or 30 days and days run 1..length", () => {
    const m = lunarMonth(2027, 2)!;
    expect([29, 30]).toContain(m.days);
    expect(lunarToSolar(2027, 2, m.days + 1)).toBeNull();
    expect(lunarToSolar(2027, 2, 0)).toBeNull();
  });

  it("today in the proposal mock: 12/4/2027 is 6/3 and 7/4/2027 is mùng 1 tháng 3", () => {
    expect(solarToLunar("2027-04-12")).toEqual({ day: 6, month: 3, year: 2027, leap: false });
    expect(solarToLunar("2027-04-07")).toEqual({ day: 1, month: 3, year: 2027, leap: false });
  });

  it("is undefined outside the supported years", () => {
    expect(solarToLunar("1850-01-01")).toBeNull();
    expect(solarToLunar("2300-01-01")).toBeNull();
  });
});

describe("lunar repeats", () => {
  it("yearly: giỗ 20/3 comes back on 20/3 next lunar year", () => {
    expect(nextLunarOccurrence("2027-04-26", "LUNAR_YEARLY")).toBe(lunarToSolar(2028, 3, 20));
    expect(nextLunarOccurrence("2027-04-26", "LUNAR_YEARLY")).toBe("2028-04-14");
  });

  it("yearly: an item in a leap month moves to the regular month", () => {
    const leap = lunarToSolar(2025, 6, 15, true)!;
    expect(nextLunarOccurrence(leap, "LUNAR_YEARLY")).toBe(lunarToSolar(2026, 6, 15));
  });

  it("monthly: rằm to rằm, and the leap month is its own month", () => {
    expect(nextLunarOccurrence(lunarToSolar(2027, 3, 15)!, "LUNAR_MONTHLY")).toBe(lunarToSolar(2027, 4, 15));
    expect(nextLunarOccurrence(lunarToSolar(2025, 6, 15)!, "LUNAR_MONTHLY")).toBe(lunarToSolar(2025, 6, 15, true));
    expect(nextLunarOccurrence(lunarToSolar(2025, 6, 15, true)!, "LUNAR_MONTHLY")).toBe(lunarToSolar(2025, 7, 15));
    expect(nextLunarMonth({ year: 2026, month: 12, leap: false })).toEqual({ year: 2027, month: 1, leap: false });
  });

  it("day 30 of a 29-day month becomes day 29", () => {
    // find a lunar month with 30 days followed by one with 29 days in 2027
    for (let m = 1; m < 12; m++) {
      const a = lunarMonth(2027, m)!;
      const b = lunarMonth(2027, m + 1)!;
      if (a.days === 30 && b.days === 29) {
        const day30 = lunarToSolar(2027, m, 30)!;
        expect(nextLunarOccurrence(day30, "LUNAR_MONTHLY")).toBe(lunarToSolarClamped(2027, m + 1, 29));
        return;
      }
    }
    throw new Error("no 30→29 pair in 2027");
  });

  it("schedule.next keeps the wall-clock time", () => {
    const at = new Date(2027, 3, 26, 8, 30).getTime(); // 26/4/2027 = 20/3 Âm
    const n = new Date(next(at, "LUNAR_YEARLY"));
    expect([n.getFullYear(), n.getMonth() + 1, n.getDate(), n.getHours(), n.getMinutes()]).toEqual([2028, 4, 14, 8, 30]);
  });

  it("nextAfter skips occurrences already past", () => {
    const at = new Date(2027, 3, 26, 8, 30).getTime();
    const t = new Date(nextAfter(at, "LUNAR_YEARLY", new Date(2029, 0, 1).getTime()));
    expect(t.getFullYear()).toBe(2029);
  });
});
