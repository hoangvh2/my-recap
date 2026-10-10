import { addDays, addMonths, diffDays, isoDateIn, monthKeyOf, monthsBetween, shiftMonthKey, type ISODate } from "./dates";
import { isClosed, stageRank, type Customer, type Item, type License, type Prefs } from "./model";

/**
 * The renewal playbook. Nobody has to create or remember tasks: from a licence's stage and the number
 * of days left, this says what to do next and when it fell due. All of it is computed on the fly, so
 * it can never go out of step with the data, and the same code feeds the app and the calendar feed.
 */

export type NudgeKind = "EXPIRED" | "URGENT" | "CONTRACT" | "QUOTE" | "FOLLOW_UP" | "ASK";
export type Severity = "danger" | "warn" | "info";

export interface Ctx {
  today: ISODate;
  prefs: Prefs;
  /** Time zone for turning the stage timestamp into a date; the machine's zone when omitted. */
  zone?: string;
}

export interface Nudge {
  licenseId: string;
  customerId: string;
  kind: NudgeKind;
  severity: Severity;
  /** The day it became (or becomes) due. */
  dueDate: ISODate;
  /** Days since it fell due (0 on the day); negative when still ahead. */
  overdueDays: number;
  /** Days until the licence ends; negative once it has ended. */
  daysToEnd: number;
  action: string;
}

export const NUDGE_ACTION: Record<NudgeKind, string> = {
  ASK: "Hỏi khách có gia hạn không",
  QUOTE: "Gửi báo giá gia hạn",
  CONTRACT: "Chốt và gửi hợp đồng",
  URGENT: "Sắp hết hạn mà chưa chốt",
  EXPIRED: "Đã hết hạn mà chưa chốt: liên hệ lại hoặc đóng lại",
  FOLLOW_UP: "Hỏi lại xem khách đã phản hồi chưa",
};

const SEVERITY: Record<NudgeKind, Severity> = { EXPIRED: "danger", URGENT: "danger", CONTRACT: "warn", QUOTE: "warn", FOLLOW_UP: "warn", ASK: "info" };
const SEVERITY_ORDER: Record<Severity, number> = { danger: 0, warn: 1, info: 2 };
/** First applicable wins. */
const PRIORITY: NudgeKind[] = ["EXPIRED", "URGENT", "CONTRACT", "QUOTE", "FOLLOW_UP", "ASK"];
/** The milestone kinds, in the order of Prefs.milestones. */
const MILESTONES: NudgeKind[] = ["ASK", "QUOTE", "CONTRACT", "URGENT"];
/** A step is still needed while the stage has not reached it. */
const NEEDED_UNTIL: Record<string, number> = { ASK: 0, QUOTE: 1, CONTRACT: 2, URGENT: 3 };

export const isOpenLicense = (l: License): boolean => l.status === "OPEN" && !isClosed(l.stage);

/** Length of the term in months, from the explicit value or the dates. */
export function termOf(l: License): number | undefined {
  if (l.termMonths) return l.termMonths;
  return l.startDate ? Math.max(1, monthsBetween(l.startDate, addDays(l.endDate, 1))) : undefined;
}

/** The four reminder offsets that apply to this licence: short terms use the shorter rhythm. */
export function offsetsFor(l: License, prefs: Prefs): number[] {
  const t = termOf(l);
  return t !== undefined && t <= prefs.shortTermMonths ? prefs.shortMilestones : prefs.milestones;
}

/** The day to be finished by: the end date, or earlier when the contract needs notice. */
export const deadlineOf = (l: License): ISODate => addDays(l.endDate, -(l.noticeDays ?? 0));

const stageDate = (l: License, ctx: Ctx): ISODate => isoDateIn(l.stageAt, ctx.zone);

function make(l: License, kind: NudgeKind, dueDate: ISODate, ctx: Ctx): Nudge {
  return {
    licenseId: l.id,
    customerId: l.customerId,
    kind,
    severity: SEVERITY[kind],
    dueDate,
    overdueDays: diffDays(dueDate, ctx.today),
    daysToEnd: diffDays(ctx.today, l.endDate),
    action: NUDGE_ACTION[kind],
  };
}

