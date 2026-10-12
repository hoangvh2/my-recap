import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { addWeeks, buildWeekly, weekStartOf } from "../src/lib/weekly";

const ms = (y: number, mo: number, d: number, h = 10) => new Date(y, mo - 1, d, h).getTime();
let n = 0;
// Week of Monday 2026-10-12 .. Sunday 10-18; "now" is Friday 10-16.
const week = ms(2026, 10, 12, 0);
const now = ms(2026, 10, 16);
const done = (y: number, mo: number, d: number): Item => ({ id: `d${n++}`, type: "TASK", status: "DONE", title: "xong", details: "", allDay: false, createdAt: 0, doneAt: ms(y, mo, d), whenAt: ms(y, mo, d) });
const doneAt = (t: number) => { const d = new Date(t); return done(d.getFullYear(), d.getMonth() + 1, d.getDate()); };
const open = (type: Item["type"], y: number, mo: number, d: number): Item => ({ id: `o${n++}`, type, status: "OPEN", title: "mở", details: "", whenAt: ms(y, mo, d), allDay: true, createdAt: 0 });
const spend = (amount: number, y: number, mo: number, d: number, category = "Ăn uống"): Item => ({ id: `s${n++}`, type: "EXPENSE", status: "OPEN", title: "chi", details: "", whenAt: ms(y, mo, d), allDay: true, amount, category, createdAt: 0 });
const before = (w: number, plus = 0) => addWeeks(week, -w) + plus * 86_400_000;

describe("weekly recap", () => {
  it("the week starts on Monday, even from Sunday", () => {
    expect(weekStartOf(ms(2026, 10, 18, 23))).toBe(week);
    expect(weekStartOf(ms(2026, 10, 12, 0))).toBe(week);
  });

  it("counts done tasks per day and against last week", () => {
    const r = buildWeekly([done(2026, 10, 12), done(2026, 10, 12), done(2026, 10, 15), done(2026, 10, 7), done(2026, 10, 19)], [], week, now);
    expect(r.doneByDay).toEqual([2, 0, 0, 1, 0, 0, 0]);
    expect(r.done).toBe(3);
    expect(r.previousDone).toBe(1);
  });

  it("spending totals, top category and the previous week", () => {
    const r = buildWeekly([spend(100_000, 2026, 10, 12), spend(300_000, 2026, 10, 14, "Mua sắm"), spend(50_000, 2026, 10, 8)], [], week, now);
    expect([r.spent, r.previousSpent]).toEqual([400_000, 50_000]);
    expect(r.topCategory).toEqual({ name: "Mua sắm", amount: 300_000 });
  });

  it("counts recordings of the week only", () => {
    expect(buildWeekly([], [ms(2026, 10, 13), ms(2026, 10, 18, 23), ms(2026, 10, 19, 1), ms(2026, 10, 11)], week, now).recordings).toBe(2);
  });

  it("flags stuck tasks only when older than a week", () => {
    const old = open("TASK", 2026, 10, 5);
    const recent = open("TASK", 2026, 10, 14);
    const r = buildWeekly([old, recent], [], week, now);
    expect([r.overdue, r.stale]).toEqual([2, 1]);
    expect(r.insights.some((i) => i.kind === "STUCK")).toBe(true);
  });

  it("low output needs history and a late enough day", () => {
    const history = [1, 2, 3, 4].flatMap((w) => [1, 2, 3, 4].map((i) => doneAt(before(w, i % 3) + 10 * 3600_000)));
    expect(buildWeekly([...history, done(2026, 10, 13)], [], week, now).insights.some((i) => i.kind === "LOW_OUTPUT")).toBe(true);
    expect(buildWeekly(history, [], week, ms(2026, 10, 13)).insights.some((i) => i.kind === "LOW_OUTPUT")).toBe(false);
    const none = buildWeekly([done(2026, 10, 13)], [], week, now);
    expect(none.averageDone).toBeNull();
    expect(none.insights.some((i) => i.kind === "LOW_OUTPUT")).toBe(false);
  });

  it("a spend spike needs three weeks of history", () => {
    const history = [1, 2, 3].map((w) => { const d = new Date(before(w)); return spend(500_000, d.getFullYear(), d.getMonth() + 1, d.getDate()); });
    expect(buildWeekly([...history, spend(1_500_000, 2026, 10, 13)], [], week, now).insights.some((i) => i.kind === "SPEND_UP")).toBe(true);
    expect(buildWeekly([...history.slice(0, 2), spend(1_500_000, 2026, 10, 13)], [], week, now).insights.some((i) => i.kind === "SPEND_UP")).toBe(false);
  });

  it("flags the busiest day of next week", () => {
    const items = [...Array.from({ length: 6 }, () => open("TASK", 2026, 10, 20)), open("EVENT", 2026, 10, 22)];
    const r = buildWeekly(items, [], week, now);
    expect(r.nextWeekByDay).toEqual([0, 6, 0, 1, 0, 0, 0]);
    expect(r.nextWeekTotal).toBe(7);
    expect(r.insights.find((i) => i.kind === "BUSY_DAY")!.message.startsWith("Thứ Ba")).toBe(true);
  });

  it("a quiet week raises nothing", () => {
    expect(buildWeekly([done(2026, 10, 13), open("TASK", 2026, 10, 21)], [], week, now).insights).toEqual([]);
  });
});
