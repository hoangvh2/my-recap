import type { Item } from "./model";
import { clock } from "./format";
import { startOfDay } from "./schedule";

export interface Overview {
  overdue: Item[];
  today: Item[];
  upcoming: Item[];
  someday: Item[];
}

const byWhen = (a: Item, b: Item) => (a.whenAt ?? Infinity) - (b.whenAt ?? Infinity) || a.createdAt - b.createdAt;

/**
 * Open tasks and appointments grouped for the "Tất cả" tab. Notes and expenses have their own tabs.
 * Something another person shared with me (`received`) is never "overdue" for me: if its time was today it
 * stays on today's list, if it was earlier it is left out.
 */
export function overview(items: Item[], now: number, received: (i: Item) => boolean = () => false): Overview {
  const today0 = startOfDay(now);
  const tomorrow0 = today0 + 86_400_000;
  const out: Overview = { overdue: [], today: [], upcoming: [], someday: [] };
  for (const i of items) {
    if (i.status !== "OPEN" || (i.type !== "TASK" && i.type !== "EVENT")) continue;
    if (i.whenAt === undefined) {
      if (i.type === "TASK") out.someday.push(i);
      continue;
    }
    const over = i.allDay ? i.whenAt < today0 : i.whenAt < now && i.type === "TASK";
    const pastEvent = i.type === "EVENT" && !i.allDay && i.whenAt < today0;
    if ((over || pastEvent) && received(i)) {
      if (i.whenAt >= today0) out.today.push(i);
    } else if (over || pastEvent) out.overdue.push(i);
    else if (i.whenAt < tomorrow0) out.today.push(i);
    else out.upcoming.push(i);
  }
  out.overdue.sort(byWhen);
  out.today.sort(byWhen);
  out.upcoming.sort(byWhen);
  out.someday.sort((a, b) => a.createdAt - b.createdAt);
  return out;
}

export interface Agenda {
  /** Tasks whose time has passed and are not done: they stay on top until done. */
  overdue: Item[];
  /** Everything dated from today on, tasks and appointments together, in time order. */
  ahead: Item[];
  /** Appointments that are over, newest first. */
  past: Item[];
}

/**
 * The calendar view: one list of every open dated task and appointment. The same item also shows in
 * its own list ("Việc"); it is one record in two views, never a copy.
 */
export function agenda(items: Item[], now: number, received: (i: Item) => boolean = () => false): Agenda {
  const today0 = startOfDay(now);
  const out: Agenda = { overdue: [], ahead: [], past: [] };
  for (const i of items) {
    if (i.status !== "OPEN" || i.whenAt === undefined || (i.type !== "TASK" && i.type !== "EVENT")) continue;
    if (i.type === "TASK") {
      const late = i.allDay ? i.whenAt < today0 : i.whenAt < now;
      // Someone else's late task is not mine to catch up on: today's stays in the day, older ones drop off.
      if (late && received(i)) {
        if (i.whenAt >= today0) out.ahead.push(i);
      } else (late ? out.overdue : out.ahead).push(i);
    } else {
      const end = i.allDay ? startOfDay(i.whenAt) + 86_400_000 : i.whenAt + 3_600_000;
      (end <= now ? out.past : out.ahead).push(i);
    }
  }
  out.overdue.sort(byWhen);
  out.ahead.sort(byWhen);
  out.past.sort((a, b) => b.whenAt! - a.whenAt!);
  return out;
}

export interface TimelineRow {
  item: Item;
  /** "09:30", or "Cả ngày". */
  time: string;
  /** An appointment that is already over. */
  past: boolean;
}

/**
 * Today as a timeline: all-day entries first, then by time; `nowAt` is where the "now" line goes
 * (before the first entry still ahead).
 */
export function timeline(today: Item[], now: number): { rows: TimelineRow[]; nowAt: number } {
  const allDay = today.filter((i) => i.allDay || i.whenAt === undefined).sort(byWhen);
  const timed = today.filter((i) => !i.allDay && i.whenAt !== undefined).sort(byWhen);
  const rows: TimelineRow[] = [
    ...allDay.map((item) => ({ item, time: "Cả ngày", past: false })),
    ...timed.map((item) => ({ item, time: clock(item.whenAt!), past: item.type === "EVENT" && item.whenAt! + 3_600_000 <= now })),
  ];
  const firstAhead = rows.findIndex((r, i) => i >= allDay.length && r.item.whenAt! >= now);
  return { rows, nowAt: firstAhead < 0 ? rows.length : firstAhead };
}

export function monthKey(t: number): string {
  const d = new Date(t);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

export interface ExpenseSummary {
  total: number;
  byCategory: { category: string; total: number }[];
  items: Item[];
}

/** Saved (non-draft) expenses of one month, newest first, with the category split. */
export function expensesOfMonth(items: Item[], key: string): ExpenseSummary {
  const list = items
    .filter((i) => i.type === "EXPENSE" && i.status !== "DRAFT" && monthKey(i.whenAt ?? i.createdAt) === key)
    .sort((a, b) => (b.whenAt ?? b.createdAt) - (a.whenAt ?? a.createdAt));
  const cat = new Map<string, number>();
  let total = 0;
  for (const i of list) {
    const v = i.amount ?? 0;
    total += v;
    const c = i.category ?? "Khác";
    cat.set(c, (cat.get(c) ?? 0) + v);
  }
  const byCategory = [...cat.entries()].map(([category, t]) => ({ category, total: t })).sort((a, b) => b.total - a.total);
  return { total, byCategory, items: list };
}

export function shiftMonth(key: string, delta: number): string {
  const [y, m] = key.split("-").map(Number);
  return monthKey(new Date(y, m - 1 + delta, 1).getTime());
}

/** Lowercase, no diacritics, đ → d: "Họp Đội" matches "hop doi". */
export function fold(s: string): string {
  return s.normalize("NFD").replace(/\p{M}/gu, "").replace(/đ/gi, "d").toLowerCase();
}

export function matches(item: Item, query: string): boolean {
  const q = fold(query.trim());
  if (!q) return true;
  return fold([item.title, item.details, item.place, item.person, item.category, item.quote].filter(Boolean).join(" ")).includes(q);
}