/** The date a milestone falls on for this licence. */
export function milestoneDate(l: License, kind: NudgeKind, prefs: Prefs): ISODate {
  const i = MILESTONES.indexOf(kind);
  return addDays(deadlineOf(l), -offsetsFor(l, prefs)[i]);
}

/** What needs doing today for this licence, or null when nothing (closed, draft, snoozed or all on track). */
export function nudgeFor(l: License, ctx: Ctx): Nudge | null {
  if (!isOpenLicense(l)) return null;
  if (l.snoozeUntil && l.snoozeUntil > ctx.today) return null;
  const rank = stageRank(l.stage);
  const found: Partial<Record<NudgeKind, ISODate>> = {};
  if (diffDays(ctx.today, l.endDate) < 0) found.EXPIRED = l.endDate;
  for (const k of MILESTONES) {
    if (rank > NEEDED_UNTIL[k]) continue;
    const date = milestoneDate(l, k, ctx.prefs);
    if (date <= ctx.today) found[k] = date;
  }
  if ((l.stage === "ASKED" || l.stage === "QUOTED") && ctx.prefs.staleDays > 0) {
    const due = addDays(stageDate(l, ctx), ctx.prefs.staleDays);
    if (due <= ctx.today) found.FOLLOW_UP = due;
  }
  for (const k of PRIORITY) if (found[k]) return make(l, k, found[k]!, ctx);
  return null;
}

/** The next milestone still ahead if nothing changes, e.g. {QUOTE, 2027-01-12}. */
export function nextMilestone(l: License, ctx: Ctx): { kind: NudgeKind; date: ISODate; action: string } | null {
  if (!isOpenLicense(l)) return null;
  const rank = stageRank(l.stage);
  let best: { kind: NudgeKind; date: ISODate } | null = null;
  for (const k of MILESTONES) {
    if (rank > NEEDED_UNTIL[k]) continue;
    const date = milestoneDate(l, k, ctx.prefs);
    if (date > ctx.today && (!best || date < best.date)) best = { kind: k, date };
  }
  return best ? { ...best, action: NUDGE_ACTION[best.kind] } : null;
}

/** Every milestone ahead for the current stage, plus the end date: what goes on a calendar. */
export function calendarMilestones(l: License, ctx: Ctx): { kind: NudgeKind | "END"; date: ISODate; action: string }[] {
  if (!isOpenLicense(l)) return [];
  const rank = stageRank(l.stage);
  const out: { kind: NudgeKind | "END"; date: ISODate; action: string }[] = [];
  for (const k of MILESTONES) {
    if (rank > NEEDED_UNTIL[k]) continue;
    const date = milestoneDate(l, k, ctx.prefs);
    if (date >= ctx.today) out.push({ kind: k, date, action: NUDGE_ACTION[k] });
  }
  if (l.endDate >= ctx.today) out.push({ kind: "END", date: l.endDate, action: "Hết hạn" });
  return out.sort((a, b) => a.date.localeCompare(b.date));
}

const bySeverity = (a: Nudge, b: Nudge) =>
  SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] || a.dueDate.localeCompare(b.dueDate) || a.daysToEnd - b.daysToEnd;

/** Everything due now, most urgent first. */
export function dueNudges(licenses: readonly License[], ctx: Ctx): Nudge[] {
  return licenses.map((l) => nudgeFor(l, ctx)).filter((n): n is Nudge => n !== null).sort(bySeverity);
}

/** Milestones that will fall due within the next `days` days. */
export function upcomingNudges(licenses: readonly License[], ctx: Ctx, days = 14): Nudge[] {
  const out: Nudge[] = [];
  for (const l of licenses) {
    if (nudgeFor(l, ctx)) continue; // already in the "due" list
    const n = nextMilestone(l, ctx);
    if (n && diffDays(ctx.today, n.date) <= days) out.push(make(l, n.kind, n.date, ctx));
  }
  return out.sort((a, b) => a.dueDate.localeCompare(b.dueDate));
}

export type Urgency = "danger" | "warn" | "info" | "calm" | "done";

