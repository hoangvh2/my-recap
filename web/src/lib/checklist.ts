import {
  CHECKLIST_MAX_ENTRIES, CHECKLIST_MAX_TEXT, canChecklist, type ChecklistEntry, type Item,
} from "./model";

/**
 * The checklist on a task or appointment ("ra khỏi nhà: ví, chìa khoá, sạc"). Everything here is pure; the
 * screens decide when to save. A repeating item starts every occurrence unticked (see `reset`).
 */

export const entriesOf = (i: Pick<Item, "type" | "checklist">): ChecklistEntry[] => (canChecklist(i.type) ? (i.checklist ?? []) : []);

export interface Progress {
  done: number;
  total: number;
  remaining: ChecklistEntry[];
  ticked: ChecklistEntry[];
  complete: boolean;
}

export function progress(i: Pick<Item, "type" | "checklist">): Progress {
  const all = entriesOf(i);
  const ticked = all.filter((e) => e.done === true);
  const remaining = all.filter((e) => e.done !== true);
  return { done: ticked.length, total: all.length, remaining, ticked, complete: all.length > 0 && remaining.length === 0 };
}

export const hasChecklist = (i: Pick<Item, "type" | "checklist">): boolean => entriesOf(i).length > 0;

/** "Còn: Ví · Chìa khoá · Sạc · +2": the first [show] things still missing, never the whole list. */
export function remainingLabel(i: Pick<Item, "type" | "checklist">, show = 3): string {
  const { remaining } = progress(i);
  if (remaining.length === 0) return "";
  const names = remaining.slice(0, show).map((e) => e.text);
  const more = remaining.length - names.length;
  return `Còn: ${names.join(" · ")}${more > 0 ? ` · +${more}` : ""}`;
}

/** What the chip says: "2/5", or "Đủ 5/5" once everything is ticked (words, not only colour). */
export function chipLabel(i: Pick<Item, "type" | "checklist">): string {
  const p = progress(i);
  return p.complete ? `Đủ ${p.total}/${p.total}` : `${p.done}/${p.total}`;
}

const withEntries = (item: Item, entries: ChecklistEntry[]): Item => {
  const out: Item = { ...item };
  if (entries.length > 0) out.checklist = entries;
  else delete out.checklist;
  return out;
};

/** Ticks or unticks one line. Unknown ids leave the item as it is. */
export function toggleEntry(item: Item, id: string): Item {
  const list = entriesOf(item);
  if (!list.some((e) => e.id === id)) return item;
  return withEntries(item, list.map((e) => (e.id === id ? tick(e, e.done !== true) : e)));
}

/** "Đủ rồi": everything ticked. */
export const tickAll = (item: Item): Item => withEntries(item, entriesOf(item).map((e) => tick(e, true)));

/** Every line unticked: a new occurrence of a repeating item, or "Đặt lại". */
export const reset = (item: Item): Item => withEntries(item, entriesOf(item).map((e) => tick(e, false)));

function tick(e: ChecklistEntry, done: boolean): ChecklistEntry {
  const out: ChecklistEntry = { id: e.id, text: e.text };
  if (done) out.done = true;
  return out;
}

export const withChecklist = (item: Item, entries: ChecklistEntry[]): Item => withEntries(item, entries);

const clip = (s: string) => s.trim().replace(/\s+/g, " ").slice(0, CHECKLIST_MAX_TEXT);

/** Text → lines: one per line, or split at commas and semicolons when it is a single line. Blanks and repeats dropped. */
export function parseLines(text: string, splitCommas = true): string[] {
  const parts = text.includes("\n") || !splitCommas ? text.split(/\r?\n/) : text.split(/[,;]/);
  const seen = new Set<string>();
  const out: string[] = [];
  for (const p of parts) {
    const t = clip(p.replace(/^\s*(?:[-*•]|\[[ xX]?\])\s*/, ""));
    const key = t.toLocaleLowerCase("vi");
    if (t && !seen.has(key)) {
      seen.add(key);
      out.push(t);
    }
  }
  return out.slice(0, CHECKLIST_MAX_ENTRIES);
}

let counter = 0;
const entryId = () => `c${Date.now().toString(36)}${(counter++).toString(36)}${Math.random().toString(36).slice(2, 5)}`;

/** New entries for [texts], skipping lines already on the list (accents and case matter little: only case is ignored). Capped at the limit. */
export function appendLines(list: ChecklistEntry[], texts: string[]): ChecklistEntry[] {
  const have = new Set(list.map((e) => e.text.toLocaleLowerCase("vi")));
  const added: ChecklistEntry[] = [];
  for (const raw of texts) {
    const t = clip(raw);
    if (!t || have.has(t.toLocaleLowerCase("vi"))) continue;
    if (list.length + added.length >= CHECKLIST_MAX_ENTRIES) break;
    have.add(t.toLocaleLowerCase("vi"));
    added.push({ id: entryId(), text: t });
  }
  return added.length === 0 ? list : [...list, ...added];
}

/** Entries from plain lines, all unticked. */
export const entriesFrom = (texts: string[]): ChecklistEntry[] => appendLines([], texts);

/**
 * "ra khỏi nhà 7h30 hằng ngày: ví, chìa khoá, sạc" → the part before the colon is the title line and the
 * rest is the checklist. Needs a colon that is not part of a time ("9:30") and at least two lines after it,
 * so "gọi anh Nam: mai 9h" stays an ordinary quick-add line.
 */
export function splitChecklistLine(line: string): { head: string; lines: string[] } | null {
  const t = line.trim();
  const at = [...t].findIndex((c, i) => c === ":" && !(/\d/.test(t[i - 1] ?? "") && /\d/.test(t[i + 1] ?? "")));
  if (at <= 0) return null;
  const head = t.slice(0, at).trim();
  const lines = parseLines(t.slice(at + 1));
  return head && lines.length >= 2 ? { head, lines } : null;
}
