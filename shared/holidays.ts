import { addDays, diffDays, isIsoDate, toDay, type ISODate } from "./dates";
import { lunarToSolar } from "./lunar";

/**
 * Vietnamese public holidays. The days come from the labour code (Bộ luật Lao động 2019, Điều 112) and the rule that a
 * holiday on a weekend is made up on the next working day (Điều 111); that is computed here for any year.
 * What changes year by year is only the official schedule (adjacent day of Quốc khánh, swapped working days, longer Tết),
 * which lives in shared/holidays-vn.json as per-year overrides and can be updated without a new app release.
 */
export type HolidayId = "tet-duong-lich" | "tet-nguyen-dan" | "gio-to" | "30-4-1-5" | "quoc-khanh";

export const HOLIDAY_NAME: Record<HolidayId, string> = {
  "tet-duong-lich": "Tết Dương lịch",
  "tet-nguyen-dan": "Tết Nguyên đán",
  "gio-to": "Giỗ Tổ Hùng Vương",
  "30-4-1-5": "Lễ 30/4 và 1/5",
  "quoc-khanh": "Quốc khánh 2/9",
};
const IDS = Object.keys(HOLIDAY_NAME) as HolidayId[];

/** "rule": computed from the law. "announced": an official schedule was published for that year (see `source`). */
export type HolidayStatus = "rule" | "announced";

/** An entry of holidays-vn.json: the schedule announced for one holiday of one year. */
export interface HolidayOverride {
  id: HolidayId;
  year: number;
  from: ISODate;
  to: ISODate;
  /** Working days swapped in to pay for the extra days off (e.g. a Saturday). */
  workdays?: ISODate[];
  source?: string;
}

export interface Holiday {
  id: HolidayId;
  name: string;
  year: number;
  /** First and last day of the whole break, weekends included. */
  from: ISODate;
  to: ISODate;
  /** The days the law names (the day of the holiday itself, weekend or not). */
  legal: ISODate[];
  /** The date the holiday is about (Mùng 1 Tết, 10/3 Âm, 2/9...). */
  main: ISODate;
  workdays: ISODate[];
  status: HolidayStatus;
  source?: string;
}

/** 0 = Sunday ... 6 = Saturday. */
export const weekday = (d: ISODate): number => (((toDay(d) + 4) % 7) + 7) % 7;
const weekend = (d: ISODate): boolean => weekday(d) === 0 || weekday(d) === 6;

/** The days the law gives for a holiday, and the date it is about. Null when the lunar date cannot be found. */
function legalDays(id: HolidayId, y: number): { days: ISODate[]; main: ISODate } | null {
  switch (id) {
    case "tet-duong-lich":
      return { days: [`${y}-01-01`], main: `${y}-01-01` };
    case "tet-nguyen-dan": {
      // The last day of the old year and the first four of the new one.
      const tet = lunarToSolar(y, 1, 1);
      if (!tet) return null;
      return { days: [-1, 0, 1, 2, 3].map((n) => addDays(tet, n)), main: tet };
    }
    case "gio-to": {
      const d = lunarToSolar(y, 3, 10);
      return d ? { days: [d], main: d } : null;
    }
    case "30-4-1-5":
      return { days: [`${y}-04-30`, `${y}-05-01`], main: `${y}-04-30` };
    case "quoc-khanh":
      // Two days: 2/9 and one next to it. Which one is announced each year, so the rule only guesses the day after.
      return { days: [`${y}-09-02`, `${y}-09-03`], main: `${y}-09-02` };
  }
}

/** The break as the law implies it: legal days, made up on the next working day when on a weekend, joined to touching weekends. */
function ruleBreak(days: ISODate[]): { from: ISODate; to: ISODate } {
  const off = new Set(days);
  for (const d of days) {
    if (!weekend(d)) continue;
    let n = addDays(d, 1);
    while (weekend(n) || off.has(n)) n = addDays(n, 1);
    off.add(n);
  }
  const sorted = [...off].sort();
  let from = sorted[0] as ISODate;
  let to = sorted[sorted.length - 1] as ISODate;
  while (weekend(addDays(from, -1))) from = addDays(from, -1);
  while (weekend(addDays(to, 1))) to = addDays(to, 1);
  return { from, to };
}

