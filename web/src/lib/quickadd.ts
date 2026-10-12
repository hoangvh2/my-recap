import { guessCategory } from "./categories";
import { formatVnd } from "./money";
import type { Item, ItemType, Recurrence } from "./model";
import { RECURRENCE_LABEL } from "./model";
import { fold } from "./views";

/**
 * Typed quick-add without the AI: "gọi anh Nam mai 3h chiều", "họp nhóm thứ 5 tuần sau 9h", "uống thuốc
 * mỗi ngày 8h tối gấp", "cà phê 45k". Understands only the common Vietnamese phrasings below and leaves
 * everything else in the title; a phrase it is unsure of is never guessed, so the worst case is a plain
 * undated task. Each recognised phrase is reported so the screen can show it and let the user switch it
 * off. Port of the Android `QuickAdd`, in the device's local time.
 */
export type Part = "DAY" | "TIME" | "REPEAT" | "IMPORTANT" | "AMOUNT";
export const PART_LABEL: Record<Part, string> = { DAY: "Ngày", TIME: "Giờ", REPEAT: "Lặp lại", IMPORTANT: "Quan trọng", AMOUNT: "Số tiền" };

/** A recognised phrase: which part it set, the words as typed and a readable form such as "Mai". */
export interface Span {
  part: Part;
  text: string;
  shown: string;
  start: number;
  end: number;
}

export interface QuickResult {
  title: string;
  type: ItemType;
  whenAt?: number;
  allDay: boolean;
  recurrence?: Recurrence;
  important: boolean;
  spans: Span[];
  /** Whole VND; set only for expenses ("cà phê 45k"). */
  amount?: number;
  category?: string;
}

/** The item a result saves as. */
export function toItem(r: QuickResult, id: string, now: number): Item {
  return {
    id, type: r.type, status: "OPEN", title: r.title, details: "", allDay: r.allDay, createdAt: now,
    ...(r.whenAt !== undefined ? { whenAt: r.whenAt } : {}),
    ...(r.recurrence ? { recurrence: r.recurrence } : {}),
    ...(r.important ? { important: true } : {}),
    ...(r.amount !== undefined ? { amount: r.amount } : {}),
    ...(r.category ? { category: r.category } : {}),
  };
}

/** Words that make a dated entry an appointment rather than a task (same idea as the server rule). */
const EVENT_WORDS = ["hop", "hen", "gap mat", "phong van", "kham", "demo", "sinh nhat", "chuyen bay", "tiec"];

/** ISO weekday: Monday 1 … Sunday 7. */
const DAYS: Record<string, number> = { hai: 1, "2": 1, ba: 2, "3": 2, tu: 3, "4": 3, nam: 4, "5": 4, sau: 5, "6": 5, bay: 6, "7": 6 };

const REPEAT = /\b(?:moi|hang) (ngay|tuan|thang)\b/;
const EVERY_WEEKDAY = /\bmoi thu (hai|ba|tu|nam|sau|bay|[2-7])\b/;
const WEEKDAY = /\bthu (hai|ba|tu|nam|sau|bay|[2-7])(?: (tuan sau|tuan toi|tuan nay))?\b/g;
const SUNDAY = /\b(?:chu nhat|cn)(?: (tuan sau|tuan toi|tuan nay))?\b/g;
const SLASH_DATE = /\b(\d{1,2})\/(\d{1,2})(?:\/(\d{4}))?\b/g;
const LONG_DATE = /\bngay (\d{1,2}) thang (\d{1,2})\b/g;
const DAY_ONLY = /\bngay (\d{1,2})\b/g;
const YESTERDAY = /\bhom qua\b/g;
const RELATIVE = /\b(hom nay|ngay mai|ngay mot|mai)\b/g;
// 9h, 9h30, 9 giờ, 9 giờ 30, 9 giờ rưỡi, 09:30. "h" must end the word so "2 hộp" is not 2 o'clock.
const TIME = /\b(\d{1,2})(?: ?h(?:(\d{2})\b|(?![a-z]))| ?gio\b(?: (\d{2}|ruoi)\b)?|:(\d{2}))(?![\d/])/g;
const PERIOD_AFTER = /^ (sang|trua|chieu|toi)\b/;
const PERIOD_BEFORE = /\b(sang|trua|chieu|toi) $/;
const IMPORTANT = /\b(gap|quan trong)\b/;
// 45k, 45 nghìn, 1tr2, 1.5 triệu, 2 củ, 120.000đ, 50000 vnd. A bare number is never money ("gọi 3 người").
const MILLIONS = /\b(\d+)(?:[.,](\d+))? ?(?:tr|trieu|cu)(?: ?(\d{1,3}))?(?![\d.,a-z])/g;
const THOUSANDS = /\b(\d+)(?:[.,](\d+))? ?(?:k|nghin|ngan)(?![\d.,a-z])/g;
const GROUPED = /\b(\d{1,3}(?:\.\d{3})+)(?: ?(?:d|vnd|dong))?(?![\d.,a-z])/g;
const WITH_UNIT = /\b(\d{3,})(?: ?(?:d|vnd|dong))(?![\d.,a-z])/g;
type Unit = "MILLIONS" | "THOUSANDS" | "GROUPED" | "WITH_UNIT";
const AMOUNTS: [Unit, RegExp][] = [["MILLIONS", MILLIONS], ["THOUSANDS", THOUSANDS], ["GROUPED", GROUPED], ["WITH_UNIT", WITH_UNIT]];

