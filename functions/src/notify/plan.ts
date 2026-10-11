import { createHash } from "node:crypto";
import { DateTime } from "luxon";
import type { Item, License, Prefs, Recurrence } from "../../../shared/model";
import { dueNudges } from "../../../shared/renewals";
import { inQuietHours, type NotifySettings } from "./settings";

/**
 * What to tell the owner, and when. Pure functions: the scheduler hands in what it read, gets back
 * finished messages, and owns nothing but sending and remembering what was sent.
 *
 * Levels (Pushover priority), from loud to quiet:
 *   2  KHẨN      an item marked important, at its time: repeats every 5 minutes until acknowledged
 *   1  quan trọng the reminder of an appointment (N minutes before) or a task (at its time); a morning
 *                 summary with something overdue or a red renewal. Rings even in Pushover's quiet hours.
 *   0  bình thường a task still not done 2 hours after its time; the morning summary; 1 hour before an important appointment
 *  -1  nhẹ       the evening look at tomorrow: no sound, just there when she looks
 *
 * Frequency is kept low on purpose: one reminder per appointment, one at the time of a task plus one
 * follow-up, and two summaries a day that are skipped when empty. Several reminders falling in the same
 * minute become one message.
 */

export type Priority = -1 | 0 | 1 | 2;

export interface Message {
  /** Stable id of this notification: the same reminder is never sent twice. */
  key: string;
  priority: Priority;
  title: string;
  /** Pushover HTML (b, i, u, font, a); user text is escaped. */
  body: string;
  /** Shown as the time of the message (epoch ms). */
  at: number;
  url?: string;
  urlTitle?: string;
  /** Seconds after which an unread message disappears by itself (not for emergencies). */
  ttlSec?: number;
  /** For a grouped message: the keys of the reminders it carries, each remembered as sent. */
  parts?: string[];
  /** This reminder as one line of a grouped message. */
  line?: string;
  /** The item it is about (an emergency is cancelled when the item is done, no longer important, or deleted). */
  itemId?: string;
  /** Emergency only: seconds Pushover keeps repeating it if nobody acknowledges. */
  expireSec?: number;
}

export interface PlanInput {
  items: readonly Item[];
  /** customerId → name, for the items given. */
  names: ReadonlyMap<string, string>;
  settings: NotifySettings;
  nowMs: number;
  /** https://… of the web app, for the "open" link. */
  appUrl?: string;
}

/** A reminder may go out this late if a scheduler run was missed; never twice (the key is remembered). */
export const CATCH_UP_MS = 10 * 60_000;
export const FOLLOW_UP_MS = 2 * 3600_000;
const HOUR_MS = 3600_000;
const ALL_DAY_FALLBACK_MIN = 8 * 60;
const MAX_BODY = 1000;
const MAX_TITLE = 240;
/** More reminders than this in one run are sent as one list. */
const MAX_SEPARATE = 3;

const LEVEL_ICON: Record<Priority, string> = { 2: "🔴", 1: "🟠", 0: "🔵", [-1]: "⚪" } as Record<Priority, string>;
const WEEKDAY = ["Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"];
const WEEKDAY_SHORT = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"];

export const escapeHtml = (s: string): string => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const oneLine = (s: string, max: number) => {
  const t = s.replace(/\s+/g, " ").trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
};

// ---------------------------------------------------------------- time

function step(d: DateTime, rule: Recurrence): DateTime {
  switch (rule) {
    case "DAILY":
      return d.plus({ days: 1 });
    case "WEEKDAYS": {
      let n = d.plus({ days: 1 });
      while (n.weekday > 5) n = n.plus({ days: 1 });
      return n;
    }
    case "WEEKLY":
      return d.plus({ weeks: 1 });
    case "MONTHLY":
      return d.plus({ months: 1 });
  }
}

/** Start times of an item within [from, to], repeating items included (same wall-clock time in `zone`). */
export function occurrences(item: Item, fromMs: number, toMs: number, zone: string): number[] {
  if (item.whenAt === undefined) return [];
  if (!item.recurrence) return item.whenAt >= fromMs && item.whenAt <= toMs ? [item.whenAt] : [];
  const out: number[] = [];
  let d: DateTime = DateTime.fromMillis(item.whenAt, { zone });
  for (let i = 0; i < 5000 && d.toMillis() <= toMs; i++) {
    if (d.toMillis() >= fromMs) out.push(d.toMillis());
    d = step(d, item.recurrence);
  }
  return out;
}

