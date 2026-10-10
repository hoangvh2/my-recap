import type { Item, Recurrence } from "./model";

/**
 * Occurrences of repeating tasks and appointments in the device's local time (a 9:00 meeting
 * stays at 9:00 across daylight saving). Port of the Android `Schedule`.
 */
const MAX_STEPS = 5_000;
const EVENT_LENGTH_MS = 60 * 60_000;

export function next(at: number, rule: Recurrence): number {
  const d = new Date(at);
  switch (rule) {
    case "DAILY":
      d.setDate(d.getDate() + 1);
      break;
    case "WEEKDAYS":
      do d.setDate(d.getDate() + 1);
      while (d.getDay() === 0 || d.getDay() === 6);
      break;
    case "WEEKLY":
      d.setDate(d.getDate() + 7);
      break;
    case "MONTHLY": {
      // Day 31 becomes the month's last day and stays there; acceptable for reminders.
      const day = d.getDate();
      d.setDate(1);
      d.setMonth(d.getMonth() + 1);
      const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
      d.setDate(Math.min(day, last));
      break;
    }
  }
  return d.getTime();
}

/** First occurrence strictly after `after`, stepping from `at`. */
export function nextAfter(at: number, rule: Recurrence, after: number): number {
  let t = next(at, rule);
  let steps = 0;
  while (t <= after && steps++ < MAX_STEPS) t = next(t, rule);
  return t;
}

export function startOfDay(t: number): number {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/**
 * The task that replaces a completed repeating task: same content, next date that is still ahead
 * (a daily task finished three days late comes back tomorrow, not three times).
 */
export function nextInstance(done: Item, newId: string, now: number): Item | null {
  if (!done.recurrence || done.whenAt === undefined || done.type !== "TASK") return null;
  const nextAt = nextAfter(done.whenAt, done.recurrence, Math.max(done.whenAt, done.allDay ? startOfDay(now) : now));
  const copy: Item = { ...done, id: newId, status: "OPEN", whenAt: nextAt, createdAt: now };
  delete copy.doneAt;
  return copy;
}

/** A repeating appointment whose occurrence is over moves to its next one. Null when nothing changes. */
export function rollForward(item: Item, now: number): Item | null {
  if (!item.recurrence || item.whenAt === undefined) return null;
  if (item.type !== "EVENT" || item.status !== "OPEN") return null;
  const end = (t: number) => (item.allDay ? startOfDay(t) + 24 * 3600_000 : t + EVENT_LENGTH_MS);
  if (end(item.whenAt) > now) return null;
  let t = item.whenAt;
  let steps = 0;
  while (end(t) <= now && steps++ < MAX_STEPS) t = next(t, item.recurrence);
  return { ...item, whenAt: t };
}
