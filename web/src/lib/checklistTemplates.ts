import { entriesFrom, parseLines } from "./checklist";
import { CHECKLIST_MAX_ENTRIES, RECURRENCES, RECURRENCE_LABEL, type Item, type ItemType, type Recurrence } from "./model";
import { nextAfter, startOfDay } from "./schedule";
import { createStore } from "./store";
import { fold } from "./views";

/**
 * Checklist templates ("Ra khỏi nhà", "Giao hàng", "Chuẩn bị họp"): a name, the lines, and optionally when it
 * happens (every day at 07:30). One tap in quick add turns a template into a task or appointment with its
 * checklist. Managed in Cài đặt → Mẫu checklist. Kept in this browser only, like the quick-add lines.
 */
export interface ChecklistTemplate {
  id: string;
  name: string;
  items: string[];
  /** What using it creates. */
  type: Extract<ItemType, "TASK" | "EVENT">;
  recurrence?: Recurrence;
  /** "07:30"; without it the item is all-day (or undated when there is no recurrence either). */
  time?: string;
  uses: number;
  lastUsed: number;
}

export const MAX_TEMPLATES = 30;
export const MAX_NAME = 60;
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

const make = (id: string, name: string, items: string[], extra: Partial<ChecklistTemplate> = {}): ChecklistTemplate => ({
  id, name, items, type: "TASK", uses: 0, lastUsed: 0, ...extra,
});

/** Shown until the person edits the list, so the feature explains itself on first use. */
export const DEFAULTS: ChecklistTemplate[] = [
  make("default-leave-home", "Ra khỏi nhà", ["Ví", "Chìa khoá", "Điện thoại và sạc", "Thuốc", "Thẻ xe"], { recurrence: "DAILY", time: "07:30" }),
  make("default-delivery", "Giao hàng cho khách", ["Đủ hàng, đúng số lượng", "Hoá đơn", "Biên bản giao nhận", "Báo khách trước khi tới"]),
  make("default-meeting", "Chuẩn bị họp", ["Tài liệu, báo giá", "Laptop và sạc", "Xem lại ghi chú lần trước"], { type: "EVENT" }),
];

export type TemplateDraft = Pick<ChecklistTemplate, "name" | "type" | "recurrence" | "time"> & { lines: string };

/** What the form needs to say is wrong, or the cleaned template (without id and counters). */
export function validate(list: ChecklistTemplate[], draft: TemplateDraft, selfId?: string): string | Omit<ChecklistTemplate, "id" | "uses" | "lastUsed"> {
  const name = draft.name.trim().replace(/\s+/g, " ").slice(0, MAX_NAME);
  if (!name) return "Cần nhập tên mẫu";
  if (list.some((t) => t.id !== selfId && fold(t.name) === fold(name))) return "Đã có mẫu trùng tên";
  const items = parseLines(draft.lines, false);
  if (items.length === 0) return "Cần ít nhất một mục (mỗi dòng một mục)";
  if (draft.time && !TIME.test(draft.time)) return "Giờ chưa đúng";
  if (draft.recurrence && !draft.time && draft.type === "EVENT") return "Lịch hẹn lặp cần có giờ";
  const out: Omit<ChecklistTemplate, "id" | "uses" | "lastUsed"> = { name, items: items.slice(0, CHECKLIST_MAX_ENTRIES), type: draft.type };
  if (draft.recurrence) out.recurrence = draft.recurrence;
  if (draft.time) out.time = draft.time;
  return out;
}

let n = 0;
const newId = () => `tpl-${Date.now().toString(36)}${(n++).toString(36)}`;

export function addTemplate(list: ChecklistTemplate[], t: Omit<ChecklistTemplate, "id" | "uses" | "lastUsed">): ChecklistTemplate[] {
  if (list.length >= MAX_TEMPLATES) return list;
  return [...list, { ...t, id: newId(), uses: 0, lastUsed: 0 }];
}

export function updateTemplate(list: ChecklistTemplate[], id: string, t: Omit<ChecklistTemplate, "id" | "uses" | "lastUsed">): ChecklistTemplate[] {
  return list.map((x) => (x.id === id ? { ...t, id, uses: x.uses, lastUsed: x.lastUsed } : x));
}

export const removeTemplate = (list: ChecklistTemplate[], id: string): ChecklistTemplate[] => list.filter((t) => t.id !== id);

/** Puts back any built-in template (by name) the person deleted, without touching the rest. */
export function restoreDefaults(list: ChecklistTemplate[]): ChecklistTemplate[] {
  const have = new Set(list.map((t) => fold(t.name)));
  const missing = DEFAULTS.filter((d) => !have.has(fold(d.name)));
  return [...list, ...missing.slice(0, Math.max(0, MAX_TEMPLATES - list.length))];
}

