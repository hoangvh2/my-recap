import { DateTime } from "luxon";
import type { Item } from "../../shared/model";
import { fold } from "../../shared/text";

/**
 * Small models file "họp anh Nam 3h chiều mai" as a task, or leave the date of "hạn nộp 20/11" out.
 * These rules fix the two mistakes that matter for reminders, without ever creating a second item:
 * one thing said is one item, shown both in its list and on the calendar.
 */

/** Words that make something an appointment (it happens at a time) rather than a task (it must get done). */
const APPOINTMENT = /(^|[^\p{L}])(họp|hẹn gặp|hẹn với|hẹn ăn|hẹn khám|lịch hẹn|cuộc hẹn|có hẹn|gặp|khám|tái khám|tiêm|đón|tiệc|đám cưới|đám giỗ|sinh nhật|phỏng vấn|demo|meeting|lịch bay|chuyến bay|bay chuyến|xem phim|ăn tối với|ăn trưa với|cà phê với|cafe với|học thêm|đi học|lễ)(?=$|[^\p{L}])/iu;

export function isAppointment(text: string): boolean {
  return APPOINTMENT.test(text.normalize("NFC").toLowerCase());
}

/** An explicit day in the words: "20/11", "20/11/2026", "ngày 20 tháng 11 (năm 2026)". Relative days are left to the model. */
export function explicitDate(text: string, nowMs: number, zone: string): { year: number; month: number; day: number } | null {
  const t = fold(text);
  const now = DateTime.fromMillis(nowMs, { zone });
  const spoken = /(\d{1,2})\s+thang\s+(\d{1,2})(?:\s+nam\s+(\d{4}))?/.exec(t);
  // "12/11/2026" anywhere; "12/11" only after a day word, so "1/2 số tiền" or "giá 3.5" is never a date.
  const numeric =
    /(?:^|[^\d/.-])(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})(?![\d/.-])/.exec(t) ??
    /(?:ngay|han|truoc|den|toi|sang|chieu|thu \d|chu nhat|cn)(?:\s+[a-z]+){0,2}\s+(\d{1,2})[/-](\d{1,2})(?![\d/.-])/.exec(t);
  const m = spoken ?? numeric;
  if (!m) return null;
  const day = Number(m[1]);
  const month = Number(m[2]);
  let year = m[3] ? Number(m[3]) : now.year;
  let d = DateTime.fromObject({ year, month, day }, { zone });
  if (!d.isValid) return null;
  // No year said: the next such day.
  if (!m[3] && d < now.startOf("day")) {
    year += 1;
    d = DateTime.fromObject({ year, month, day }, { zone });
    if (!d.isValid) return null;
  }
  return { year, month, day };
}

/**
 * Corrects the type and date of one draft item in place:
 * - a task or note about an appointment that has a date becomes an appointment (EVENT);
 * - a task with no date whose words name an explicit day gets that day (all day), so it is reminded.
 * Notes without an appointment stay notes; nothing is ever duplicated.
 */
export function fixTiming(item: Item, nowMs: number, zone: string): void {
  if (item.type === "EXPENSE") return;
  const words = [item.title, item.details, item.quote ?? ""].join(" ");
  if (item.whenAt === undefined && item.type !== "NOTE") {
    const d = explicitDate(words, nowMs, zone);
    if (d) {
      item.whenAt = DateTime.fromObject(d, { zone }).startOf("day").toMillis();
      item.allDay = true;
    }
  }
  if (item.type === "NOTE" && item.whenAt === undefined && isAppointment(words)) {
    const d = explicitDate(words, nowMs, zone);
    if (d && DateTime.fromObject(d, { zone }) >= DateTime.fromMillis(nowMs, { zone }).startOf("day")) {
      item.whenAt = DateTime.fromObject(d, { zone }).startOf("day").toMillis();
      item.allDay = true;
    }
  }
  if (item.type !== "EVENT" && item.whenAt !== undefined && isAppointment(words)) {
    item.type = "EVENT";
    // Appointments are not "done"; a recurrence stays as it was.
  }
}
