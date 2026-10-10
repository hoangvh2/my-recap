import { describe, expect, it } from "vitest";
import { dayLabel, fromInputs, toDateInput, toTimeInput, whenLabel } from "../src/lib/format";
import type { Item } from "../src/lib/model";

const now = new Date(2026, 9, 10, 14, 0).getTime(); // Saturday

describe("format", () => {
  it("labels days relative to today", () => {
    expect(dayLabel(new Date(2026, 9, 10, 8).getTime(), now)).toBe("Hôm nay");
    expect(dayLabel(new Date(2026, 9, 11).getTime(), now)).toBe("Mai");
    expect(dayLabel(new Date(2026, 9, 9).getTime(), now)).toBe("Hôm qua");
    expect(dayLabel(new Date(2026, 9, 15).getTime(), now)).toBe("Thứ Năm 15/10");
    expect(dayLabel(new Date(2027, 0, 5).getTime(), now)).toBe("Thứ Ba 05/01/2027");
  });
  it("adds the clock time unless the item is all-day", () => {
    const base: Item = { id: "a", type: "EVENT", status: "OPEN", title: "x", details: "", allDay: false, createdAt: 0, whenAt: new Date(2026, 9, 11, 9, 5).getTime() };
    expect(whenLabel(base, now)).toBe("Mai 09:05");
    expect(whenLabel({ ...base, allDay: true }, now)).toBe("Mai");
    expect(whenLabel({ ...base, whenAt: undefined }, now)).toBeNull();
  });
  it("round-trips the date and time inputs", () => {
    const t = new Date(2026, 9, 15, 15, 30).getTime();
    expect(toDateInput(t)).toBe("2026-10-15");
    expect(toTimeInput(t)).toBe("15:30");
    expect(fromInputs("2026-10-15", "15:30", false)).toBe(t);
    expect(fromInputs("2026-10-15", "", true)).toBe(new Date(2026, 9, 15).getTime());
  });
  it("refuses impossible dates and missing times", () => {
    expect(fromInputs("2026-02-30", "10:00", false)).toBeNull();
    expect(fromInputs("", "10:00", false)).toBeNull();
    expect(fromInputs("2026-10-15", "", false)).toBeNull();
  });
});