export const usedTemplate = (list: ChecklistTemplate[], id: string, now: number): ChecklistTemplate[] =>
  list.map((t) => (t.id === id ? { ...t, uses: t.uses + 1, lastUsed: now } : t));

/** Most used first; ties go to the latest used, then to the saved order. */
export const byUse = (list: ChecklistTemplate[]): ChecklistTemplate[] =>
  list.map((t, i) => ({ t, i })).sort((a, b) => b.t.uses - a.t.uses || b.t.lastUsed - a.t.lastUsed || a.i - b.i).map((x) => x.t);

/** Templates whose name contains every word typed (accents and case ignored). */
export function matching(list: ChecklistTemplate[], query: string): ChecklistTemplate[] {
  const ws = fold(query).split(/[^a-z0-9]+/).filter(Boolean);
  if (ws.length === 0) return [];
  return byUse(list).filter((t) => ws.every((w) => fold(t.name).includes(w)));
}

/** "Mỗi ngày 07:30", "Lịch hẹn · 14:00", "Việc không hẹn giờ": what using the template creates. */
export function scheduleLabel(t: Pick<ChecklistTemplate, "type" | "recurrence" | "time">): string {
  const rec = t.recurrence ? RECURRENCE_LABEL[t.recurrence] : "";
  const parts = [t.type === "EVENT" ? "Lịch hẹn" : "Việc", rec, t.time ?? ""].filter(Boolean);
  return parts.join(" · ");
}

/** Has its own time or repeat, so using it needs no further questions. Otherwise the editor opens to pick a day. */
export const isScheduled = (t: Pick<ChecklistTemplate, "recurrence" | "time">): boolean => Boolean(t.recurrence || t.time);

/**
 * The item a template creates (without id and createdAt handled by the caller). A repeating template starts
 * at its next time: today when the time is still ahead, else the next occurrence, so it is never overdue at birth.
 */
export function instantiate(t: ChecklistTemplate, id: string, now: number): Item {
  const item: Item = { id, type: t.type, status: "OPEN", title: t.name, details: "", allDay: !t.time, createdAt: now, checklist: entriesFrom(t.items) };
  if (!isScheduled(t)) {
    if (t.type === "EVENT") item.whenAt = now;
    return item;
  }
  const day = startOfDay(now);
  const [h, m] = (t.time ?? "00:00").split(":").map(Number);
  const todayAt = new Date(day).setHours(h, m, 0, 0);
  const ahead = t.time ? todayAt > now : true;
  item.whenAt = ahead ? (t.time ? todayAt : day) : t.recurrence ? nextAfter(todayAt, t.recurrence, now) : todayAt;
  if (t.recurrence) item.recurrence = t.recurrence;
  return item;
}

const KEY = "vhub.checklistTemplates";

function load(): ChecklistTemplate[] {
  try {
    const raw = typeof localStorage === "undefined" ? null : localStorage.getItem(KEY);
    if (raw === null) return DEFAULTS;
    const a: unknown = JSON.parse(raw);
    if (!Array.isArray(a)) return [];
    return a.flatMap((o): ChecklistTemplate[] => {
      const r = o as Partial<ChecklistTemplate> | null;
      if (!r || typeof r.id !== "string" || typeof r.name !== "string" || !r.name.trim() || !Array.isArray(r.items)) return [];
      const items = r.items.filter((x): x is string => typeof x === "string" && x.trim() !== "").slice(0, CHECKLIST_MAX_ENTRIES);
      if (items.length === 0) return [];
      const out: ChecklistTemplate = { id: r.id, name: r.name.trim().slice(0, MAX_NAME), items, type: r.type === "EVENT" ? "EVENT" : "TASK", uses: Number(r.uses) || 0, lastUsed: Number(r.lastUsed) || 0 };
      if (r.recurrence && (RECURRENCES as readonly string[]).includes(r.recurrence)) out.recurrence = r.recurrence;
      if (typeof r.time === "string" && TIME.test(r.time)) out.time = r.time;
      return [out];
    });
  } catch {
    return DEFAULTS;
  }
}

export const checklistTemplates = createStore<ChecklistTemplate[]>(load());

/** Saves the list; the app works the same if the browser refuses (private mode), until it is closed. */
export function setChecklistTemplates(list: ChecklistTemplate[]): void {
  checklistTemplates.set(list);
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* kept in memory only */
  }
}
