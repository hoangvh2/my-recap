import { describe, expect, it } from "vitest";
import { buildIcs, escapeText, fold } from "../src/lib/ics";
import type { Item } from "../src/lib/model";

const item = (over: Partial<Item>): Item => ({ id: "abc", type: "EVENT", status: "OPEN", title: "Họp", details: "", allDay: false, createdAt: 0, ...over });
const at = new Date(2026, 9, 15, 15, 0).getTime();
const stamp = Date.UTC(2026, 9, 10, 7, 0, 0);

describe("buildIcs", () => {
  it("writes a timed appointment with a 15-minute alarm", () => {
    const ics = buildIcs([item({ whenAt: at, place: "Cafe, Quận 1", person: "anh Nam" })], stamp);
    expect(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")).toBe(true);
    expect(ics).toContain("UID:abc@myrecap");
    expect(ics).toContain("DTSTAMP:20261010T070000Z");
    expect(ics).toContain("DTSTART:20261015T150000");
    expect(ics).toContain("DTEND:20261015T160000");
    expect(ics).toContain("LOCATION:Cafe\\, Quận 1");
    expect(ics).toContain("DESCRIPTION:Với: anh Nam");
    expect(ics).toContain("TRIGGER:-PT15M");
    expect(ics.endsWith("END:VCALENDAR\r\n")).toBe(true);
  });
  it("alarms a task at its due time and an all-day item at 8:00", () => {
    expect(buildIcs([item({ type: "TASK", whenAt: at })], stamp)).toContain("TRIGGER:PT0S");
    const allDay = buildIcs([item({ type: "TASK", whenAt: new Date(2026, 9, 15).getTime(), allDay: true })], stamp);
    expect(allDay).toContain("DTSTART;VALUE=DATE:20261015");
    expect(allDay).toContain("DTEND;VALUE=DATE:20261016");
    expect(allDay).toContain("TRIGGER;RELATED=START:PT8H");
    expect(allDay).toContain("SUMMARY:Việc: Họp");
  });
  it("repeats", () => {
    expect(buildIcs([item({ whenAt: at, recurrence: "WEEKDAYS" })], stamp)).toContain("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR");
    expect(buildIcs([item({ whenAt: at, recurrence: "MONTHLY" })], stamp)).toContain("RRULE:FREQ=MONTHLY");
  });
  it("skips items with no date", () => {
    expect(buildIcs([item({})], stamp)).not.toContain("BEGIN:VEVENT");
  });
  it("escapes text and folds long lines at 75 octets without breaking characters", () => {
    expect(escapeText("a,b;c\\d\ne")).toBe("a\\,b\;c\\\\d\\ne");
    const long = "Việt Nam ".repeat(30);
    const ics = buildIcs([item({ whenAt: at, title: long })], stamp);
    const enc = new TextEncoder();
    for (const line of ics.split("\r\n")) expect(enc.encode(line).length).toBeLessThanOrEqual(75);
    // Unfolding gives the original text back.
    expect(ics.replace(/\r\n /g, "")).toContain(`SUMMARY:${escapeText(long)}`);
    expect(fold("short")).toBe("short");
  });
});