/** How worried to look about a licence: the colour of its badge. */
export function urgencyOf(l: License, ctx: Ctx): Urgency {
  if (l.status !== "OPEN" || isClosed(l.stage)) return "done";
  const n = nudgeFor({ ...l, snoozeUntil: undefined }, ctx);
  return n ? n.severity : "calm";
}

/** "Còn 12 ngày", "Hết hạn hôm nay", "Quá hạn 5 ngày". */
export function daysLeftText(daysToEnd: number): string {
  if (daysToEnd > 0) return daysToEnd >= 60 && daysToEnd % 30 === 0 ? `Còn ${daysToEnd / 30} tháng` : `Còn ${daysToEnd} ngày`;
  if (daysToEnd === 0) return "Hết hạn hôm nay";
  return `Quá hạn ${-daysToEnd} ngày`;
}

/** The period that follows this one: starts the next day and lasts as long as the last one did (12 months when unknown). */
export function nextPeriod(l: Pick<License, "startDate" | "endDate" | "termMonths">): { startDate: ISODate; endDate: ISODate; termMonths: number } {
  const startDate = addDays(l.endDate, 1);
  const termMonths = l.termMonths ?? (l.startDate ? Math.max(1, monthsBetween(l.startDate, startDate)) : 12);
  return { startDate, endDate: addDays(addMonths(startDate, termMonths), -1), termMonths };
}

// ---------------------------------------------------------------- overview numbers

export interface MonthForecast {
  month: string;
  open: { count: number; value: number };
  renewed: { count: number; value: number };
  lost: { count: number; value: number };
}

/** For each month: how many licences end, and how they stand (still open, renewed, lost). */
export function monthlyForecast(licenses: readonly License[], fromMonth: string, months: number): MonthForecast[] {
  const out: MonthForecast[] = [];
  for (let i = 0; i < months; i++) {
    const month = shiftMonthKey(fromMonth, i);
    const row: MonthForecast = { month, open: { count: 0, value: 0 }, renewed: { count: 0, value: 0 }, lost: { count: 0, value: 0 } };
    for (const l of licenses) {
      if (l.status !== "OPEN" || monthKeyOf(l.endDate) !== month) continue;
      const bucket = l.stage === "RENEWED" ? row.renewed : l.stage === "LOST" ? row.lost : row.open;
      bucket.count++;
      bucket.value += l.value ?? 0;
    }
    out.push(row);
  }
  return out;
}

/** The soonest end date among a customer's licences that are still open. */
export function nextExpiryOf(licenses: readonly License[]): ISODate | null {
  const dates = licenses.filter(isOpenLicense).map((l) => l.endDate).sort();
  return dates[0] ?? null;
}

/** Total value of licences still open for renewal. */
export const openValueOf = (licenses: readonly License[]): number => licenses.filter(isOpenLicense).reduce((s, l) => s + (l.value ?? 0), 0);

/** When anything last happened with this customer: a note, task, appointment, stage change or edit. */
export function lastTouchOf(c: Customer, licenses: readonly License[], items: readonly Item[]): number {
  let t = Math.max(c.createdAt, c.updatedAt);
  for (const l of licenses) t = Math.max(t, l.stageAt, l.updatedAt);
  for (const i of items) t = Math.max(t, i.createdAt, i.doneAt ?? 0);
  return t;
}

/** Customers with licences to look after that nobody has touched for a long time, most valuable first. */
export function quietCustomers(
  customers: readonly Customer[],
  licenses: readonly License[],
  items: readonly Item[],
  nowMs: number,
  quietDays: number,
): { customer: Customer; daysQuiet: number; value: number }[] {
  const out: { customer: Customer; daysQuiet: number; value: number }[] = [];
  for (const c of customers) {
    if (c.status !== "OPEN") continue;
    const ls = licenses.filter((l) => l.customerId === c.id);
    if (!ls.some(isOpenLicense)) continue;
    const days = Math.floor((nowMs - lastTouchOf(c, ls, items.filter((i) => i.customerId === c.id))) / 86_400_000);
    if (days >= quietDays) out.push({ customer: c, daysQuiet: days, value: openValueOf(ls) });
  }
  return out.sort((a, b) => b.value - a.value || b.daysQuiet - a.daysQuiet);
}
