import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { expensesOfMonth, fold, matches, monthKey, overview, shiftMonth } from "../src/lib/views";

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
