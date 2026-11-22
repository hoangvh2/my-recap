import { addDays, diffDays, isIsoDate, type ISODate } from "./dates";

/**
 * Days off the company gives, typed in by the person (the state holidays come from holidays.ts).
 * One entry is a day or a run of days. `yearly` entries repeat on the same solar day each year: only the month and day
 * matter, the year is where the person started, and earlier years are left alone. Entries that do not repeat belong to that one year.
 */
export interface CompanyHoliday {
  id: string;
  name: string;
  from: ISODate;
  /** Same as `from` for a single day. */
  to: ISODate;
  yearly: boolean;
}

/** One occurrence of a company holiday in a given year. */
export interface CompanyBreak {
  /** "company:<id>:<year>": what a dismissed banner and a notification are keyed by. */
  key: string;
  name: string;
  from: ISODate;
  to: ISODate;
  /** Days until the break starts; 0 when it starts today, negative while it is on. Only set by the banner and notice helpers. */
  daysUntil?: number;
}

export const COMPANY_MAX = 60;
export const COMPANY_NAME_MAX = 60;
export const COMPANY_MAX_DAYS = 31;

/** The same month and day in another year; 29/2 becomes 28/2 in a year that has none. */
function inYear(d: ISODate, y: number): ISODate {
  const m = Number(d.slice(5, 7));
  const day = Number(d.slice(8, 10));
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(Math.min(day, last)).padStart(2, "0")}`;
}

/** The occurrences that start in `year`. */
export function companyBreaksOfYear(list: readonly CompanyHoliday[], year: number): CompanyBreak[] {
  const out: CompanyBreak[] = [];
  for (const c of list) {
    const span = diffDays(c.from, c.to);
    let from: ISODate;
    if (c.yearly) {
      if (year < Number(c.from.slice(0, 4))) continue;
      from = inYear(c.from, year);
    }
    else if (Number(c.from.slice(0, 4)) === year) from = c.from;
    else continue;
    out.push({ key: `company:${c.id}:${year}`, name: c.name, from, to: addDays(from, span) });
  }
  return out.sort((a, b) => (a.from < b.from ? -1 : a.from > b.from ? 1 : 0));
}

const around = (list: readonly CompanyHoliday[], iso: ISODate): CompanyBreak[] => {
  const y = Number(iso.slice(0, 4));
  return [y - 1, y, y + 1].flatMap((n) => companyBreaksOfYear(list, n));
};

/** The company break a date falls in, if any. */
export function companyOn(iso: ISODate, list: readonly CompanyHoliday[]): CompanyBreak | null {
  return around(list, iso).find((b) => iso >= b.from && iso <= b.to) ?? null;
}

/** The first company break that has not ended yet, with the days until it starts. */
export function companyNext(today: ISODate, list: readonly CompanyHoliday[]): CompanyBreak | null {
  const b = around(list, today)
    .filter((x) => x.to >= today)
    .sort((a, c) => (a.from < c.from ? -1 : 1))[0];
  return b ? { ...b, daysUntil: diffDays(today, b.from) } : null;
}

/** The company break to put in a banner today: one that is on, or starts within `lead` days. */
export function companyBanner(today: ISODate, lead: number, dismissed: readonly string[], list: readonly CompanyHoliday[]): CompanyBreak | null {
  for (const b of around(list, today).filter((x) => x.to >= today).sort((a, c) => (a.from < c.from ? -1 : 1))) {
    const daysUntil = diffDays(today, b.from);
    if (daysUntil > lead) return null;
    if (!dismissed.includes(b.key)) return { ...b, daysUntil };
  }
  return null;
}

/** "Thứ…" is the screen's job; this is the span: "29/4" or "29/4 đến 2/5". */
export function companySpan(b: Pick<CompanyBreak, "from" | "to">): string {
  const dm = (d: ISODate) => `${Number(d.slice(8, 10))}/${Number(d.slice(5, 7))}`;
  return b.from === b.to ? dm(b.from) : `${dm(b.from)} đến ${dm(b.to)}`;
}

/** Why a draft cannot be saved, or null. */
export function companyProblem(c: { name: string; from: string; to: string }): string | null {
  if (!c.name.trim()) return "Nhập tên kỳ nghỉ.";
  if (!isIsoDate(c.from)) return "Chọn ngày bắt đầu.";
  if (!isIsoDate(c.to)) return "Chọn ngày kết thúc.";
  if (c.to < c.from) return "Ngày kết thúc phải sau ngày bắt đầu.";
  if (diffDays(c.from, c.to) + 1 > COMPANY_MAX_DAYS) return `Một kỳ nghỉ dài tối đa ${COMPANY_MAX_DAYS} ngày.`;
  return null;
}

/** Reads a stored list, dropping anything malformed so a bad value never breaks the calendar. */
export function parseCompany(raw: unknown): CompanyHoliday[] {
  if (!Array.isArray(raw)) return [];
  const out: CompanyHoliday[] = [];
  for (const p of raw as Record<string, unknown>[]) {
    if (!p || typeof p.id !== "string" || !p.id || typeof p.name !== "string") continue;
    if (companyProblem({ name: p.name, from: p.from as string, to: p.to as string })) continue;
    if (out.some((x) => x.id === p.id)) continue;
    out.push({ id: p.id.slice(0, 40), name: p.name.trim().slice(0, COMPANY_NAME_MAX), from: p.from as ISODate, to: p.to as ISODate, yearly: p.yearly === true });
    if (out.length >= COMPANY_MAX) break;
  }
  return out;
}
