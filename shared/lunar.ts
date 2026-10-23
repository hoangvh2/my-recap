import { fromDay, toDay, type ISODate } from "./dates";

/**
 * Vietnamese lunisolar calendar (UTC+7), computed on the device: no table, no network.
 * Astronomical method of Hồ Ngọc Đức (new moon after Meeus, sun longitude by 30° terms). The Kotlin port in
 * core/.../Lunar.kt must give identical results; both are tested against shared/lunar-vectors.json.
 * Valid for 1900 to 2199 (outside it the functions return null).
 */
export interface LunarDate {
  day: number;
  month: number;
  year: number;
  /** True in a leap month (tháng nhuận). */
  leap: boolean;
}

export const LUNAR_MIN_YEAR = 1900;
export const LUNAR_MAX_YEAR = 2199;

const TZ = 7;
const SYNODIC = 29.530588853;
const EPOCH_JD_OF_DAY0 = 2440588; // Julian day number of 1970-01-01
const floor = Math.floor;

const jdOf = (iso: ISODate): number => toDay(iso) + EPOCH_JD_OF_DAY0;
const isoOfJd = (jd: number): ISODate => fromDay(jd - EPOCH_JD_OF_DAY0);

function newMoon(k: number): number {
  const T = k / 1236.85;
  const T2 = T * T;
  const T3 = T2 * T;
  const dr = Math.PI / 180;
  let jd1 = 2415020.75933 + SYNODIC * k + 0.0001178 * T2 - 0.000000155 * T3;
  jd1 += 0.00033 * Math.sin((166.56 + 132.87 * T - 0.009173 * T2) * dr);
  const M = 359.2242 + 29.10535608 * k - 0.0000333 * T2 - 0.00000347 * T3;
  const Mpr = 306.0253 + 385.81691806 * k + 0.0107306 * T2 + 0.00001236 * T3;
  const F = 21.2964 + 390.67050646 * k - 0.0016528 * T2 - 0.00000239 * T3;
  let c1 = (0.1734 - 0.000393 * T) * Math.sin(M * dr) + 0.0021 * Math.sin(2 * dr * M);
  c1 += -0.4068 * Math.sin(Mpr * dr) + 0.0161 * Math.sin(dr * 2 * Mpr) - 0.0004 * Math.sin(dr * 3 * Mpr);
  c1 += 0.0104 * Math.sin(dr * 2 * F) - 0.0051 * Math.sin(dr * (M + Mpr)) - 0.0074 * Math.sin(dr * (M - Mpr));
  c1 += 0.0004 * Math.sin(dr * (2 * F + M)) - 0.0004 * Math.sin(dr * (2 * F - M)) - 0.0006 * Math.sin(dr * (2 * F + Mpr));
  c1 += 0.001 * Math.sin(dr * (2 * F - Mpr)) + 0.0005 * Math.sin(dr * (2 * Mpr + M));
  const dt = T < -11 ? 0.001 + 0.000839 * T + 0.0002261 * T2 - 0.00000845 * T3 - 0.000000081 * T * T3 : -0.000278 + 0.000265 * T + 0.000262 * T2;
  return jd1 + c1 - dt;
}

/** Sun longitude at local midnight of the day, as a 30° sector 0..11. */
function sunSector(jdn: number): number {
  const T = (jdn - 0.5 - TZ / 24 - 2451545.0) / 36525;
  const T2 = T * T;
  const dr = Math.PI / 180;
  const M = 357.5291 + 35999.0503 * T - 0.0001559 * T2 - 0.00000048 * T * T2;
  const L0 = 280.46645 + 36000.76983 * T + 0.0003032 * T2;
  let dl = (1.9146 - 0.004817 * T - 0.000014 * T2) * Math.sin(dr * M);
  dl += (0.019993 - 0.000101 * T) * Math.sin(dr * 2 * M) + 0.00029 * Math.sin(dr * 3 * M);
  let L = (L0 + dl) * dr;
  L -= Math.PI * 2 * floor(L / (Math.PI * 2));
  return floor((L / Math.PI) * 6);
}

const newMoonDay = (k: number): number => floor(newMoon(k) + 0.5 + TZ / 24);

/** The day (JDN) the 11th lunar month, which holds the winter solstice, starts in solar year `yy`. */
function month11Start(yy: number): number {
  const off = jdOf(`${String(yy).padStart(4, "0")}-12-31`) - 2415021;
  const k = floor(off / SYNODIC);
  let nm = newMoonDay(k);
  if (sunSector(nm) >= 9) nm = newMoonDay(k - 1);
  return nm;
}

/** Offset (in months from the 11th) of the leap month in a 13-month year starting at `a11`. */
function leapOffset(a11: number): number {
  const k = floor((a11 - 2415021.076998695) / SYNODIC + 0.5);
  let i = 1;
  let arc = sunSector(newMoonDay(k + i));
  let last: number;
  do {
    last = arc;
    i++;
    arc = sunSector(newMoonDay(k + i));
  } while (arc !== last && i < 14);
  return i - 1;
}

