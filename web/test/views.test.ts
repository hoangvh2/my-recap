import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { splitAhead, agenda, expensesOfMonth, fold, matches, monthKey, overview, shiftMonth } from "../src/lib/views";

const now = new Date(2026, 9, 10, 14, 0).getTime();
let n = 0;
const it_ = (over: Partial<Item>): Item => ({ id: `i${n++}`, type: "TASK", status: "OPEN", title: "x", details: "", allDay: false, createdAt: n, ...over });
const at = (d: number, h = 9) => new Date(2026, 9, d, h).getTime();

describe("overview", () => {
  it("groups open tasks and appointments", () => {
    const overdueTask = it_({ title: "late", whenAt: at(9, 20) });
    const lateAllDay = it_({ title: "late-allday", whenAt: new Date(2026, 9, 9).getTime(), allDay: true });
    const todayAllDay = it_({ title: "today-allday", whenAt: new Date(2026, 9, 10).getTime(), allDay: true });
    const todayEarlierTask = it_({ title: "today-earlier", whenAt: at(10, 8) });
    const todayEvent = it_({ type: "EVENT", title: "event", whenAt: at(10, 16) });
    const earlierTodayEvent = it_({ type: "EVENT", title: "event-earlier", whenAt: at(10, 9) });
    const tomorrow = it_({ title: "tomorrow", whenAt: at(11) });
    const someday = it_({ title: "someday" });
    const done = it_({ title: "done", status: "DONE", whenAt: at(9) });
    const draft = it_({ title: "draft", status: "DRAFT", whenAt: at(11) });
    const note = it_({ type: "NOTE", title: "note" });
    const expense = it_({ type: "EXPENSE", title: "exp", whenAt: at(10) });
    const o = overview([overdueTask, lateAllDay, todayAllDay, todayEarlierTask, todayEvent, earlierTodayEvent, tomorrow, someday, done, draft, note, expense], now);
    expect(o.overdue.map((i) => i.title)).toEqual(["late-allday", "late", "today-earlier"]);
    expect(o.today.map((i) => i.title)).toEqual(["today-allday", "event-earlier", "event"]);
    expect(o.upcoming.map((i) => i.title)).toEqual(["tomorrow"]);
    expect(o.someday.map((i) => i.title)).toEqual(["someday"]);
  });
});

describe("splitAhead (dashboard Sắp tới)", () => {
  const ahead = (...items: Item[]) => splitAhead(items, now);
  it("keeps what is at most 3 calendar days away in soon, one-off and repeating alike", () => {
    const d1 = it_({ title: "d1", whenAt: at(11) });
    const d3late = it_({ title: "d3-late", whenAt: new Date(2026, 9, 13, 23, 30).getTime() });
    const d3rep = it_({ title: "d3-rep", whenAt: at(13), recurrence: "WEEKLY" });
    const a = ahead(d1, d3late, d3rep);
    expect(a.soon.map((i) => i.title)).toEqual(["d1", "d3-late", "d3-rep"]);
    expect(a.later).toEqual([]);
    expect(a.recurring).toEqual([]);
  });
  it("folds further one-off items into later and repeating ones into recurring, each item once", () => {
    const d4 = it_({ title: "d4", whenAt: at(14, 0) });
    const d4rep = it_({ title: "d4-rep", whenAt: at(14, 8), recurrence: "WEEKLY" });
    const monthly = it_({ title: "monthly", whenAt: at(30), recurrence: "MONTHLY" });
    const far = it_({ title: "far", whenAt: at(28) });
    const a = ahead(d4, d4rep, monthly, far);
    expect(a.soon).toEqual([]);
    expect(a.later.map((i) => i.title)).toEqual(["d4", "far"]);
    expect(a.recurring.map((i) => i.title)).toEqual(["d4-rep", "monthly"]);
    expect(a.soon.length + a.later.length + a.recurring.length).toBe(4);
  });
  it("measures calendar days, so late tonight and early on day 4 differ", () => {
    const lateNow = new Date(2026, 9, 10, 23, 50).getTime();
    const x = it_({ whenAt: new Date(2026, 9, 14, 0, 5).getTime() });
    expect(splitAhead([x], lateNow).later).toEqual([x]);
  });
});

