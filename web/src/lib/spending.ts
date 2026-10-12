import type { Item } from "./model";
import { monthKey, shiftMonth, fold } from "./views";

/**
 * What the owner spent in a month: totals, a pace-based forecast, spending by category, a six-month trend and
 * alerts for things that look off. Pure functions over confirmed expense items. Port of the Android
 * `SpendingStats`, in the device's local time.
 */
export const OTHER = "Khác";
export const MAX_CATEGORIES = 5;
/** A forecast needs this many days of the month behind it; earlier than that it is noise. */
export const MIN_FORECAST_DAYS = 5;

const PACE_RATIO = 1.3;
const MIN_PACE_EXCESS = 500_000;
const SPIKE_RATIO = 1.5;
const MIN_SPIKE_EXCESS = 200_000;
const MIN_BASELINE = 8;
const MIN_LARGE = 100_000;
const MAX_ALERTS = 5;

export interface CategoryShare { name: string; amount: number; share: number }
export interface MonthTotal { key: string; total: number }
export type AlertKind = "PACE" | "CATEGORY_SPIKE" | "LARGE_EXPENSE" | "POSSIBLE_DUPLICATE";
const ALERT_ORDER: AlertKind[] = ["PACE", "CATEGORY_SPIKE", "LARGE_EXPENSE", "POSSIBLE_DUPLICATE"];
export interface Alert { kind: AlertKind; message: string; itemId?: string }

export interface SpendingReport {
  key: string;
  total: number;
  count: number;
  /** Running total by day of the month, index 0 = day 1, up to today (or the whole month when it is over). */
  cumulative: number[];
  /** The previous month's running total over its whole length. */
  previousCumulative: number[];
  /** Last month's total up to the same day of the month, or null when there was no spending then. */
  previousToDate: number | null;
  /** Month-end total if the current pace holds, or null when it is too early or the month is over. */
  forecast: number | null;
  daysInMonth: number;
  daysElapsed: number;
  categories: CategoryShare[];
  /** Up to six months ending at the month, oldest first. */
  months: MonthTotal[];
  alerts: Alert[];
}

const daysIn = (key: string) => new Date(Number(key.slice(0, 4)), Number(key.slice(5)), 0).getDate();
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
const average = (xs: number[]) => sum(xs) / xs.length;