export function holidaysOfYear(year: number, overrides: readonly HolidayOverride[] = []): Holiday[] {
  const out: Holiday[] = [];
  for (const id of IDS) {
    const legal = legalDays(id, year);
    if (!legal) continue;
    const o = overrides.find((x) => x.id === id && x.year === year);
    const b = o ? { from: o.from, to: o.to } : ruleBreak(legal.days);
    out.push({
      id,
      name: HOLIDAY_NAME[id],
      year,
      from: b.from,
      to: b.to,
      legal: legal.days,
      main: legal.main,
      workdays: o?.workdays ?? [],
      status: o ? "announced" : "rule",
      ...(o?.source ? { source: o.source } : {}),
    });
  }
  return out.sort((a, b) => (a.from < b.from ? -1 : 1));
}

export const holidayDays = (h: Holiday): number => diffDays(h.from, h.to) + 1;

/** Which holiday a date belongs to, and whether the day is named by the law or is an extra day off (made up, swapped, extended Tết). */
export function holidayOn(iso: ISODate, overrides: readonly HolidayOverride[] = []): { holiday: Holiday; kind: "legal" | "extra" | "weekend" } | null {
  const y = Number(iso.slice(0, 4));
  for (const h of [...holidaysOfYear(y - 1, overrides), ...holidaysOfYear(y, overrides), ...holidaysOfYear(y + 1, overrides)]) {
    if (iso < h.from || iso > h.to) continue;
    return { holiday: h, kind: h.legal.includes(iso) ? "legal" : weekend(iso) ? "weekend" : "extra" };
  }
  return null;
}

export interface HolidayAhead {
  holiday: Holiday;
  /** Days until the break starts; 0 when it starts today, negative while it is on. */
  daysUntil: number;
}

/** The first break that has not ended yet. */
export function nextHoliday(today: ISODate, overrides: readonly HolidayOverride[] = []): HolidayAhead | null {
  const y = Number(today.slice(0, 4));
  const all = [y - 1, y, y + 1].flatMap((n) => holidaysOfYear(n, overrides)).filter((h) => h.to >= today).sort((a, b) => (a.from < b.from ? -1 : 1));
  const h = all[0];
  return h ? { holiday: h, daysUntil: diffDays(today, h.from) } : null;
}

/** Days of notice before a break: Tết needs the longest because people travel. */
export function leadFor(id: HolidayId, lead: number): number {
  return id === "tet-nguyen-dan" ? Math.max(14, lead) : lead;
}

/** The break to put in a banner today: one that is on, or starts within the notice. `dismissed` holds keys from `bannerKey`. */
export function bannerFor(today: ISODate, lead: number, dismissed: readonly string[], overrides: readonly HolidayOverride[] = []): HolidayAhead | null {
  const y = Number(today.slice(0, 4));
  const list = [y - 1, y, y + 1]
    .flatMap((n) => holidaysOfYear(n, overrides))
    .filter((h) => h.to >= today)
    .sort((a, b) => (a.from < b.from ? -1 : 1));
  for (const h of list) {
    const daysUntil = diffDays(today, h.from);
    if (daysUntil > leadFor(h.id, lead)) return null;
    if (!dismissed.includes(bannerKey(h))) return { holiday: h, daysUntil };
  }
  return null;
}

export const bannerKey = (h: Pick<Holiday, "id" | "year">): string => `${h.id}:${h.year}`;

/** "Thứ Sáu 16/4 (10/3 Âm)": nothing here, the screens format dates themselves. Returns the lunar date text of the main day. */
export function lunarOfMain(h: Holiday): string | null {
  if (h.id === "gio-to") return "10/3 Âm";
  if (h.id === "tet-nguyen-dan") return "mùng 1 Âm";
  return null;
}

/** Reads holidays-vn.json, dropping anything malformed so a bad file never breaks the app. */
export function parseOverrides(raw: unknown): HolidayOverride[] {
  const list = (raw as { periods?: unknown } | null)?.periods;
  if (!Array.isArray(list)) return [];
  const out: HolidayOverride[] = [];
  for (const p of list as Record<string, unknown>[]) {
    if (!p || !IDS.includes(p.id as HolidayId) || !Number.isInteger(p.year)) continue;
    if (!isIsoDate(p.from) || !isIsoDate(p.to) || p.from > p.to || diffDays(p.from, p.to) > 16) continue;
    const workdays = Array.isArray(p.workdays) ? p.workdays.filter((d): d is ISODate => isIsoDate(d)).slice(0, 8) : [];
    out.push({
      id: p.id as HolidayId,
      year: p.year as number,
      from: p.from,
      to: p.to,
      ...(workdays.length ? { workdays } : {}),
      ...(typeof p.source === "string" ? { source: p.source.slice(0, 200) } : {}),
    });
  }
  return out;
}

