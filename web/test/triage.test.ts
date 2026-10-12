import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { applyMove, overdueTasks } from "../src/lib/triage";

const ms = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime();
const now = ms(2026, 10, 14, 10); // Wednesday 10:00
const task = (id: string, at?: number, over: Partial<Item> = {}): Item => ({ id, type: "TASK", status: "OPEN", title: id, details: "", allDay: false, createdAt: 0, ...(at !== undefined ? { whenAt: at } : {}), ...over });

describe("triage", () => {
  it("lists only open overdue tasks, oldest first", () => {
    const items = [
      task("late-today", ms(2026, 10, 14, 8)), task("old", ms(2026, 10, 10, 9)),
      task("all-day-yesterday", ms(2026, 10, 13), { allDay: true }), task("all-day-today", ms(2026, 10, 14), { allDay: true }),
      task("later-today", ms(2026, 10, 14, 15)), task("no-date"), task("done", ms(2026, 10, 1), { status: "DONE" }),
      task("meeting", ms(2026, 10, 12, 9), { type: "EVENT" }),
    ];
    expect(overdueTasks(items, now).map((i) => i.id)).toEqual(["old", "all-day-yesterday", "late-today"]);
  });

  it("tomorrow keeps the time of day", () => {
    const moved = applyMove(task("t", ms(2026, 10, 10, 9, 30)), "TOMORROW", now);
    expect(moved).toMatchObject({ whenAt: ms(2026, 10, 15, 9, 30), allDay: false });
  });

  it("today keeps a time still ahead but never leaves the task overdue", () => {
    expect(applyMove(task("t", ms(2026, 10, 10, 16)), "TODAY", now).whenAt).toBe(ms(2026, 10, 14, 16));
    const passed = applyMove(task("t", ms(2026, 10, 10, 9)), "TODAY", now);
    expect(passed).toMatchObject({ whenAt: ms(2026, 10, 14), allDay: true });
    expect(overdueTasks([passed], now)).toEqual([]);
  });

  it("next week is the coming Monday, even on a Monday", () => {
    expect(applyMove(task("t", ms(2026, 10, 10, 9)), "NEXT_WEEK", now).whenAt).toBe(ms(2026, 10, 19, 9));
    expect(applyMove(task("t", ms(2026, 10, 12), { allDay: true }), "NEXT_WEEK", ms(2026, 10, 19, 10)).whenAt).toBe(ms(2026, 10, 26));
    expect(applyMove(task("t", ms(2026, 10, 12), { allDay: true }), "NEXT_WEEK", ms(2026, 10, 18, 10)).whenAt).toBe(ms(2026, 10, 19)); // Sunday
  });

  it("someday drops the date and keeps the task open", () => {
    const moved = applyMove(task("t", ms(2026, 10, 10, 9)), "SOMEDAY", now);
    expect(moved.whenAt).toBeUndefined();
    expect(moved).toMatchObject({ allDay: false, status: "OPEN" });
  });

  it("an all-day task stays all-day", () => {
    expect(applyMove(task("t", ms(2026, 10, 12), { allDay: true }), "TOMORROW", now)).toMatchObject({ whenAt: ms(2026, 10, 15), allDay: true });
  });
});
