import type { Item, Recurrence } from "./model";

/** Appointments remind this long before they start; all-day items remind at this hour. */
const EVENT_LEAD_MIN = 15;
const ALL_DAY_HOUR = 8;
const EVENT_LENGTH_MIN = 60;
const TASK_LENGTH_MIN = 30;

const RRULE: Record<Recurrence, string> = {
  DAILY: "FREQ=DAILY",
  WEEKDAYS: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
  WEEKLY: "FREQ=WEEKLY",
  MONTHLY: "FREQ=MONTHLY",
};

const pad = (n: number, w = 2) => String(n).padStart(w, "0");

/** Floating local time (no zone): the calendar shows it at the same wall-clock time on the device. */
const localStamp = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}T${pad(d.getHours())}${pad(d.getMinutes())}00`;
};
const dateStamp = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
};
const utcStamp = (t: number) => new Date(t).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");

/** RFC 5545 TEXT escaping. */
export function escapeText(s: string): string {
  return s.replace(/\\/g, "\\\\").replace(/;/g, "\;").replace(/,/g, "\\,").replace(/\r\n|\r|\n/g, "\\n");
}

/** Folds a content line at 75 octets without splitting a UTF-8 character. */
export function fold(line: string): string {
  const enc = new TextEncoder();
  if (enc.encode(line).length <= 75) return line;
  const out: string[] = [];
  let cur = "";
  let bytes = 0;
  let limit = 75;
  for (const ch of line) {
    const n = enc.encode(ch).length;
    if (bytes + n > limit) {
      out.push(cur);
      cur = "";
      bytes = 0;
      limit = 74; // continuation lines start with a space
    }
    cur += ch;
    bytes += n;
  }
  out.push(cur);
  return out.join("\r\n ");
}

/** An item as VEVENT lines, or null when it has no date to put on a calendar. */
export function eventLines(item: Item, stamp: number): string[] | null {
  if (item.whenAt === undefined) return null;
  const lines = ["BEGIN:VEVENT", `UID:${item.id}@myrecap`, `DTSTAMP:${utcStamp(stamp)}`];
  let trigger: string;
  if (item.allDay) {
    const start = new Date(item.whenAt);
    const end = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1);
    lines.push(`DTSTART;VALUE=DATE:${dateStamp(item.whenAt)}`, `DTEND;VALUE=DATE:${dateStamp(end.getTime())}`);
    trigger = `TRIGGER;RELATED=START:PT${ALL_DAY_HOUR}H`;
  } else {
    const len = (item.type === "EVENT" ? EVENT_LENGTH_MIN : TASK_LENGTH_MIN) * 60_000;
    lines.push(`DTSTART:${localStamp(item.whenAt)}`, `DTEND:${localStamp(item.whenAt + len)}`);
    trigger = item.type === "EVENT" ? `TRIGGER:-PT${EVENT_LEAD_MIN}M` : "TRIGGER:PT0S";
  }
  const prefix = item.type === "TASK" ? "Việc: " : "";
  lines.push(`SUMMARY:${escapeText(prefix + item.title)}`);
  const desc = [item.details, item.person ? `Với: ${item.person}` : ""].filter(Boolean).join("\n");
  if (desc) lines.push(`DESCRIPTION:${escapeText(desc)}`);
  if (item.place) lines.push(`LOCATION:${escapeText(item.place)}`);
  if (item.recurrence) lines.push(`RRULE:${RRULE[item.recurrence]}`);
  lines.push("BEGIN:VALARM", "ACTION:DISPLAY", `DESCRIPTION:${escapeText(item.title)}`, trigger, "END:VALARM", "END:VEVENT");
  return lines;
}

/** A calendar file with every dated task/appointment given. Items without a date are skipped. */
export function buildIcs(items: Item[], stamp = Date.now()): string {
  const body = items.flatMap((i) => eventLines(i, stamp) ?? []);
  const lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//My Recap//Thu ky//VI", "CALSCALE:GREGORIAN", ...body, "END:VCALENDAR"];
  return lines.map(fold).join("\r\n") + "\r\n";
}