const clock = (ms: number, zone: string) => DateTime.fromMillis(ms, { zone }).toFormat("HH:mm");

/** "Hôm nay, Thứ Hai 12/10", "Mai, Thứ Ba 13/10", "Thứ Sáu 16/10". */
function dayText(ms: number, nowMs: number, zone: string): string {
  const d = DateTime.fromMillis(ms, { zone });
  const days = Math.round(d.startOf("day").diff(DateTime.fromMillis(nowMs, { zone }).startOf("day"), "days").days);
  const base = `${WEEKDAY[d.weekday - 1]} ${d.toFormat("dd/MM")}`;
  return days === 0 ? `Hôm nay, ${base}` : days === 1 ? `Mai, ${base}` : base;
}

const minuteOf = (ms: number, zone: string) => {
  const d = DateTime.fromMillis(ms, { zone });
  return d.hour * 60 + d.minute;
};

// ---------------------------------------------------------------- reminders

type Kind = "lead" | "hour" | "due" | "late" | "day";

interface Due {
  item: Item;
  occ: number;
  kind: Kind;
  fireAt: number;
  priority: Priority;
}

/** The window of item times the scheduler must read so that `reminders` sees everything due now. */
export function readWindow(nowMs: number, s: Pick<NotifySettings, "lead">): { from: number; to: number } {
  // Back far enough for the follow-up (2 h) and the 08:00 reminder of an all-day entry (start of day).
  return { from: nowMs - 10 * HOUR_MS - CATCH_UP_MS, to: nowMs + Math.max(s.lead * 60_000, HOUR_MS) + CATCH_UP_MS };
}

function candidates(item: Item, s: NotifySettings, nowMs: number): Due[] {
  if (item.status !== "OPEN" || (item.type !== "TASK" && item.type !== "EVENT") || item.whenAt === undefined) return [];
  const w = readWindow(nowMs, s);
  const out: Due[] = [];
  const loud: Priority = item.important ? 2 : 1;
  for (const occ of occurrences(item, w.from, w.to, s.zone)) {
    if (item.allDay) {
      // All-day entries are in the morning summary; without one, they get their own reminder at 08:00.
      if (s.morning < 0) {
        const at = DateTime.fromMillis(occ, { zone: s.zone }).startOf("day").plus({ minutes: ALL_DAY_FALLBACK_MIN }).toMillis();
        out.push({ item, occ, kind: "day", fireAt: at, priority: item.important ? 1 : 0 });
      }
      continue;
    }
    if (item.type === "EVENT") {
      out.push({ item, occ, kind: "lead", fireAt: occ - s.lead * 60_000, priority: loud });
      if (item.important && s.lead < 60) out.push({ item, occ, kind: "hour", fireAt: occ - HOUR_MS, priority: 0 });
    } else {
      out.push({ item, occ, kind: "due", fireAt: occ, priority: loud });
      // One follow-up, only for the latest occurrence still open, and never in the quiet hours.
      const isLatest = !item.recurrence || occ + FOLLOW_UP_MS > nowMs - CATCH_UP_MS;
      const late = occ + FOLLOW_UP_MS;
      if (isLatest && !inQuietHours(minuteOf(late, s.zone), s)) out.push({ item, occ, kind: "late", fireAt: late, priority: 0 });
    }
  }
  return out;
}

const hash = (s: string) => createHash("sha256").update(s).digest("hex").slice(0, 24);
const keyOf = (d: Due) => `r-${d.item.id}-${d.occ}-${d.kind}`;

function itemLines(item: Item, names: ReadonlyMap<string, string>): string[] {
  const lines: string[] = [];
  if (item.place) lines.push(`📍 ${escapeHtml(oneLine(item.place, 120))}`);
  const who = [item.person, item.customerId ? names.get(item.customerId) : undefined].filter(Boolean).map((s) => escapeHtml(oneLine(s!, 80)));
  if (who.length) lines.push(`👤 ${who.join(" · ")}`);
  if (item.details) lines.push(`📝 ${escapeHtml(oneLine(item.details, 300))}`);
  return lines;
}

function headline(d: Due, s: NotifySettings): string {
  switch (d.kind) {
    case "lead":
      return s.lead >= 60 ? `${s.lead / 60} giờ nữa` : `${s.lead} phút nữa`;
    case "hour":
      return "1 giờ nữa";
    case "due":
      return "Đến giờ";
    case "late":
      return "Chưa xong";
    case "day":
      return d.item.type === "EVENT" ? "Hôm nay có lịch" : "Việc hôm nay";
  }
}

