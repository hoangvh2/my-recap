import { addDays, formatDate, type ISODate } from "./dates";
import type { Ctx } from "./renewals";
import { calendarMilestones, daysLeftText } from "./renewals";
import { KIND_LABEL, STAGE_LABEL, type Item, type License, type Recurrence } from "./model";

/** Appointments remind this long before they start; all-day items remind at this hour. */
const EVENT_LEAD_MIN = 15;
const ALL_DAY_HOUR = 8;
const MILESTONE_HOUR = 9;
const EVENT_LENGTH_MIN = 60;
const TASK_LENGTH_MIN = 30;

const RRULE: Record<Recurrence, string> = {
  DAILY: "FREQ=DAILY",
  WEEKDAYS: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
  WEEKLY: "FREQ=WEEKLY",
  MONTHLY: "FREQ=MONTHLY",
};

/** One calendar entry, before it is turned into iCalendar text. */
export interface CalEvent {
  uid: string;
  summary: string;
  description?: string;
  location?: string;
  /** All-day on this date. */
  date?: ISODate;
  /** Or a time of day, shown at the same wall-clock time wherever the phone is. */
  startMs?: number;
  durationMin?: number;
  rrule?: string;
  /** The whole alarm line, e.g. "TRIGGER:-PT15M". */
  alarm: string;
  /** Zone to read `startMs` in; the machine's zone when omitted (the browser). */
  zone?: string;
}

const pad = (n: number, w = 2) => String(n).padStart(w, "0");

/** Wall-clock parts of an instant in a zone. */
function wall(ms: number, zone?: string): { y: number; mo: number; d: number; h: number; mi: number } {
  if (!zone) {
    const d = new Date(ms);
    return { y: d.getFullYear(), mo: d.getMonth() + 1, d: d.getDate(), h: d.getHours(), mi: d.getMinutes() };
  }
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: zone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).formatToParts(ms);
  const get = (t: string) => Number(parts.find((p) => p.type === t)?.value);
  return { y: get("year"), mo: get("month"), d: get("day"), h: get("hour"), mi: get("minute") };
}

const stampOf = (w: ReturnType<typeof wall>) => `${pad(w.y, 4)}${pad(w.mo)}${pad(w.d)}T${pad(w.h)}${pad(w.mi)}00`;
const dateOnly = (d: ISODate) => d.replaceAll("-", "");
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

function eventLines(e: CalEvent, stamp: number): string[] {
  const lines = ["BEGIN:VEVENT", `UID:${e.uid}`, `DTSTAMP:${utcStamp(stamp)}`];
  if (e.date) {
    lines.push(`DTSTART;VALUE=DATE:${dateOnly(e.date)}`, `DTEND;VALUE=DATE:${dateOnly(addDays(e.date, 1))}`);
  } else if (e.startMs !== undefined) {
    const start = stampOf(wall(e.startMs, e.zone));
    const end = stampOf(wall(e.startMs + (e.durationMin ?? EVENT_LENGTH_MIN) * 60_000, e.zone));
    lines.push(`DTSTART:${start}`, `DTEND:${end}`);
  }
  lines.push(`SUMMARY:${escapeText(e.summary)}`);
  if (e.description) lines.push(`DESCRIPTION:${escapeText(e.description)}`);
  if (e.location) lines.push(`LOCATION:${escapeText(e.location)}`);
  if (e.rrule) lines.push(`RRULE:${e.rrule}`);
  lines.push("BEGIN:VALARM", "ACTION:DISPLAY", `DESCRIPTION:${escapeText(e.summary)}`, e.alarm, "END:VALARM", "END:VEVENT");
  return lines;
}

export interface CalendarOptions {
  stamp?: number;
  /** Name shown for a subscribed calendar. */
  name?: string;
  /** Ask calendar apps to refresh a subscription this often. */
  refreshHours?: number;
}

