import type { Item } from "./model";
import { startOfDay } from "./schedule";

const WEEKDAY = ["Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"];
const pad = (n: number) => String(n).padStart(2, "0");

export const clock = (t: number) => `${pad(new Date(t).getHours())}:${pad(new Date(t).getMinutes())}`;

/** "Hôm nay", "Mai", "Thứ Năm 15/10" (+ "/2027" in another year). */
export function dayLabel(t: number, now = Date.now()): string {
  const days = Math.round((startOfDay(t) - startOfDay(now)) / 86_400_000);
  if (days === 0) return "Hôm nay";
  if (days === 1) return "Mai";
  if (days === -1) return "Hôm qua";
  const d = new Date(t);
  const base = `${WEEKDAY[d.getDay()]} ${pad(d.getDate())}/${pad(d.getMonth() + 1)}`;
  return d.getFullYear() === new Date(now).getFullYear() ? base : `${base}/${d.getFullYear()}`;
}

/** "Hôm nay 15:00", "Mai", "Thứ Năm 15/10 09:30" */
export function whenLabel(item: Item, now = Date.now()): string | null {
  if (item.whenAt === undefined) return null;
  return item.allDay ? dayLabel(item.whenAt, now) : `${dayLabel(item.whenAt, now)} ${clock(item.whenAt)}`;
}

export function todayLabel(now = Date.now()): string {
  const d = new Date(now);
  return `${WEEKDAY[d.getDay()]}, ${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** Value for <input type="date"> / <input type="time"> from epoch ms. */
export const toDateInput = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
export const toTimeInput = (t: number) => clock(t);

/** Epoch ms from the two inputs; an all-day item keeps midnight. Null when the date is empty/invalid. */
export function fromInputs(date: string, time: string, allDay: boolean): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) return null;
  let h = 0;
  let mi = 0;
  if (!allDay) {
    const t = /^(\d{2}):(\d{2})$/.exec(time);
    if (!t) return null;
    h = Number(t[1]);
    mi = Number(t[2]);
  }
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), h, mi, 0, 0);
  return Number.isNaN(d.getTime()) || d.getMonth() !== Number(m[2]) - 1 ? null : d.getTime();
}