function titleOf(priority: Priority, head: string, text: string): string {
  const urgent = priority === 2 ? "KHẨN · " : "";
  return oneLine(`${LEVEL_ICON[priority]} ${urgent}${head} · ${text}`, MAX_TITLE);
}

function reminderMessage(d: Due, input: PlanInput): Message {
  const { settings: s, nowMs } = input;
  const item = d.item;
  const when =
    d.kind === "day"
      ? dayText(d.occ, nowMs, s.zone)
      : item.type === "EVENT"
        ? `${clock(d.occ, s.zone)} – ${clock(d.occ + HOUR_MS, s.zone)} · ${dayText(d.occ, nowMs, s.zone)}`
        : `${d.kind === "late" ? "Hạn " : ""}${clock(d.occ, s.zone)} · ${dayText(d.occ, nowMs, s.zone)}`;
  const lines = [`<b>${escapeHtml(when)}</b>`, ...itemLines(item, input.names)];
  const doneLink = item.type === "TASK" && input.appUrl;
  if (item.type === "TASK") lines.push(doneLink ? "<i>Làm xong thì bấm “✅ Đã xong” bên dưới.</i>" : "<i>Xong thì mở app đánh dấu xong.</i>");
  if (d.priority === 2) lines.push(item.type === "EVENT" ? "<i>Nhắc lại mỗi 5 phút đến khi bấm Xác nhận; tự dừng khi đến giờ hẹn.</i>" : "<i>Nhắc lại mỗi 5 phút đến khi bấm Xác nhận hoặc Đã xong (tối đa 30 phút).</i>");
  const msg: Message = {
    key: keyOf(d),
    priority: d.priority,
    title: titleOf(d.priority, headline(d, s), item.title),
    body: fit(lines),
    at: d.fireAt,
    itemId: item.id,
    // A task: one tap in the notification ticks it off. An appointment: just open the app.
    ...(doneLink ? { url: `${input.appUrl!.replace(/\/$/, "")}/#/xong/${item.id}`, urlTitle: "✅ Đã xong" } : link(input.appUrl, "#/")),
  };
  // An appointment reminder is useless once it has started: let it disappear on its own.
  if (d.priority !== 2 && (d.kind === "lead" || d.kind === "hour")) msg.ttlSec = Math.max(60, Math.round((d.occ + 15 * 60_000 - nowMs) / 1000));
  // An emergency stops repeating by itself: at the start of the appointment (+5 min), or 30 minutes after a task's time.
  if (d.priority === 2) {
    const until = item.type === "EVENT" ? d.occ + 5 * 60_000 : d.occ + 30 * 60_000;
    msg.expireSec = Math.min(3600, Math.max(60, Math.round((until - nowMs) / 1000)));
  }
  return msg;
}

const link = (appUrl: string | undefined, hash: string) => (appUrl ? { url: `${appUrl.replace(/\/$/, "")}/${hash}`, urlTitle: "Mở VHub Desk" } : {});

/** Keeps a body under Pushover's limit by dropping whole lines from the end. */
function fit(lines: string[]): string {
  const out = [...lines];
  let dropped = 0;
  while (out.length > 1 && out.join("\n").length > MAX_BODY) {
    out.pop();
    dropped++;
  }
  if (dropped > 0) out.push(`<i>… và ${dropped} dòng nữa, mở app để xem</i>`);
  return out.join("\n").slice(0, 1024);
}

/** Every reminder that falls due now (in the last CATCH_UP_MS), one message each, loudest first. */
export function reminders(input: PlanInput): Message[] {
  const { settings: s, nowMs } = input;
  const due: Due[] = [];
  for (const item of input.items) {
    for (const d of candidates(item, s, nowMs)) {
      if (d.fireAt > nowMs || d.fireAt <= nowMs - CATCH_UP_MS) continue;
      // A "before" reminder that arrives after the start is noise.
      if ((d.kind === "lead" || d.kind === "hour") && d.occ < nowMs - 5 * 60_000) continue;
      due.push(d);
    }
  }
  due.sort((a, b) => b.priority - a.priority || a.occ - b.occ);
  return due.map((d) => ({
    ...reminderMessage(d, input),
    line: `• <b>${escapeHtml(d.item.allDay ? "Cả ngày" : clock(d.occ, s.zone))}</b> ${escapeHtml(oneLine(d.item.title, 80))} <i>(${headline(d, s).toLowerCase()})</i>`,
  }));
}