interface Hit {
  start: number;
  end: number;
  g: string[];
}

const hits = (re: RegExp, s: string): Hit[] =>
  [...s.matchAll(new RegExp(re.source, re.flags.includes("g") ? re.flags : `${re.flags}g`))].map((m) => ({
    start: m.index!, end: m.index! + m[0].length, g: Array.from(m, (x) => x ?? ""),
  }));

const day0 = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const isoDow = (d: Date) => d.getDay() || 7;
const sameDay = (a: Date, b: Date) => a.getTime() === b.getTime();
const mondayOf = (d: Date) => addDays(d, 1 - isoDow(d));
/** The coming [dow], strictly after [d]. */
const nextDow = (d: Date, dow: number) => addDays(d, ((dow - isoDow(d) + 7) % 7) || 7);

/** The coming day; "tuần sau" means that day of next week, "tuần này" the day this week even if past. */
function weekdayDate(today: Date, dow: number, qualifier: string): Date {
  if (qualifier === "tuan sau" || qualifier === "tuan toi") return addDays(mondayOf(today), 7 + dow - 1);
  if (qualifier === "tuan nay") return addDays(mondayOf(today), dow - 1);
  return nextDow(today, dow);
}

function monthDay(today: Date, day: number, month: number, year?: number): Date | null {
  const y = year ?? today.getFullYear();
  const d = new Date(y, month - 1, day);
  if (d.getFullYear() !== y || d.getMonth() !== month - 1 || d.getDate() !== day) return null;
  return year === undefined && d < today ? new Date(y + 1, month - 1, day) : d;
}

const weekdayName = (dow: number) => (dow === 7 ? "Chủ nhật" : `Thứ ${dow + 1}`);

function dayLabel(date: Date, today: Date): string {
  if (sameDay(date, today)) return "Hôm nay";
  if (sameDay(date, addDays(today, 1))) return "Mai";
  if (sameDay(date, addDays(today, -1))) return "Hôm qua";
  return `${weekdayName(isoDow(date))} ${date.getDate()}/${date.getMonth() + 1}`;
}

/**
 * "thứ tư" (Wednesday) folds to the same letters as "thứ tự" (order): require the real accent.
 * [prefix] is how many characters come before the weekday word in the match ("thu " = 4).
 */
function isWeekday(typed: string, h: Hit, prefix: number): boolean {
  if (h.g[1] !== "tu") return true;
  return typed.slice(h.start + prefix, h.start + prefix + 2).toLowerCase() === "tư";
}

function parseAmount(unit: Unit, g: string[]): number | null {
  const fraction = (digits: string, scale: number) => (digits ? Math.trunc(Number(`0.${digits}`) * scale) : 0);
  let amount: number;
  switch (unit) {
    case "MILLIONS": {
      // "1tr2" = 1.2 triệu, "1tr25" = 1.25 triệu, "1 triệu 200" = 1.200.000.
      const t = g[3];
      const tail = t ? Number(t) * (t.length === 1 ? 100_000 : t.length === 2 ? 10_000 : 1_000) : 0;
      amount = Number(g[1]) * 1_000_000 + fraction(g[2], 1_000_000) + tail;
      break;
    }
    case "THOUSANDS": amount = Number(g[1]) * 1_000 + fraction(g[2], 1_000); break;
    case "GROUPED": amount = Number(g[1].replaceAll(".", "")); break;
    case "WITH_UNIT": amount = Number(g[1]); break;
  }
  return amount >= 1_000 && amount <= 10_000_000_000 ? amount : null;
}

