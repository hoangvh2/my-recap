import type { Item } from "./model";
import { compact, OTHER } from "./spending";
import { startOfDay } from "./schedule";

/**
 * One week (Monday to Sunday) at a glance: what got done, what was spent, how many recordings were made, what is
 * stuck, and how heavy next week looks. Pure functions so every number is testable. Port of the Android `WeeklyRecap`.
 */
export const STALE_DAYS = 7;
const DAY = 86_400_000;
const WEEKDAYS = ["Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ nhật"];
export const weekdayName = (i: number) => WEEKDAYS[i];

export type InsightKind = "STUCK" | "LOW_OUTPUT" | "SPEND_UP" | "BUSY_DAY";
export interface Insight { kind: InsightKind; message: string }

export interface WeeklyReport {
  /** Monday 00:00 of the week (epoch ms). */
  weekStart: number;
  /** Tasks finished on each day, Monday first. */
  doneByDay: number[];
  done: number;
  previousDone: number;
  /** Average tasks finished per week over the four weeks before, or null with under two weeks of history. */
  averageDone: number | null;
  /** Open tasks past due, and those overdue for more than STALE_DAYS days. */
  overdue: number;
  stale: number;
  spent: number;
  previousSpent: number;
  topCategory: { name: string; amount: number } | null;
  recordings: number;
  /** Open tasks and appointments due on each day of the following week, Monday first. */
  nextWeekByDay: number[];
  nextWeekTotal: number;
  insights: Insight[];
}

/** Monday 00:00 of the week [t] falls in. */
export function weekStartOf(t: number): number {
  const d = new Date(startOfDay(t));
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - ((d.getDay() + 6) % 7)).getTime();
}

/** [weeks] weeks after [start] (negative: before), at midnight, safe across daylight saving. */
export function addWeeks(start: number, weeks: number): number {
  const d = new Date(start);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 7 * weeks).getTime();
}

const trim = (x: number) => {
  const r = Math.round(x * 10) / 10;
  return Number.isInteger(r) ? String(r) : String(r).replace(".", ",");
};
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);

export function buildWeekly(items: Item[], recordingTimes: number[], weekStart: number, now: number): WeeklyReport {
  const today0 = startOfDay(now);
  const inWeek = (t: number | undefined, start: number) => t !== undefined && t >= start && t < addWeeks(start, 1);
  const dayIndex = (t: number, start: number) => Math.round((startOfDay(t) - start) / DAY);

  const doneTasks = items.filter((i) => i.type === "TASK" && i.status === "DONE" && i.doneAt !== undefined);
  const doneIn = (start: number) => doneTasks.filter((i) => inWeek(i.doneAt, start)).length;
  const doneByDay = Array.from({ length: 7 }, (_, d) => doneTasks.filter((i) => inWeek(i.doneAt, weekStart) && dayIndex(i.doneAt!, weekStart) === d).length);
  const done = sum(doneByDay);
  const history = [1, 2, 3, 4].map((w) => doneIn(addWeeks(weekStart, -w)));
  const averageDone = history.filter((n) => n > 0).length >= 2 ? sum(history) / 4 : null;

  const overdueTasks = items.filter((t) => t.type === "TASK" && t.status === "OPEN" && t.whenAt !== undefined && (t.allDay ? t.whenAt < today0 : t.whenAt < now));
  const stale = overdueTasks.filter((t) => startOfDay(t.whenAt!) < today0 - STALE_DAYS * DAY).length;

  const expenses = items.filter((i) => i.type === "EXPENSE" && i.status !== "DRAFT" && (i.amount ?? 0) > 0);
  const spentIn = (start: number) => sum(expenses.filter((i) => inWeek(i.whenAt, start)).map((i) => i.amount!));
  const spent = spentIn(weekStart);
  const previousSpent = spentIn(addWeeks(weekStart, -1));
  const byCat = new Map<string, number>();
  for (const i of expenses.filter((e) => inWeek(e.whenAt, weekStart))) byCat.set(i.category?.trim() || OTHER, (byCat.get(i.category?.trim() || OTHER) ?? 0) + i.amount!);
  const top = [...byCat.entries()].sort((a, b) => b[1] - a[1])[0];

  const nextStart = addWeeks(weekStart, 1);
  const next = items.filter((i) => (i.type === "TASK" || i.type === "EVENT") && i.status === "OPEN" && inWeek(i.whenAt, nextStart));
  const nextWeekByDay = Array.from({ length: 7 }, (_, d) => next.filter((i) => dayIndex(i.whenAt!, nextStart) === d).length);

  const insights: Insight[] = [];
  if (stale > 0) insights.push({ kind: "STUCK", message: `${stale} việc quá hạn hơn ${STALE_DAYS} ngày: dời sang ngày khác hoặc bỏ hẳn bằng thẻ Rà soát` });
  // Judge output only once most of the week has passed.
  const weekIsOver = dayIndex(today0, weekStart) > 3;
  if (averageDone !== null && averageDone >= 3 && weekIsOver && done < averageDone * 0.5) {
    insights.push({ kind: "LOW_OUTPUT", message: `Mới xong ${done} việc, chưa bằng nửa mức trung bình ${trim(averageDone)} việc/tuần của 4 tuần trước` });
  }
  const spendHistory = [1, 2, 3, 4].map((w) => spentIn(addWeeks(weekStart, -w))).filter((n) => n > 0);
  if (spendHistory.length >= 3) {
    const avg = sum(spendHistory) / spendHistory.length;
    if (spent > avg * 1.5 && spent - avg >= 300_000) {
      insights.push({ kind: "SPEND_UP", message: `Chi ${compact(spent)} trong tuần, gấp ${trim(spent / avg)} lần mức trung bình ${compact(Math.trunc(avg))}` });
    }
  }
  const busiest = nextWeekByDay.indexOf(Math.max(...nextWeekByDay));
  if (nextWeekByDay[busiest] >= 5 && nextWeekByDay[busiest] >= 2 * (sum(nextWeekByDay) / 7)) {
    insights.push({ kind: "BUSY_DAY", message: `${WEEKDAYS[busiest]} tuần sau có ${nextWeekByDay[busiest]} việc/lịch hẹn, dày nhất tuần: cân nhắc dời bớt` });
  }

  return {
    weekStart, doneByDay, done, previousDone: doneIn(addWeeks(weekStart, -1)), averageDone, overdue: overdueTasks.length, stale,
    spent, previousSpent, topCategory: top ? { name: top[0], amount: top[1] } : null,
    recordings: recordingTimes.filter((t) => inWeek(t, weekStart)).length,
    nextWeekByDay, nextWeekTotal: sum(nextWeekByDay), insights,
  };
}