const inRange = (y: number): boolean => y >= LUNAR_MIN_YEAR && y <= LUNAR_MAX_YEAR;

export function solarToLunar(iso: ISODate): LunarDate | null {
  const yy = Number(iso.slice(0, 4));
  if (!inRange(yy)) return null;
  const dayNumber = jdOf(iso);
  // The estimate of the lunation can be one off when a new moon falls just after midnight, so settle it by checking.
  let k = floor((dayNumber - 2415021.076998695) / SYNODIC);
  while (newMoonDay(k + 1) <= dayNumber) k++;
  while (newMoonDay(k) > dayNumber) k--;
  const monthStart = newMoonDay(k);
  let a11 = month11Start(yy);
  let b11 = a11;
  let year: number;
  if (a11 >= monthStart) {
    year = yy;
    a11 = month11Start(yy - 1);
  } else {
    year = yy + 1;
    b11 = month11Start(yy + 1);
  }
  const day = dayNumber - monthStart + 1;
  const diff = floor((monthStart - a11) / 29);
  let leap = false;
  let month = diff + 11;
  if (b11 - a11 > 365) {
    const lo = leapOffset(a11);
    if (diff >= lo) {
      month = diff + 10;
      if (diff === lo) leap = true;
    }
  }
  if (month > 12) month -= 12;
  if (month >= 11 && diff < 4) year -= 1;
  return { day, month, year, leap };
}

/** Where a lunar month starts (ISO date) and how many days it has (29 or 30); null when that month does not exist. */
export function lunarMonth(year: number, month: number, leap = false): { start: ISODate; days: number } | null {
  if (!inRange(year) || month < 1 || month > 12) return null;
  let a11: number;
  let b11: number;
  if (month < 11) {
    a11 = month11Start(year - 1);
    b11 = month11Start(year);
  } else {
    a11 = month11Start(year);
    b11 = month11Start(year + 1);
  }
  const k = floor(0.5 + (a11 - 2415021.076998695) / SYNODIC);
  let off = month - 11;
  if (off < 0) off += 12;
  if (b11 - a11 > 365) {
    const lo = leapOffset(a11);
    let leapMonth = lo - 2;
    if (leapMonth < 0) leapMonth += 12;
    if (leap && month !== leapMonth) return null;
    if (leap || off >= lo) off += 1;
  } else if (leap) {
    return null;
  }
  const start = newMoonDay(k + off);
  return { start: isoOfJd(start), days: newMoonDay(k + off + 1) - start };
}

/** The solar date of a lunar date. Null when it does not exist (no such leap month, or day 30 of a 29-day month). */
export function lunarToSolar(year: number, month: number, day: number, leap = false): ISODate | null {
  const m = lunarMonth(year, month, leap);
  if (!m || day < 1 || day > m.days) return null;
  return isoOfJd(jdOf(m.start) + day - 1);
}

/** Like lunarToSolar but day 30 of a 29-day month becomes its last day (the rule for yearly/monthly lunar repeats). */
export function lunarToSolarClamped(year: number, month: number, day: number, leap = false): ISODate | null {
  const m = lunarMonth(year, month, leap);
  if (!m || day < 1) return null;
  return isoOfJd(jdOf(m.start) + Math.min(day, m.days) - 1);
}

/** Next lunar month after (year, month, leap): a leap month is followed by the regular next month. */
export function nextLunarMonth(d: Pick<LunarDate, "year" | "month" | "leap">): { year: number; month: number; leap: boolean } {
  // After month m comes its leap twin when the year has one, else m+1. The twin exists when lunarMonth(year, m, true) does.
  if (!d.leap && lunarMonth(d.year, d.month, true)) return { year: d.year, month: d.month, leap: true };
  return d.month === 12 ? { year: d.year + 1, month: 1, leap: false } : { year: d.year, month: d.month + 1, leap: false };
}

/** "6/3" or "1/3" (a leap month gets "n"), the form shown next to a solar date. */
export function lunarShort(l: LunarDate): string {
  return `${l.day}/${l.month}${l.leap ? "n" : ""}`;
}

/**
 * The date of the next occurrence of a lunar repeat after the solar date `iso`.
 * Yearly: same lunar day and month next lunar year (a date in a leap month moves to the regular month).
 * Monthly: same lunar day next lunar month (leap months count as months). Day 30 of a 29-day month becomes day 29.
 * Null when `iso` is outside the supported years.
 */
export function nextLunarOccurrence(iso: ISODate, rule: "LUNAR_MONTHLY" | "LUNAR_YEARLY"): ISODate | null {
  const l = solarToLunar(iso);
  if (!l) return null;
  if (rule === "LUNAR_YEARLY") return lunarToSolarClamped(l.year + 1, l.month, l.day, false);
  const n = nextLunarMonth(l);
  return lunarToSolarClamped(n.year, n.month, l.day, n.leap);
}