/** Extends a time match over an adjacent "sáng/trưa/chiều/tối" so the word is not left in the title. */
function widen(folded: string, h: Hit): { start: number; end: number } {
  const after = PERIOD_AFTER.exec(folded.slice(h.end));
  if (after) return { start: h.start, end: h.end + after[0].length };
  const before = PERIOD_BEFORE.exec(folded.slice(0, h.start));
  if (before) return { start: before.index, end: h.end };
  return { start: h.start, end: h.end };
}

const periodOf = (folded: string, h: Hit): string | null =>
  PERIOD_AFTER.exec(folded.slice(h.end))?.[1] ?? PERIOD_BEFORE.exec(folded.slice(0, h.start))?.[1] ?? null;

function parseTime(h: Hit, period: string | null): { hour: number; minute: number } | null {
  let hour = Number(h.g[1]);
  const [, , m2, m3, m4] = h.g;
  const minute = m3 === "ruoi" ? 30 : m2 ? Number(m2) : m3 ? Number(m3) : m4 ? Number(m4) : 0;
  if (hour > 23 || minute > 59) return null;
  if ((period === "chieu" || period === "toi") && hour >= 1 && hour <= 11) hour += 12;
  else if (period === "trua" && hour >= 1 && hour <= 5) hour += 12;
  return { hour, minute };
}

const CONNECTORS = new Set(["lúc", "vào", "ngày", "hạn", "trước", "luc", "vao", "truoc", "-", ","]);

function cleanTitle(typed: string, taken: boolean[]): string {
  const kept = [...typed].map((c, i) => (taken[i] ? " " : c)).join("");
  const words = kept.split(" ").filter(Boolean);
  while (words.length && CONNECTORS.has(words[0].toLowerCase())) words.shift();
  while (words.length && CONNECTORS.has(words[words.length - 1].toLowerCase())) words.pop();
  return words.join(" ").replace(/^[\s,;-]+|[\s,;-]+$/g, "");
}

const plain = (text: string): QuickResult => ({ title: text, type: "TASK", allDay: false, important: false, spans: [] });

