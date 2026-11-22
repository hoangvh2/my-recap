import { describe, expect, it } from "vitest";
import { holidaysOfYear } from "../../shared/holidays";
import { solarToLunar } from "../../shared/lunar";
import { DEFAULT_LUNAR_PREFS, clockText, parseClock, readLunarPrefs } from "../src/lib/lunarCfg";
import { deleteBody } from "../src/lib/format";
import { holidayLine, longDate, lunarChip, lunarFull, monthlyMark, monthlyNotice, monthlyTitle } from "../src/lib/lunarText";

describe("lunar text", () => {
  it("short chip and full names", () => {
    const l = solarToLunar("2027-04-12")!;
    expect(lunarChip(l)).toBe("6/3 Âm");
    expect(lunarFull(l)).toBe("Mùng 6 tháng 3 Âm");
    expect(lunarFull(solarToLunar("2027-04-21")!)).toBe("Rằm tháng 3 Âm");
    expect(lunarFull({ day: 22, month: 3, year: 2027, leap: false })).toBe("22 tháng 3 Âm");
    expect(lunarChip({ day: 6, month: 6, year: 2025, leap: true })).toBe("6/6n Âm");
  });
  it("marks mùng 1 and rằm only", () => {
    expect(monthlyMark(solarToLunar("2027-04-07"))).toBe("Mùng 1");
    expect(monthlyMark(solarToLunar("2027-04-21"))).toBe("Rằm");
    expect(monthlyMark(solarToLunar("2027-04-12"))).toBeNull();
  });
  it("holiday line says the day, the countdown and the span", () => {
    const h = holidaysOfYear(2027).find((x) => x.id === "gio-to")!;
    expect(longDate(h.main)).toBe("Thứ Sáu 16/4/2027");
    expect(holidayLine(h, 4)).toBe("Thứ Sáu 16/4/2027 (10/3 Âm). Còn 4 ngày. Nghỉ 16/4 đến 18/4 (3 ngày).");
    expect(holidayLine(h, 1)).toContain("Nghỉ từ ngày mai.");
    expect(holidayLine(h, -1)).toContain("Nghỉ đến hết 18/4.");
  });
});

describe("lunar settings", () => {
  it("defaults: lunar shown, holidays on, mùng 1 and rằm off", () => {
    expect(readLunarPrefs(null)).toEqual(DEFAULT_LUNAR_PREFS);
    expect(DEFAULT_LUNAR_PREFS.monthly).toBe(false);
  });
  it("keeps the old evening-before behaviour by default", () => {
    expect(DEFAULT_LUNAR_PREFS.monthlyLead).toBe(1);
    expect(clockText(DEFAULT_LUNAR_PREFS.monthlyMinutes)).toBe("18:00");
    expect(readLunarPrefs({ monthly: true }).monthlyLead).toBe(1);
  });
  it("rejects bad lead and time", () => {
    const r = readLunarPrefs({ monthlyLead: 4, monthlyMinutes: 1440 });
    expect([r.monthlyLead, r.monthlyMinutes]).toEqual([1, 1080]);
    expect(parseClock("07:30")).toBe(450);
    expect(parseClock("25:00")).toBeNull();
  });
  it("drops bad values", () => {
    expect(readLunarPrefs({ show: "yes", lead: 4, monthly: true, dismissed: ["a", 3] })).toEqual({ ...DEFAULT_LUNAR_PREFS, monthly: true, dismissed: ["a"] });
    expect(DEFAULT_LUNAR_PREFS.company).toEqual([]);
  });
});

describe("monthly reminder timing", () => {
  const at = (iso: string, h: number, m = 0) => new Date(`${iso}T${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:00`).getTime();
  it("default: from 18:00 the evening before Rằm (21/4/2027) through the day", () => {
    expect(monthlyNotice(at("2027-04-20", 17, 59), 1, 1080)).toBeNull();
    expect(monthlyNotice(at("2027-04-20", 18), 1, 1080)).toMatchObject({ mark: "Rằm", target: "2027-04-21", lead: 1 });
    expect(monthlyNotice(at("2027-04-21", 9), 1, 1080)).toMatchObject({ mark: "Rằm", lead: 0 });
    expect(monthlyNotice(at("2027-04-22", 9), 1, 1080)).toBeNull();
  });
  it("3 days before at 07:30; and on the day itself", () => {
    expect(monthlyNotice(at("2027-04-18", 7, 29), 3, 450)).toBeNull();
    expect(monthlyNotice(at("2027-04-18", 7, 30), 3, 450)).toMatchObject({ target: "2027-04-21", lead: 3 });
    expect(monthlyNotice(at("2027-04-21", 5), 0, 360)).toBeNull();
    expect(monthlyNotice(at("2027-04-21", 6), 0, 360)).toMatchObject({ lead: 0 });
  });
  it("titles", () => {
    expect(monthlyTitle(0, "Rằm", "tháng 3 Âm")).toBe("Hôm nay là Rằm tháng 3 Âm");
    expect(monthlyTitle(1, "Rằm", "tháng 3 Âm")).toBe("Mai là Rằm tháng 3 Âm");
    expect(monthlyTitle(3, "Rằm", "tháng 3 Âm")).toBe("3 ngày nữa là Rằm tháng 3 Âm");
  });
});

describe("delete dialog text", () => {
  it("warns that a repeating item takes later occurrences with it", () => {
    expect(deleteBody({ title: "Giỗ ông nội" })).toBe("Giỗ ông nội");
    expect(deleteBody({ title: "Giỗ ông nội", recurrence: "LUNAR_YEARLY" })).toBe("Giỗ ông nội\nViệc này lặp lại (hằng năm theo âm). Xoá sẽ bỏ cả các lần sau.");
  });
});