/**
 * Several reminders at once become one list, so the phone buzzes once. Emergencies stay separate:
 * each one has to be acknowledged on its own.
 */
export function group(messages: readonly Message[], nowMs: number, appUrl?: string): Message[] {
  const urgent = messages.filter((m) => m.priority === 2);
  const rest = messages.filter((m) => m.priority !== 2);
  if (rest.length <= MAX_SEPARATE) return [...urgent, ...rest];
  const top = Math.max(...rest.map((m) => m.priority)) as Priority;
  const parts = rest.map((m) => m.key);
  return [
    ...urgent,
    {
      key: `b-${hash([...parts].sort().join("|"))}`,
      priority: top,
      title: titleOf(top, "Nhắc việc", `${rest.length} mục cùng lúc`),
      body: fit(rest.map((m) => m.line ?? escapeHtml(m.title))),
      at: nowMs,
      parts,
      ...link(appUrl, "#/viec"),
    },
  ];
}

// ---------------------------------------------------------------- summaries

export interface DigestInput extends PlanInput {
  licenses: readonly License[];
  prefs: Prefs;
  /** Drafts from captures still waiting for review. */
  drafts: number;
}

export type DigestKind = "morning" | "evening";

/** The summary due now, if its time has come in the last CATCH_UP_MS. Null when not due or nothing to say. */
export function digestDue(kind: DigestKind, s: NotifySettings, nowMs: number): string | null {
  const at = kind === "morning" ? s.morning : s.evening;
  if (at < 0) return null;
  const now = DateTime.fromMillis(nowMs, { zone: s.zone });
  const target = now.startOf("day").plus({ minutes: at });
  if (target.toMillis() > nowMs || target.toMillis() <= nowMs - CATCH_UP_MS) return null;
  return `d-${kind}-${now.toISODate()}`;
}

interface Entry {
  item: Item;
  occ: number;
}

function entriesOn(items: readonly Item[], dayStart: number, dayEnd: number, zone: string): Entry[] {
  const out: Entry[] = [];
  for (const item of items) {
    if (item.status !== "OPEN" || (item.type !== "TASK" && item.type !== "EVENT")) continue;
    for (const occ of occurrences(item, dayStart, dayEnd - 1, zone)) out.push({ item, occ });
  }
  return out.sort((a, b) => Number(b.item.allDay) - Number(a.item.allDay) || a.occ - b.occ);
}

function entryLine(e: Entry, zone: string, names: ReadonlyMap<string, string>): string {
  const time = e.item.allDay ? "Cả ngày" : clock(e.occ, zone);
  const flag = e.item.important ? "❗ " : "";
  const extra = [e.item.place, e.item.customerId ? names.get(e.item.customerId) : undefined].filter(Boolean).map((x) => escapeHtml(oneLine(x!, 40)));
  return `• <b>${time}</b> ${flag}${escapeHtml(oneLine(e.item.title, 70))}${extra.length ? ` <i>(${extra.join(" · ")})</i>` : ""}`;
}