function median(values: number[]): number {
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 === 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

const trim = (x: number) => {
  const r = Math.round(x * 10) / 10;
  return Number.isInteger(r) ? String(r) : String(r).replace(".", ",");
};

/** "850 nghìn", "1,2 triệu": short enough for a chart label. */
export function compact(amount: number): string {
  if (amount >= 1_000_000) return `${trim(amount / 1_000_000)} triệu`;
  if (amount >= 1_000) return `${trim(amount / 1_000)} nghìn`;
  return `${amount} đ`;
}

export function spendingReport(expenses: Item[], key: string, now: number): SpendingReport {
  const spent = expenses.filter((i) => i.type === "EXPENSE" && i.status !== "DRAFT" && (i.amount ?? 0) > 0 && i.whenAt !== undefined);
  const dayOf = (i: Item) => new Date(i.whenAt!).getDate();
  const monthOf = (i: Item) => monthKey(i.whenAt!);
  const nowKey = monthKey(now);
  const inMonth = spent.filter((i) => monthOf(i) === key);
  const total = sum(inMonth.map((i) => i.amount!));
  const isCurrent = key === nowKey;
  const length = daysIn(key);
  const elapsed = isCurrent ? new Date(now).getDate() : key < nowKey ? length : 0;

  const running = (list: Item[], days: number): number[] => {
    const perDay = new Array<number>(days + 1).fill(0);
    for (const e of list) if (dayOf(e) <= days) perDay[dayOf(e)] += e.amount!;
    let s = 0;
    return Array.from({ length: days }, (_, d) => (s += perDay[d + 1]));
  };
  const cumulative = running(inMonth, elapsed);
  const previous = shiftMonth(key, -1);
  const previousCumulative = running(spent.filter((i) => monthOf(i) === previous), daysIn(previous));
  const at = previousCumulative[Math.min(elapsed, previousCumulative.length) - 1];
  const previousToDate = at !== undefined && at > 0 ? at : null;

  // Forecast: what is spent so far, plus what the previous months still spent after this day (so a rent paid on
  // the 3rd is not multiplied across the month). With under two months of history, extrapolate the daily pace.
  const history = [1, 2, 3]
    .map((back) => { const m = shiftMonth(key, -back); return running(spent.filter((i) => monthOf(i) === m), daysIn(m)); })
    .filter((h) => h[h.length - 1] > 0);
  let forecast: number | null = null;
  if (isCurrent && elapsed >= MIN_FORECAST_DAYS && elapsed < length && total > 0) {
    forecast = history.length >= 2
      ? total + Math.round(average(history.map((h) => h[h.length - 1] - h[Math.min(elapsed, h.length) - 1])))
      : Math.round((total / elapsed) * length);
  }

  const months = [5, 4, 3, 2, 1, 0].map((back) => {
    const m = shiftMonth(key, -back);
    return { key: m, total: sum(spent.filter((i) => monthOf(i) === m).map((i) => i.amount!)) };
  });

  return {
    key, total, count: inMonth.length, cumulative, previousCumulative, previousToDate, forecast, daysInMonth: length, daysElapsed: elapsed,
    categories: categoriesOf(inMonth, total), months,
    alerts: alertsOf(spent, inMonth, key, isCurrent, elapsed, history, total),
  };
}

/** Largest MAX_CATEGORIES categories, everything else folded into "Khác". */
function categoriesOf(inMonth: Item[], total: number): CategoryShare[] {
  if (total <= 0) return [];
  const by = new Map<string, number>();
  for (const i of inMonth) {
    const c = i.category?.trim() || OTHER;
    by.set(c, (by.get(c) ?? 0) + i.amount!);
  }
  const named = [...by.entries()].filter(([c]) => c !== OTHER).sort((a, b) => b[1] - a[1]);
  const other = (by.get(OTHER) ?? 0) + sum(named.slice(MAX_CATEGORIES).map(([, v]) => v));
  return [...named.slice(0, MAX_CATEGORIES), ...(other > 0 ? [[OTHER, other] as [string, number]] : [])]
    .map(([name, amount]) => ({ name, amount, share: amount / total }));
}

function alertsOf(spent: Item[], inMonth: Item[], key: string, isCurrent: boolean, elapsed: number, history: number[][], total: number): Alert[] {
  const out: Alert[] = [];
  const monthOf = (i: Item) => monthKey(i.whenAt!);

  // 1. Pace: already spent clearly more by this day than the previous months had (current month only).
  if (isCurrent && elapsed >= MIN_FORECAST_DAYS && history.length >= 2) {
    const avg = average(history.map((h) => h[Math.min(elapsed, h.length) - 1]));
    if (avg > 0 && total > avg * PACE_RATIO && total - avg >= MIN_PACE_EXCESS) {
      out.push({ kind: "PACE", message: `Đang chi nhanh: ${compact(total)} sau ${elapsed} ngày, so với trung bình ${compact(Math.trunc(avg))} cùng kỳ ${history.length} tháng trước` });
    }
  }

  // 2. A category well above its own usual level.
  const prior = [1, 2, 3].map((back) => spent.filter((i) => monthOf(i) === shiftMonth(key, -back)));
  for (const name of new Set(inMonth.flatMap((i) => (i.category ? [i.category] : [])))) {
    const perMonth = prior.map((list) => sum(list.filter((i) => i.category === name).map((i) => i.amount!))).filter((v) => v > 0);
    if (perMonth.length < 2) continue;
    const avg = average(perMonth);
    const now = sum(inMonth.filter((i) => i.category === name).map((i) => i.amount!));
    if (now > avg * SPIKE_RATIO && now - avg >= MIN_SPIKE_EXCESS) {
      out.push({ kind: "CATEGORY_SPIKE", message: `${name}: ${compact(now)}, gấp ${trim(now / avg)} lần mức trung bình ${compact(Math.trunc(avg))}` });
    }
  }

  // 3. A single expense far above what the owner usually pays (median + 4 MAD of the 90 days before the month).
  const y = Number(key.slice(0, 4));
  const m = Number(key.slice(5)) - 1;
  const start = new Date(y, m, 1).getTime();
  const from = new Date(y, m, 1 - 90).getTime();
  const baseline = spent.filter((i) => i.whenAt! < start && i.whenAt! >= from).map((i) => i.amount!);
  if (baseline.length >= MIN_BASELINE) {
    const med = median(baseline);
    const mad = median(baseline.map((a) => Math.abs(a - med)));
    const limit = Math.max(med + 4 * mad, med * 2, MIN_LARGE);
    for (const e of inMonth.filter((i) => i.amount! > limit).sort((a, b) => b.amount! - a.amount!).slice(0, 2)) {
      out.push({ kind: "LARGE_EXPENSE", message: `“${e.title}” ${compact(e.amount!)} cao hơn nhiều so với mức thường gặp (${compact(Math.trunc(med))})`, itemId: e.id });
    }
  }

  // 4. The same thing entered twice the same day.
  const dup = new Map<string, Item[]>();
  for (const i of inMonth) {
    const k = `${fold(i.title)}|${i.amount}|${new Date(i.whenAt!).toDateString()}`;
    dup.set(k, [...(dup.get(k) ?? []), i]);
  }
  for (const g of [...dup.values()].filter((g) => g.length >= 2).slice(0, 2)) {
    out.push({ kind: "POSSIBLE_DUPLICATE", message: `Có thể ghi trùng: “${g[0].title}” ${compact(g[0].amount!)} xuất hiện ${g.length} lần trong cùng ngày`, itemId: g[0].id });
  }
  return out.sort((a, b) => ALERT_ORDER.indexOf(a.kind) - ALERT_ORDER.indexOf(b.kind)).slice(0, MAX_ALERTS);
}