/** iCalendar text for a set of entries (CRLF line ends, long lines folded). */
export function renderCalendar(events: readonly CalEvent[], opts: CalendarOptions = {}): string {
  const stamp = opts.stamp ?? Date.now();
  const head = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//My Recap//Thu ky//VI", "CALSCALE:GREGORIAN", "METHOD:PUBLISH"];
  if (opts.name) head.push(`X-WR-CALNAME:${escapeText(opts.name)}`);
  if (opts.refreshHours) head.push(`REFRESH-INTERVAL;VALUE=DURATION:PT${opts.refreshHours}H`, `X-PUBLISHED-TTL:PT${opts.refreshHours}H`);
  const lines = [...head, ...events.flatMap((e) => eventLines(e, stamp)), "END:VCALENDAR"];
  return lines.map(fold).join("\r\n") + "\r\n";
}

/** A task or appointment as a calendar entry; null when it has no date. */
export function eventForItem(item: Item, who?: string, zone?: string): CalEvent | null {
  if (item.whenAt === undefined) return null;
  const prefix = item.type === "TASK" ? "Việc: " : "";
  const suffix = who ? ` · ${who}` : "";
  const desc = [item.details, item.person ? `Với: ${item.person}` : ""].filter(Boolean).join("\n");
  const base = {
    uid: `${item.id}@myrecap`,
    summary: `${prefix}${item.title}${suffix}`,
    description: desc || undefined,
    location: item.place,
    rrule: item.recurrence ? RRULE[item.recurrence] : undefined,
  };
  if (item.allDay) {
    const w = wall(item.whenAt, zone);
    return { ...base, date: `${pad(w.y, 4)}-${pad(w.mo)}-${pad(w.d)}`, alarm: `TRIGGER;RELATED=START:PT${ALL_DAY_HOUR}H` };
  }
  return {
    ...base,
    startMs: item.whenAt,
    zone,
    durationMin: item.type === "EVENT" ? EVENT_LENGTH_MIN : TASK_LENGTH_MIN,
    alarm: item.type === "EVENT" ? `TRIGGER:-PT${EVENT_LEAD_MIN}M` : "TRIGGER:PT0S",
  };
}

const MILESTONE_TITLE: Record<string, string> = {
  ASK: "Hỏi khách có gia hạn không",
  QUOTE: "Gửi báo giá gia hạn",
  CONTRACT: "Chốt hợp đồng gia hạn",
  URGENT: "CẢNH BÁO sắp hết hạn chưa chốt",
  END: "Hết hạn",
};

/**
 * The reminders ahead for one licence, and its end date. Phone numbers and e-mail addresses are left out
 * on purpose: a calendar feed is read by an app outside this one.
 */
export function eventsForLicense(l: License, customerName: string, ctx: Ctx): CalEvent[] {
  const detail = [
    `${KIND_LABEL[l.kind]}: ${l.product}`,
    `Khách: ${customerName}`,
    `Hết hạn: ${formatDate(l.endDate)} (${daysLeftText(Math.round((Date.parse(`${l.endDate}T00:00:00Z`) - Date.parse(`${ctx.today}T00:00:00Z`)) / 86_400_000))})`,
    `Giai đoạn: ${STAGE_LABEL[l.stage]}`,
    l.value ? `Giá trị: ${l.value.toLocaleString("vi-VN")} đ` : "",
    l.contractNo ? `Số hợp đồng: ${l.contractNo}` : "",
  ].filter(Boolean).join("\n");
  return calendarMilestones(l, ctx).map((m) => ({
    uid: `${l.id}-${m.kind}@myrecap`,
    summary: m.kind === "END" ? `${MILESTONE_TITLE.END}: ${l.product} · ${customerName}` : `Gia hạn · ${MILESTONE_TITLE[m.kind]}: ${customerName} (${l.product})`,
    description: detail,
    date: m.date,
    alarm: `TRIGGER;RELATED=START:PT${MILESTONE_HOUR}H`,
  }));
}

/** A calendar file of dated items, for "add to calendar". */
export function buildItemsCalendar(items: readonly Item[], opts: CalendarOptions & { names?: ReadonlyMap<string, string>; zone?: string } = {}): string {
  const events = items.map((i) => eventForItem(i, i.customerId ? opts.names?.get(i.customerId) : undefined, opts.zone)).filter((e): e is CalEvent => e !== null);
  return renderCalendar(events, opts);
}