/** [history]: earlier items, so an expense gets the category the user gave the same title before. */
export function parseQuick(text: string, now: number, off: ReadonlySet<Part> = new Set(), history: Item[] = []): QuickResult {
  const typed = text.trim().replace(/\s+/g, " ");
  const folded = fold(typed);
  // Positions are read on the folded text and applied to the typed text; only safe when they line up.
  if (!typed || folded.length !== typed.length) return plain(typed);

  const today = day0(new Date(now));
  const spans: Span[] = [];
  const taken: boolean[] = new Array(typed.length).fill(false);
  const free = (s: number, e: number) => {
    for (let i = s; i < e; i++) if (taken[i]) return false;
    return true;
  };
  const claim = (part: Part, s: number, e: number, shown: string) => {
    for (let i = s; i < e; i++) taken[i] = true;
    spans.push({ part, text: typed.slice(s, e), shown, start: s, end: e });
  };

  // --- amount: makes it an expense
  let amount: number | undefined;
  if (!off.has("AMOUNT")) {
    const found = AMOUNTS.flatMap(([unit, re]) => hits(re, folded).map((h) => ({ unit, h }))).sort((a, b) => a.h.start - b.h.start);
    for (const { unit, h } of found) {
      if (!free(h.start, h.end)) continue;
      const value = parseAmount(unit, h.g);
      if (value === null) continue;
      amount = value;
      claim("AMOUNT", h.start, h.end, formatVnd(value));
      break;
    }
  }

  // --- repeat
  let recurrence: Recurrence | undefined;
  let repeatDow: number | undefined;
  if (!off.has("REPEAT") && amount === undefined) {
    const m = hits(REPEAT, folded)[0];
    if (m && free(m.start, m.end)) {
      recurrence = m.g[1] === "ngay" ? "DAILY" : m.g[1] === "tuan" ? "WEEKLY" : "MONTHLY";
      claim("REPEAT", m.start, m.end, RECURRENCE_LABEL[recurrence]);
    }
    // "mỗi thứ 2" repeats weekly on that day.
    if (!recurrence) {
      const w = hits(EVERY_WEEKDAY, folded)[0];
      if (w && free(w.start, w.end) && isWeekday(typed, w, 8)) {
        repeatDow = DAYS[w.g[1]];
        recurrence = "WEEKLY";
        claim("REPEAT", w.start, w.end, `Mỗi ${weekdayName(repeatDow)}`);
      }
    }
  }

  // --- day
  let date: Date | undefined;
  if (!off.has("DAY")) {
    if (repeatDow !== undefined) date = nextDow(today, repeatDow);
    if (!date) {
      const cands: { start: number; end: number; date: Date }[] = [];
      // Spending is logged after the fact, so "hôm qua" (yesterday) counts only for an expense.
      if (amount !== undefined) for (const h of hits(YESTERDAY, folded)) cands.push({ ...h, date: addDays(today, -1) });
      for (const h of hits(RELATIVE, folded)) cands.push({ ...h, date: addDays(today, h.g[0] === "hom nay" ? 0 : h.g[0] === "ngay mot" ? 2 : 1) });
      for (const h of hits(WEEKDAY, folded)) if (isWeekday(typed, h, 4)) cands.push({ ...h, date: weekdayDate(today, DAYS[h.g[1]], h.g[2]) });
      for (const h of hits(SUNDAY, folded)) cands.push({ ...h, date: weekdayDate(today, 7, h.g[1]) });
      for (const h of hits(LONG_DATE, folded)) { const d = monthDay(today, Number(h.g[1]), Number(h.g[2])); if (d) cands.push({ ...h, date: d }); }
      for (const h of hits(SLASH_DATE, folded)) { const d = monthDay(today, Number(h.g[1]), Number(h.g[2]), h.g[3] ? Number(h.g[3]) : undefined); if (d) cands.push({ ...h, date: d }); }
      for (const h of hits(DAY_ONLY, folded)) {
        const d = Number(h.g[1]);
        if (d < 1 || d > 31) continue;
        let y = today.getFullYear();
        let m = today.getMonth();
        if (d < today.getDate()) { m += 1; if (m > 11) { m = 0; y += 1; } }
        if (d <= new Date(y, m + 1, 0).getDate()) cands.push({ ...h, date: new Date(y, m, d) });
      }
      for (const c of cands.sort((a, b) => a.start - b.start)) {
        if (!free(c.start, c.end)) continue;
        date = c.date;
        claim("DAY", c.start, c.end, dayLabel(c.date, today));
        break;
      }
    }
  }

  // --- time
  let time: { hour: number; minute: number } | undefined;
  if (!off.has("TIME")) {
    for (const h of hits(TIME, folded)) {
      const r = widen(folded, h);
      if (!free(r.start, r.end)) continue;
      const parsed = parseTime(h, periodOf(folded, h));
      if (!parsed) continue;
      time = parsed;
      claim("TIME", r.start, r.end, `${String(parsed.hour).padStart(2, "0")}:${String(parsed.minute).padStart(2, "0")}`);
      break;
    }
  }

  // --- importance
  let important = false;
  if (!off.has("IMPORTANT") && amount === undefined) {
    const h = hits(IMPORTANT, folded)[0];
    // "gấp" (urgent) folds to the same letters as "gặp" (meet): require the real accent.
    if (h && free(h.start, h.end) && (h.g[0] !== "gap" || typed.slice(h.start, h.end).toLowerCase() === "gấp")) {
      important = true;
      claim("IMPORTANT", h.start, h.end, "Quan trọng");
    }
  }

  const title = cleanTitle(typed, taken);
  if (!title) return plain(typed);
  const at = (d: Date, t?: { hour: number; minute: number }) => new Date(d.getFullYear(), d.getMonth(), d.getDate(), t?.hour ?? 0, t?.minute ?? 0).getTime();
  const sorted = spans.sort((a, b) => a.start - b.start);

  if (amount !== undefined) {
    // Spending happened on the day said, or today; it never repeats or reminds.
    return {
      title, type: "EXPENSE", whenAt: at(date ?? today, time), allDay: !time, important: false, spans: sorted,
      amount, category: guessCategory(title, history),
    };
  }

  // A time with no day means today, or tomorrow once that time has passed.
  let whenAt: number | undefined;
  let allDay = false;
  if (date && time) whenAt = at(date, time);
  else if (date) { whenAt = at(date); allDay = true; }
  else if (time) {
    whenAt = at(today, time);
    if (whenAt < now) whenAt = at(addDays(today, 1), time);
  }
  // A repeat needs a date to repeat from: today if only "mỗi ngày" was said.
  if (recurrence && whenAt === undefined) { whenAt = at(today); allDay = true; }

  const foldedTitle = fold(title);
  const event = whenAt !== undefined && EVENT_WORDS.some((w) => new RegExp(`\\b${w}\\b`).test(foldedTitle));
  return {
    title, type: event ? "EVENT" : "TASK", allDay, important, spans: sorted,
    ...(whenAt !== undefined ? { whenAt } : {}), ...(recurrence ? { recurrence } : {}),
  };
}
