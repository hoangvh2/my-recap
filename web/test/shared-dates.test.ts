import { describe, expect, it } from "vitest";
import { addDays, addMonths, diffDays, formatDate, formatDateShort, isIsoDate, isoDateIn, monthsBetween, parseDateLoose, shiftMonthKey } from "../../shared/dates";
import { fold, matchByName } from "../../shared/text";

describe("dates", () => {
  it("validates real calendar days only", () => {
    expect(isIsoDate("2027-03-15")).toBe(true);
    expect(isIsoDate("2027-02-29")).toBe(false);
    expect(isIsoDate("2026-02-30")).toBe(false);
    expect(isIsoDate("2027-3-15")).toBe(false);
    expect(isIsoDate(20270315)).toBe(false);
  });
  it("does whole-day arithmetic across month and year ends and leap days", () => {
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
    expect(addDays("2028-02-28", 1)).toBe("2028-02-29");
    expect(addDays("2027-03-15", -90)).toBe("2026-12-15");
    expect(diffDays("2026-12-15", "2027-03-15")).toBe(90);
    expect(diffDays("2027-03-15", "2026-12-15")).toBe(-90);
  });
  it("adds months without rolling into the next one", () => {
    expect(addMonths("2027-01-31", 1)).toBe("2027-02-28");
    expect(addMonths("2028-01-31", 1)).toBe("2028-02-29");
    expect(addMonths("2026-03-16", 12)).toBe("2027-03-16");
    expect(addMonths("2026-11-30", 3)).toBe("2027-02-28");
    expect(addMonths("2026-03-16", -3)).toBe("2025-12-16");
  });
  it("measures a term in months", () => {
    expect(monthsBetween("2026-03-16", "2027-03-16")).toBe(12);
    expect(monthsBetween("2026-09-16", "2027-03-16")).toBe(6);
    expect(monthsBetween("2026-01-01", "2026-12-31")).toBe(12);
  });
  it("moves between month keys", () => {
    expect(shiftMonthKey("2026-11", 3)).toBe("2027-02");
    expect(shiftMonthKey("2026-01", -1)).toBe("2025-12");
  });
  it("turns an instant into the date in a zone", () => {
    const t = Date.UTC(2026, 9, 10, 20, 0); // 20:00 UTC = 03:00 next day in Ho Chi Minh
    expect(isoDateIn(t, "UTC")).toBe("2026-10-10");
    expect(isoDateIn(t, "Asia/Ho_Chi_Minh")).toBe("2026-10-11");
  });
  it("formats for Vietnamese readers", () => {
    expect(formatDate("2027-03-15")).toBe("15/03/2027");
    expect(formatDateShort("2026-12-15", "2026-10-10")).toBe("15/12");
    expect(formatDateShort("2027-03-15", "2026-10-10")).toBe("15/03/2027");
  });
  it("reads what people paste from Excel, day first", () => {
    expect(parseDateLoose("15/3/2027")).toBe("2027-03-15");
    expect(parseDateLoose("05-03-2027")).toBe("2027-03-05");
    expect(parseDateLoose("5.3.27")).toBe("2027-03-05");
    expect(parseDateLoose("2027-03-15")).toBe("2027-03-15");
    expect(parseDateLoose("2027/3/5 00:00:00")).toBe("2027-03-05");
    expect(parseDateLoose("31/02/2027")).toBeNull();
    expect(parseDateLoose("13/13/2027")).toBeNull();
    expect(parseDateLoose("hết năm")).toBeNull();
    expect(parseDateLoose("")).toBeNull();
  });
});

describe("customer name matching", () => {
  const list = [
    { id: "1", name: "Công ty ABC" },
    { id: "2", name: "Công ty Đông Á" },
    { id: "3", name: "XYZ Solutions" },
    { id: "4", name: "XYZ Trading" },
  ];
  it("folds diacritics and case", () => {
    expect(fold("  Công  ty ĐÔNG Á ")).toBe("cong ty dong a");
  });
  it("matches exactly, ignoring accents", () => {
    expect(matchByName("cong ty dong a", list)?.id).toBe("2");
  });
  it("matches a shorter spoken name to one customer", () => {
    expect(matchByName("ABC", list)?.id).toBe("1");
    expect(matchByName("Công ty ABC Việt Nam", list)?.id).toBe("1");
  });
  it("refuses to guess when two customers fit", () => {
    expect(matchByName("XYZ", list)).toBeNull();
    expect(matchByName("a", list)).toBeNull();
    expect(matchByName("hoàn toàn khác", list)).toBeNull();
  });
});
