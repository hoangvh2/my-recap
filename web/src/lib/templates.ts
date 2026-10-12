import type { Item, ItemType } from "./model";
import { createStore } from "./store";
import { parseQuick } from "./quickadd";
import { fold } from "./views";

/**
 * Saved quick-add lines ("cà phê 45k", "đổ xăng …k"). A line without a blank is added in one tap; a line
 * with a blank ("…") is put in the quick-add box with the cursor on the blank. The empty box offers only the
 * most used line of each kind; the others come up as the user types words they contain. Port of the Android
 * `QuickTemplates`. Kept in this browser only (a per-device convenience).
 */
export interface QuickTemplate {
  text: string;
  uses: number;
  lastUsed: number;
}

/** What the user types for "fill this in"; "..." is accepted too. */
export const BLANK = "…";

const line = (text: string): QuickTemplate => ({ text, uses: 0, lastUsed: 0 });

/** Shown until the user edits the list, so the feature explains itself on first use. */
export const DEFAULTS: QuickTemplate[] = ["gọi … mai 9h", "họp … thứ 2 9h", "cà phê 30k", "ăn trưa …k", "đổ xăng …k"].map(line);

/** The kinds the empty box offers one line for, in this order. */
export const FEATURED_TYPES: ItemType[] = ["TASK", "EVENT", "EXPENSE"];

export const normalize = (text: string): string => text.replaceAll("...", BLANK).trim().replace(/\s+/g, " ");
export const hasBlank = (text: string): boolean => normalize(text).includes(BLANK);
const same = (a: string, b: string) => fold(normalize(a)) === fold(normalize(b));

/** The text to put in the box and where the cursor goes: the first blank, else the end. */
export function fill(text: string): { text: string; cursor: number } {
  const t = normalize(text);
  const at = t.indexOf(BLANK);
  if (at < 0) return { text: `${t} `, cursor: t.length + 1 };
  const before = t.slice(0, at);
  const after = t.slice(at + BLANK.length).replaceAll(BLANK, "");
  // "gọi … mai" → "gọi | mai" (a space on each side of the cursor); "ăn trưa …k" → "ăn trưa |k".
  const head = before === "" || before.endsWith(" ") ? before : `${before} `;
  const tail = after.startsWith(" ") ? ` ${after.trimStart()}` : after;
  return { text: head + tail, cursor: head.length };
}

/** What a line adds: an expense, an appointment or a task. A blank is read as "1". */
export function typeOf(text: string, now: number): ItemType {
  return parseQuick(normalize(text).replaceAll(BLANK, "1"), now).type;
}

/** Adds [text] unless an equal line (ignoring accents and case) is already saved. */
export function addTemplate(list: QuickTemplate[], text: string): QuickTemplate[] {
  const t = normalize(text);
  return !t || list.some((x) => same(x.text, t)) ? list : [...list, line(t)];
}

/** Replaces the line at [index]; blank text or a duplicate of another line leaves the list as is. */
export function editTemplate(list: QuickTemplate[], index: number, text: string): QuickTemplate[] {
  const t = normalize(text);
  if (index < 0 || index >= list.length || !t || list.some((x, i) => i !== index && same(x.text, t))) return list;
  return list.map((x, i) => (i === index ? { ...x, text: t } : x));
}

/** Counts one more use of [template], so the most used line of its kind is the one offered. */
export const usedTemplate = (list: QuickTemplate[], template: QuickTemplate, now: number): QuickTemplate[] =>
  list.map((x) => (same(x.text, template.text) ? { ...x, uses: x.uses + 1, lastUsed: now } : x));

/** Most used first; ties go to the latest used, then to the order saved. */
export const byUse = (list: QuickTemplate[]): QuickTemplate[] =>
  list.map((t, i) => ({ t, i })).sort((a, b) => b.t.uses - a.t.uses || b.t.lastUsed - a.t.lastUsed || a.i - b.i).map((x) => x.t);

/** The most used line of each kind in FEATURED_TYPES, for the empty box. */
export function featured(list: QuickTemplate[], now: number): QuickTemplate[] {
  const ranked = byUse(list);
  return FEATURED_TYPES.flatMap((type) => {
    const hit = ranked.find((t) => typeOf(t.text, now) === type);
    return hit ? [hit] : [];
  });
}

const words = (s: string) => fold(s).split(/[^a-z0-9]+/).filter(Boolean);

/**
 * Lines containing every word typed so far, accents and case ignored, each word anywhere in the line
 * ("ca" finds "cà phê 30k" and "ăn trưa cá …k"). Lines starting with the first word come first, then the
 * most used. A line the box already holds word for word is left out.
 */
