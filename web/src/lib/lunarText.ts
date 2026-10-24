import { holidayDays, holidayOn, lunarOfMain, type Holiday, type HolidayOverride } from "../../../shared/holidays";
import { solarToLunar, type LunarDate } from "../../../shared/lunar";
import type { ISODate } from "../../../shared/dates";
import { toDateInput } from "./format";

const WEEKDAY = ["Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"];

export const isoOf = (t: number): ISODate => toDateInput(t);
export const lunarAt = (t: number): LunarDate | null => solarToLunar(isoOf(t));

/** The lunar date in short form for a chip: "6/3 Âm", "1/3 Âm" (a leap month: "6/3n Âm"). */
export function lunarChip(l: LunarDate | null): string {
  return l ? `${l.day}/${l.month}${l.leap ? "n" : ""} Âm` : "";
}

/** "Mùng 6 tháng 3 Âm", "Rằm tháng 3 Âm", "Mùng 1 tháng 3 Âm", "22 tháng 3 nhuận Âm". */
export function lunarFull(l: LunarDate): string {
  const day = l.day === 15 ? "Rằm" : l.day <= 10 ? `Mùng ${l.day}` : String(l.day);
  return `${day} tháng ${l.month}${l.leap ? " nhuận" : ""} Âm`;
}

/** Mùng 1 or Rằm of a lunar date, for the monthly reminder. */
export const monthlyMark = (l: LunarDate | null): "Mùng 1" | "Rằm" | null => (l?.day === 1 ? "Mùng 1" : l?.day === 15 ? "Rằm" : null);

/** "Thứ Sáu 16/4/2027". */
export function longDate(iso: ISODate): string {
  const [y, m, d] = iso.split("-").map(Number);
  return `${WEEKDAY[new Date(y, m - 1, d).getDay()]} ${d}/${m}/${y}`;
}
/** "16/4". */
export const dm = (iso: ISODate): string => `${Number(iso.slice(8, 10))}/${Number(iso.slice(5, 7))}`;

/** One line about a holiday for a banner or the day's header. */
export function holidayLine(h: Holiday, daysUntil: number): string {
  const lunar = lunarOfMain(h);
  const main = `${longDate(h.main)}${lunar ? ` (${lunar})` : ""}`;
  const span = h.from === h.to ? dm(h.from) : `${dm(h.from)} đến ${dm(h.to)}`;
  const when = daysUntil < 0 ? `Nghỉ đến hết ${dm(h.to)}.` : daysUntil === 0 ? "Nghỉ từ hôm nay." : daysUntil === 1 ? "Nghỉ từ ngày mai." : `Còn ${daysUntil} ngày.`;
  const n = holidayDays(h);
  const days = `${n} ngày`;
  return `${main}. ${when} Nghỉ ${span} (${days}).`;
}

/** What a date is for the calendar: holiday name and whether the day is a legal holiday or an extra day off. */
export function holidayMark(iso: ISODate, overrides: readonly HolidayOverride[]): { name: string; kind: "legal" | "extra" | "weekend" } | null {
  const h = holidayOn(iso, overrides);
  return h ? { name: h.holiday.name, kind: h.kind } : null;
}
