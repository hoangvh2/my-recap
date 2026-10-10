/**
 * Calendar dates as plain "YYYY-MM-DD" strings. Licences, warranties and reminders are about days,
 * not instants, so doing the arithmetic on whole days keeps it identical in every time zone and on
 * the server.
 */
export type ISODate = string;

const RE = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isIsoDate(s: unknown): s is ISODate {
  if (typeof s !== "string") return false;
  const m = RE.exec(s);
  if (!m) return false;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const t = new Date(Date.UTC(y, mo - 1, d));
  return t.getUTCFullYear() === y && t.getUTCMonth() === mo - 1 && t.getUTCDate() === d;
}

/** Whole days since 1970-01-01. */
export function toDay(d: ISODate): number {
  const m = RE.exec(d);
  if (!m) throw new Error(`Not a date: ${d}`);
  return Math.floor(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) / 86_400_000);
}

export function fromDay(n: number): ISODate {
  return new Date(n * 86_400_000).toISOString().slice(0, 10);
}

export const addDays = (d: ISODate, n: number): ISODate => fromDay(toDay(d) + n);

/** Days from a to b (positive when b is later). */
export const diffDays = (a: ISODate, b: ISODate): number => toDay(b) - toDay(a);

/** Adds calendar months; 31 Jan + 1 month = 28/29 Feb, never rolls into the next month. */
export function addMonths(d: ISODate, n: number): ISODate {
  const [y, m, day] = d.split("-").map(Number);
  const total = y * 12 + (m - 1) + n;
  const ny = Math.floor(total / 12);
  const nm = total - ny * 12;
  const last = new Date(Date.UTC(ny, nm + 1, 0)).getUTCDate();
  return `${String(ny).padStart(4, "0")}-${String(nm + 1).padStart(2, "0")}-${String(Math.min(day, last)).padStart(2, "0")}`;
}

/** Whole months from a to b, rounded to the nearest month (a=2026-03-16, b=2027-03-16 → 12). */
export function monthsBetween(a: ISODate, b: ISODate): number {
  const [y1, m1] = a.split("-").map(Number);
  const [y2, m2] = b.split("-").map(Number);
  const whole = (y2 - y1) * 12 + (m2 - m1);
  const rest = diffDays(addMonths(a, whole), b);
  return Math.round(whole + rest / 30);
}

/** The date in a given IANA time zone (or the machine's zone when `zone` is omitted). */
export function isoDateIn(ms: number, zone?: string): ISODate {
  if (!zone) {
    const d = new Date(ms);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  }
  return new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(ms);
}

export const monthKeyOf = (d: ISODate): string => d.slice(0, 7);

export function shiftMonthKey(key: string, delta: number): string {
  return addMonths(`${key}-01`, delta).slice(0, 7);
}

/** 2026-03-16 → "16/03/2026" */
export function formatDate(d: ISODate): string {
  const [y, m, day] = d.split("-");
  return `${day}/${m}/${y}`;
}

/** "16/03", or "16/03/2027" when it is not in `now`'s year. */
export function formatDateShort(d: ISODate, today: ISODate): string {
  const [y, m, day] = d.split("-");
  return y === today.slice(0, 4) ? `${day}/${m}` : `${day}/${m}/${y}`;
}

/**
 * What people type or paste from Excel: 16/3/2027, 16-03-2027, 16.03.27, 2027-03-16.
 * Day first, as in Vietnam. Two-digit years mean 20xx. Null when it is not a real date.
 */
export function parseDateLoose(text: string): ISODate | null {
  const s = text.trim();
  if (!s) return null;
  let y: number, m: number, d: number;
  let r = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[T\s].*)?$/.exec(s);
  if (r) {
    [y, m, d] = [Number(r[1]), Number(r[2]), Number(r[3])];
  } else {
    r = /^(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})(?:\s.*)?$/.exec(s);
    if (!r) return null;
    [d, m, y] = [Number(r[1]), Number(r[2]), Number(r[3])];
    if (r[3].length === 2) y += 2000;
  }
  const iso = `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
  return isIsoDate(iso) ? iso : null;
}
