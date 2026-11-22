import { describe, expect, it } from "vitest";
import { companyBanner, companyBreaksOfYear, companyNext, companyOn, companyProblem, parseCompany, type CompanyHoliday } from "../../shared/company";

const hè: CompanyHoliday = { id: "a", name: "Nghỉ hè công ty", from: "2026-07-20", to: "2026-07-22", yearly: true };
const once: CompanyHoliday = { id: "b", name: "Team building", from: "2026-11-27", to: "2026-11-27", yearly: false };
const cuối: CompanyHoliday = { id: "c", name: "Nghỉ cuối năm", from: "2026-12-30", to: "2027-01-02", yearly: true };

describe("company holidays", () => {
  it("a yearly entry repeats on the same day from the year it was entered, a one-off stays in its year", () => {
    expect(companyBreaksOfYear([hè, once], 2028).map((b) => [b.name, b.from, b.to])).toEqual([["Nghỉ hè công ty", "2028-07-20", "2028-07-22"]]);
    expect(companyBreaksOfYear([hè, once], 2026).map((b) => b.name)).toEqual(["Nghỉ hè công ty", "Team building"]);
    expect(companyBreaksOfYear([hè], 2025)).toEqual([]);
  });
  it("finds the break a date is in, including one that crosses the new year", () => {
    expect(companyOn("2027-07-21", [hè])?.name).toBe("Nghỉ hè công ty");
    expect(companyOn("2027-07-23", [hè])).toBeNull();
    expect(companyOn("2028-01-01", [cuối])?.name).toBe("Nghỉ cuối năm");
    expect(companyOn("2027-01-02", [cuối])?.name).toBe("Nghỉ cuối năm");
  });
  it("29/2 falls back to 28/2 in a year without one", () => {
    const leap: CompanyHoliday = { id: "d", name: "X", from: "2028-02-29", to: "2028-02-29", yearly: true };
    expect(companyBreaksOfYear([leap], 2029)[0]!.from).toBe("2029-02-28");
  });
  it("next break and banner count the days and honour dismissals", () => {
    expect(companyNext("2026-07-10", [hè])?.daysUntil).toBe(10);
    expect(companyBanner("2026-07-10", 5, [], [hè])).toBeNull();
    expect(companyBanner("2026-07-17", 5, [], [hè])?.daysUntil).toBe(3);
    expect(companyBanner("2026-07-21", 5, [], [hè])?.daysUntil).toBe(-1);
    expect(companyBanner("2026-07-17", 5, ["company:a:2026"], [hè])).toBeNull();
  });
  it("validates a draft", () => {
    expect(companyProblem({ name: " ", from: "2026-07-20", to: "2026-07-20" })).toMatch(/tên/);
    expect(companyProblem({ name: "x", from: "", to: "" })).toMatch(/bắt đầu/);
    expect(companyProblem({ name: "x", from: "2026-07-20", to: "2026-07-19" })).toMatch(/sau/);
    expect(companyProblem({ name: "x", from: "2026-01-01", to: "2026-03-01" })).toMatch(/tối đa/);
    expect(companyProblem({ name: "x", from: "2026-07-20", to: "2026-07-20" })).toBeNull();
  });
  it("drops malformed stored entries", () => {
    expect(parseCompany([hè, { id: "a", name: "dup", from: "2026-01-01", to: "2026-01-01" }, { id: "z", name: "bad", from: "x", to: "y" }, null, 3])).toEqual([hè]);
    expect(parseCompany("nope")).toEqual([]);
  });
});
