import { describe, expect, it } from "vitest";
import { holidaysOfYear } from "../../shared/holidays";
import { solarToLunar } from "../../shared/lunar";
import { DEFAULT_LUNAR_PREFS, readLunarPrefs } from "../src/lib/lunarCfg";
import { deleteBody } from "../src/lib/format";
import { holidayLine, longDate, lunarChip, lunarFull, monthlyMark } from "../src/lib/lunarText";

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
  it("drops bad values", () => {
    expect(readLunarPrefs({ show: "yes", lead: 4, monthly: true, dismissed: ["a", 3] })).toEqual({ ...DEFAULT_LUNAR_PREFS, monthly: true, dismissed: ["a"] });
  });
});

describe("delete dialog text", () => {
  it("warns that a repeating item takes later occurrences with it", () => {
    expect(deleteBody({ title: "Giỗ ông nội" })).toBe("Giỗ ông nội");
    expect(deleteBody({ title: "Giỗ ông nội", recurrence: "LUNAR_YEARLY" })).toBe("Giỗ ông nội\nViệc này lặp lại (hằng năm theo âm). Xoá sẽ bỏ cả các lần sau.");
  });
});