describe("expenses", () => {
  const e = (title: string, amount: number, category: string, d: number, month = 9) =>
    it_({ type: "EXPENSE", title, amount, category, whenAt: new Date(2026, month, d).getTime(), allDay: true });
  it("totals one month by category, newest first, ignoring drafts", () => {
    const items = [e("a", 100, "Ăn uống", 1), e("b", 300, "Di chuyển", 5), e("c", 50, "Ăn uống", 7), e("old", 999, "Khác", 30, 8), it_({ type: "EXPENSE", status: "DRAFT", amount: 7, whenAt: at(3) })];
    const s = expensesOfMonth(items, "2026-10");
    expect(s.total).toBe(450);
    expect(s.byCategory).toEqual([{ category: "Di chuyển", total: 300 }, { category: "Ăn uống", total: 150 }]);
    expect(s.items.map((i) => i.title)).toEqual(["c", "b", "a"]);
  });
  it("moves between months", () => {
    expect(monthKey(now)).toBe("2026-10");
    expect(shiftMonth("2026-10", -1)).toBe("2026-09");
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
  });
});

describe("search", () => {
  it("ignores case and Vietnamese diacritics", () => {
    expect(fold("Họp Đội Ngũ")).toBe("hop doi ngu");
    expect(matches(it_({ title: "Họp với anh Nam", place: "Quận 1" }), "hop nam")).toBe(false); // words must be contiguous
    expect(matches(it_({ title: "Họp với anh Nam" }), "HỌP VỚI")).toBe(true);
    expect(matches(it_({ title: "x", place: "Cà phê" }), "ca phe")).toBe(true);
    expect(matches(it_({ title: "x" }), "  ")).toBe(true);
  });
});

describe("agenda", () => {
  it("puts dated tasks and appointments on one calendar, each once, overdue tasks first", () => {
    const late = it_({ title: "late", whenAt: at(9, 20) });
    const todayTask = it_({ title: "today-task", whenAt: at(10, 16) });
    const meeting = it_({ type: "EVENT", title: "meeting", whenAt: at(10, 15) });
    const pastMeeting = it_({ type: "EVENT", title: "past", whenAt: at(10, 9) });
    const tomorrow = it_({ title: "tomorrow", whenAt: at(11), allDay: true });
    const undated = it_({ title: "undated" });
    const note = it_({ type: "NOTE", title: "note", whenAt: at(11) });
    const done = it_({ title: "done", status: "DONE", whenAt: at(11) });
    const a = agenda([tomorrow, meeting, late, todayTask, pastMeeting, undated, note, done], now);
    expect(a.overdue.map((i) => i.title)).toEqual(["late"]);
    expect(a.ahead.map((i) => i.title)).toEqual(["meeting", "today-task", "tomorrow"]);
    expect(a.past.map((i) => i.title)).toEqual(["past"]);
  });
});

describe("timeline", () => {
  it("puts all-day entries first, then by time, with the now line before the first entry still ahead", async () => {
    const { timeline } = await import("../src/lib/views");
    const day = it_({ title: "day", whenAt: new Date(2026, 9, 10).getTime(), allDay: true });
    const nine = it_({ type: "EVENT", title: "nine", whenAt: at(10, 9) });
    const sixteen = it_({ type: "EVENT", title: "sixteen", whenAt: at(10, 16) });
    const fifteen = it_({ title: "fifteen", whenAt: at(10, 15) });
    const t = timeline([sixteen, day, fifteen, nine], now); // now = 14:00
    expect(t.rows.map((r) => [r.time, r.item.title, r.past])).toEqual([
      ["Cả ngày", "day", false], ["09:00", "nine", true], ["15:00", "fifteen", false], ["16:00", "sixteen", false],
    ]);
    expect(t.nowAt).toBe(2);
  });
});
