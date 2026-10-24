import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import data from "../../shared/holidays-vn.json";
import { bannerFor, bannerKey, holidayDays, holidayOn, holidaysOfYear, nextHoliday, parseOverrides, weekday } from "../../shared/holidays";

const overrides = parseOverrides(data);
const get = (id: string, y: number) => holidaysOfYear(y, overrides).find((h) => h.id === id)!;

describe("holidays from the labour code", () => {
  it("Giỗ Tổ on a Sunday is made up on Monday (2026)", () => {
    const h = get("gio-to", 2026);
    expect([h.main, h.from, h.to, h.status]).toEqual(["2026-04-26", "2026-04-25", "2026-04-27", "rule"]);
  });
  it("30/4 and 1/5 on Thursday and Friday give four days (2026)", () => {
    const h = get("30-4-1-5", 2026);
    expect([h.from, h.to, holidayDays(h)]).toEqual(["2026-04-30", "2026-05-03", 4]);
  });
  it("1/5 on a Saturday is made up on Monday 3/5 (2027)", () => {
    const h = get("30-4-1-5", 2027);
    expect([h.from, h.to]).toEqual(["2027-04-30", "2027-05-03"]);
  });
  it("Giỗ Tổ 2027 is Friday 16/4 and the break runs to Sunday", () => {
    const h = get("gio-to", 2027);
    expect([h.main, weekday(h.main), h.from, h.to]).toEqual(["2027-04-16", 5, "2027-04-16", "2027-04-18"]);
  });
  it("Tết Dương lịch on a Friday is three days (2027)", () => {
    const h = get("tet-duong-lich", 2027);
    expect([h.from, h.to]).toEqual(["2027-01-01", "2027-01-03"]);
  });
  it("Tết without an announcement still gives the legal five days around mùng 1", () => {
    const h = get("tet-nguyen-dan", 2028);
    expect(h.status).toBe("rule");
    expect(h.main).toBe("2028-01-26");
    expect(h.legal).toEqual(["2028-01-25", "2028-01-26", "2028-01-27", "2028-01-28", "2028-01-29"]);
    expect(h.from <= "2028-01-25" && h.to >= "2028-01-29").toBe(true);
  });
});

describe("announced schedules", () => {
  it("replace the rule and keep the source", () => {
    const tet = get("tet-nguyen-dan", 2027);
    expect([tet.from, tet.to, tet.status, holidayDays(tet)]).toEqual(["2027-02-04", "2027-02-10", "announced", 7]);
    expect(tet.source).toContain("11483/TB-BNV");
    expect(get("quoc-khanh", 2026).workdays).toEqual(["2026-08-22"]);
  });
  it("the file has an entry for every year it is about and no bad rows", () => {
    expect(overrides.length).toBe((data as { periods: unknown[] }).periods.length);
  });
  it("the copy served by the web app is the same file", () => {
    expect(JSON.parse(readFileSync(new URL("../public/holidays-vn.json", import.meta.url), "utf8"))).toEqual(data);
  });
  it("a malformed file is ignored row by row", () => {
    expect(parseOverrides({ periods: [{ id: "nope", year: 2027, from: "2027-01-01", to: "2027-01-02" }, { id: "gio-to", year: 2027, from: "2027-04-20", to: "2027-04-16" }, { id: "gio-to", year: 2027, from: "2027-04-16", to: "2027-04-18" }] })).toEqual([
      { id: "gio-to", year: 2027, from: "2027-04-16", to: "2027-04-18" },
    ]);
    expect(parseOverrides(null)).toEqual([]);
    expect(parseOverrides("x")).toEqual([]);
  });
});

describe("banner", () => {
  it("shows the break that starts within the notice", () => {
    const b = bannerFor("2027-04-12", 5, [], overrides)!;
    expect([b.holiday.id, b.daysUntil]).toEqual(["gio-to", 4]);
  });
  it("is quiet when nothing starts soon, and after being hidden", () => {
    expect(bannerFor("2027-04-05", 5, [], overrides)).toBeNull();
    expect(bannerFor("2027-04-12", 5, [bannerKey({ id: "gio-to", year: 2027 })], overrides)).toBeNull();
  });
  it("Tết gives two weeks' notice whatever the setting", () => {
    expect(bannerFor("2027-01-25", 3, [], overrides)!.holiday.id).toBe("tet-nguyen-dan");
    expect(bannerFor("2027-01-15", 3, [], overrides)).toBeNull();
  });
  it("keeps showing while the break is on, then moves on", () => {
    const on = bannerFor("2027-02-06", 5, [], overrides)!;
    expect([on.holiday.id, on.daysUntil]).toEqual(["tet-nguyen-dan", -2]);
    expect(nextHoliday("2027-02-11", overrides)!.holiday.id).toBe("gio-to");
  });
  it("looks into next year from December", () => {
    expect(nextHoliday("2026-12-30", overrides)!.holiday.id).toBe("tet-duong-lich");
  });
});

describe("calendar marks", () => {
  it("tells legal days from extra days off", () => {
    expect(holidayOn("2027-04-16", overrides)?.kind).toBe("legal");
    expect(holidayOn("2027-04-17", overrides)?.kind).toBe("weekend");
    expect(holidayOn("2027-02-04", overrides)?.kind).toBe("extra");
    expect(holidayOn("2027-03-10", overrides)).toBeNull();
  });
});