/** The morning summary (today, overdue, renewals) or the evening one (tomorrow). */
export function digest(kind: DigestKind, key: string, input: DigestInput): Message | null {
  const { settings: s, nowMs, names } = input;
  const now = DateTime.fromMillis(nowMs, { zone: s.zone });
  const day = kind === "morning" ? now.startOf("day") : now.startOf("day").plus({ days: 1 });
  const entries = entriesOn(input.items, day.toMillis(), day.plus({ days: 1 }).toMillis(), s.zone);
  const events = entries.filter((e) => e.item.type === "EVENT");
  const tasks = entries.filter((e) => e.item.type === "TASK");
  const dayLabel = `${WEEKDAY_SHORT[day.weekday - 1]} ${day.toFormat("dd/MM")}`;

  if (kind === "evening") {
    if (entries.length === 0) return null;
    const first = entries.find((e) => !e.item.allDay);
    const lines = [
      ...(first ? [`<b>Sớm nhất: ${clock(first.occ, s.zone)}</b> ${escapeHtml(oneLine(first.item.title, 70))}`] : []),
      ...(events.length ? ["<b>📅 Lịch</b>", ...events.map((e) => entryLine(e, s.zone, names))] : []),
      ...(tasks.length ? ["<b>✅ Việc</b>", ...tasks.map((e) => entryLine(e, s.zone, names))] : []),
    ];
    const important = entries.some((e) => e.item.important);
    return {
      key, priority: important ? 0 : -1,
      title: oneLine(`🌙 Ngày mai ${dayLabel} · ${count(events.length, "lịch")}${events.length && tasks.length ? ", " : ""}${count(tasks.length, "việc")}`, MAX_TITLE),
      body: fit(lines), at: nowMs, ...link(input.appUrl, "#/viec"),
    };
  }

  const today0 = day.toMillis();
  const overdue = input.items
    .filter((i) => i.status === "OPEN" && i.type === "TASK" && i.whenAt !== undefined && !i.recurrence && (i.allDay ? i.whenAt < today0 : i.whenAt < nowMs) && i.whenAt >= today0 - 30 * 24 * HOUR_MS)
    .sort((a, b) => a.whenAt! - b.whenAt!);
  const nudges = dueNudges(input.licenses.filter((l) => l.status === "OPEN"), { today: now.toISODate()!, prefs: input.prefs, zone: s.zone });
  if (entries.length === 0 && overdue.length === 0 && nudges.length === 0 && input.drafts === 0) return null;

  const red = nudges.filter((n) => n.severity === "danger").length;
  const lines: string[] = [];
  if (overdue.length) {
    lines.push(`<b><font color="#d32f2f">⚠️ Quá hạn (${overdue.length})</font></b>`);
    for (const i of overdue.slice(0, 6)) lines.push(`• ${i.important ? "❗ " : ""}${escapeHtml(oneLine(i.title, 70))} <i>(hạn ${dayShort(i.whenAt!, s.zone)})</i>`);
  }
  if (events.length) lines.push("<b>📅 Lịch hôm nay</b>", ...events.map((e) => entryLine(e, s.zone, names)));
  if (tasks.length) lines.push("<b>✅ Việc hôm nay</b>", ...tasks.map((e) => entryLine(e, s.zone, names)));
  if (nudges.length) {
    const licById = new Map(input.licenses.map((l) => [l.id, l]));
    lines.push(`<b>🔁 Gia hạn cần xử lý (${nudges.length})</b>`);
    for (const n of nudges.slice(0, 6)) {
      const l = licById.get(n.licenseId);
      const who = escapeHtml(oneLine(`${names.get(n.customerId) ?? "Khách"} · ${l?.product ?? ""}`, 60));
      lines.push(`• ${n.severity === "danger" ? "🔴 " : ""}${who}: ${escapeHtml(n.action)}`);
    }
  }
  if (input.drafts) lines.push(`<i>📝 ${input.drafts} ghi nhanh đang chờ bạn xem lại</i>`);
  const loud = overdue.length > 0 || red > 0 || entries.some((e) => e.item.important);
  const parts = [count(events.length, "lịch"), count(tasks.length, "việc"), overdue.length ? `⚠️ ${overdue.length} quá hạn` : "", nudges.length ? `${nudges.length} gia hạn` : ""].filter(Boolean);
  return {
    key, priority: loud ? 1 : 0,
    title: oneLine(`☀️ Hôm nay ${dayLabel}${parts.length ? ` · ${parts.join(", ")}` : ""}`, MAX_TITLE),
    body: fit(lines), at: nowMs, ...link(input.appUrl, "#/"),
  };
}

const count = (n: number, what: string) => (n ? `${n} ${what}` : "");
const dayShort = (ms: number, zone: string) => {
  const d = DateTime.fromMillis(ms, { zone });
  return `${WEEKDAY_SHORT[d.weekday - 1]} ${d.toFormat("dd/MM")}`;
};

/** The message sent by "Gửi thử": what a real reminder looks like. */
export function sampleMessage(s: NotifySettings, nowMs: number, appUrl?: string): Message {
  return {
    key: `test-${nowMs}`,
    priority: 1,
    title: `${LEVEL_ICON[1]} ${s.lead} phút nữa · Ví dụ: Họp với anh Nam`,
    body: fit([
      `<b>${clock(nowMs + s.lead * 60_000, s.zone)} – ${clock(nowMs + s.lead * 60_000 + HOUR_MS, s.zone)} · ${escapeHtml(dayText(nowMs, nowMs, s.zone))}</b>`,
      "📍 Văn phòng",
      "👤 anh Nam · Công ty ABC",
      "<i>Đây là tin thử từ VHub Desk. Nhận được tin này là thông báo đã chạy.</i>",
    ]),
    at: nowMs,
    ...link(appUrl, "#/cai-dat"),
  };
}