export function matching(list: QuickTemplate[], query: string, limit = 4): QuickTemplate[] {
  const ws = words(query);
  if (ws.length === 0) return [];
  const q = fold(query);
  return byUse(list)
    .filter((t) => {
      const f = fold(normalize(t.text).replaceAll(BLANK, " "));
      return f !== q && ws.every((w) => f.includes(w));
    })
    .map((t, i) => ({ t, i, first: fold(t.text).startsWith(ws[0]) }))
    .sort((a, b) => Number(b.first) - Number(a.first) || a.i - b.i)
    .map((x) => x.t)
    .slice(0, limit);
}

const MIN_REPEATS = 3;
const WINDOW_DAYS = 60;

/** The value at least half of [values] share, if any. */
function usual<T>(values: T[]): T | undefined {
  if (values.length === 0) return undefined;
  const counts = new Map<T, number>();
  for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1);
  const [value, count] = [...counts.entries()].sort((a, b) => b[1] - a[1])[0];
  return count * 2 >= values.length ? value : undefined;
}

/** "45k", "5tr", "1.25tr"; the quick-add parser reads each back to the same amount. */
export function shortAmount(amount: number): string {
  if (amount >= 1_000_000 && amount % 10_000 === 0) {
    const millions = Math.floor(amount / 1_000_000);
    const rest = String((amount % 1_000_000) / 10_000).padStart(2, "0").replace(/0+$/, "");
    return rest ? `${millions}.${rest}tr` : `${millions}tr`;
  }
  return amount % 1_000 === 0 ? `${amount / 1_000}k` : `${amount}đ`;
}

/**
 * Lines worth saving, from what the user keeps adding: a title used 3+ times in the last 60 days. An expense
 * keeps its usual amount when most entries share it ("cà phê 45k"), else leaves it blank ("đổ xăng …k"); a task
 * or appointment keeps its usual hour.
 */
export function suggest(history: Item[], saved: QuickTemplate[], now: number, limit = 5): string[] {
  const since = now - WINDOW_DAYS * 86_400_000;
  const groups = new Map<string, Item[]>();
  for (const i of history) {
    if (i.status === "DRAFT" || i.createdAt < since || !i.title.trim() || i.type === "NOTE") continue;
    const k = fold(i.title);
    groups.set(k, [...(groups.get(k) ?? []), i]);
  }
  return [...groups.values()]
    .filter((g) => g.length >= MIN_REPEATS)
    .sort((a, b) => b.length - a.length)
    .flatMap((g) => {
      const latest = g.reduce((a, b) => (b.createdAt > a.createdAt ? b : a));
      const title = latest.title.trim();
      let text: string;
      if (latest.type === "EXPENSE") {
        const u = usual(g.flatMap((i) => (i.amount ? [i.amount] : [])));
        text = u !== undefined ? `${title} ${shortAmount(u)}` : `${title} ${BLANK}k`;
      } else {
        const hours = g.filter((i) => i.whenAt !== undefined && !i.allDay).map((i) => new Date(i.whenAt!)).map((d) => (d.getMinutes() === 0 ? `${d.getHours()}h` : `${d.getHours()}h${String(d.getMinutes()).padStart(2, "0")}`));
        const u = usual(hours);
        text = u ? `${title} ${u}` : title;
      }
      return saved.some((s) => same(s.text, text) || same(s.text.split(BLANK)[0].trim(), title)) ? [] : [text];
    })
    .slice(0, limit);
}

const KEY = "vhub.quickTemplates";

function load(): QuickTemplate[] {
  try {
    const raw = typeof localStorage === "undefined" ? null : localStorage.getItem(KEY);
    if (raw === null) return DEFAULTS;
    const a: unknown = JSON.parse(raw);
    if (!Array.isArray(a)) return [];
    return a.flatMap((o): QuickTemplate[] => {
      const r = o as Partial<QuickTemplate> | null;
      return r && typeof r.text === "string" && r.text.trim()
        ? [{ text: normalize(r.text), uses: Number(r.uses) || 0, lastUsed: Number(r.lastUsed) || 0 }]
        : [];
    });
  } catch {
    return DEFAULTS;
  }
}

export const templates = createStore<QuickTemplate[]>(load());

/** Saves the list; the app works the same if the browser refuses (private mode), until it is closed. */
export function setTemplates(list: QuickTemplate[]): void {
  templates.set(list);
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* kept in memory only */
  }
}

