import type { Item, ItemType, Recurrence } from "./model";

/**
 * Sharing between people. Same shapes on the server, the web app and the Android app.
 *
 * Two people become contacts by a one-time code (one makes it, the other enters it). After that, either
 * of them may share ONE item at a time with any of their contacts. The person who receives it gets a
 * read-only view (`ShareDoc`) that the server writes; the original item never leaves its owner's data.
 */

/** Item types that can be shared. Expenses (money) and everything about customers stay private. */
export const SHAREABLE: readonly ItemType[] = ["TASK", "EVENT", "NOTE"];
export const isShareable = (t: ItemType): boolean => SHAREABLE.includes(t);

export const SHARE_LIMITS = {
  /** Contacts per person. */
  contacts: 100,
  /** People one item can be shared with. */
  audience: 50,
  /** Shared items one person can receive (and the client listens to). */
  received: 500,
  /** Minutes a link code stays valid. */
  codeMinutes: 10,
  /** Wrong codes a person may enter per hour before entering is locked for the rest of it. */
  failuresPerHour: 5,
  aliasChars: 40,
} as const;

/** One person I am linked with, as I see them: the name I gave them is mine alone. */
export interface Contact {
  /** Their uid; also the document id. */
  uid: string;
  /** What I call them ("Em Lan"). Only I see it. */
  alias: string;
  /** Their Google display name when we linked (falls back to the alias when Google has none). */
  googleName: string;
  /** Their address with most of the name hidden ("lan•••@gmail.com"). */
  maskedEmail: string;
  linkedAt: number;
  /** Push me when they share something new or change something shared. */
  notify: boolean;
}

/** The read-only view of a shared item. Written only by the server; only these fields ever leave the owner. */
export interface ShareDoc {
  /** `${ownerUid}_${itemId}` */
  id: string;
  ownerUid: string;
  /** The owner's Google name, for a recipient whose contact list has not loaded yet. */
  ownerName: string;
  itemId: string;
  /** Uids that may read it. */
  audience: string[];
  type: ItemType;
  status: "OPEN" | "DONE";
  title: string;
  details: string;
  whenAt?: number;
  allDay: boolean;
  place?: string;
  recurrence?: Recurrence;
  important?: boolean;
  doneAt?: number;
  createdAt: number;
  updatedAt: number;
}

/** The fields of an item a recipient may see. Customer, licence, money, people, quotes and sources never do. */
export type SharedFields = Pick<ShareDoc, "type" | "status" | "title" | "details" | "whenAt" | "allDay" | "place" | "recurrence" | "important" | "doneAt" | "createdAt">;

export function sharedFields(item: Item): SharedFields {
  const out: SharedFields = {
    type: item.type,
    status: item.status === "DONE" ? "DONE" : "OPEN",
    title: item.title,
    details: item.details ?? "",
    allDay: item.allDay === true,
    createdAt: item.createdAt,
  };
  if (item.whenAt !== undefined && item.whenAt !== null) out.whenAt = item.whenAt;
  if (item.place) out.place = item.place;
  if (item.recurrence) out.recurrence = item.recurrence;
  if (item.important) out.important = true;
  if (item.status === "DONE" && item.doneAt !== undefined && item.doneAt !== null) out.doneAt = item.doneAt;
  return out;
}

export const shareId = (ownerUid: string, itemId: string): string => `${ownerUid}_${itemId}`;

/** The id of the link between two people: both uids, sorted, so either side computes the same one. */
export const pairId = (a: string, b: string): string => (a < b ? `${a}_${b}` : `${b}_${a}`);

/** The next occurrence of a repeating task is stored as `${parentId}_n${whenAt}`; it inherits the parent's audience. */
export function parentOfRepeat(itemId: string): string | null {
  const m = /^(.+)_n\d+$/.exec(itemId);
  return m ? m[1] : null;
}

// ---------------------------------------------------------------- link codes

/** No I, O, 0 or 1: nothing that reads as something else when said aloud or copied by hand. */
export const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const CODE_LENGTH = 8;

/** "k7m4 qx-9d" → "K7M4QX9D"; null when it cannot be a code. */
export function normalizeCode(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const s = raw.toUpperCase().replace(/[\s-]/g, "");
  if (s.length !== CODE_LENGTH) return null;
  for (const ch of s) if (!CODE_ALPHABET.includes(ch)) return null;
  return s;
}

/** "K7M4QX9D" → "K7M4 QX9D" */
export const formatCode = (code: string): string => `${code.slice(0, 4)} ${code.slice(4)}`;

/** "lan.nguyen@gmail.com" → "lan•••@gmail.com" */
export function maskEmail(email: string): string {
  const [name, domain] = email.split("@");
  if (!domain) return "";
  return `${name.slice(0, Math.min(3, Math.max(1, name.length - 1)))}•••@${domain}`;
}

/** Trimmed, collapsed and cut to length; empty when there is nothing left. */
export function cleanAlias(raw: unknown): string {
  if (typeof raw !== "string") return "";
  return raw.replace(/[\u0000-\u001f\u007f]/g, "").replace(/\s+/g, " ").trim().slice(0, SHARE_LIMITS.aliasChars);
}

/** First letter of a name, for the round badge next to a shared item. */
export function initial(name: string): string {
  const ch = name.trim().match(/\p{L}|\p{N}/u)?.[0] ?? "?";
  return ch.toUpperCase();
}

// ---------------------------------------------------------------- what is worth a push

export type ShareChange = "new" | "edited" | "deleted" | "unshared" | null;

/**
 * Whether a change of the original item is news for the people it is shared with. Ticking it off and the
 * automatic move of a repeating appointment to its next date are not: only a real edit is.
 */
export function changeKind(before: SharedFields | null, after: SharedFields | null, nowMs: number): ShareChange {
  if (!before && !after) return null;
  if (!before) return "new";
  if (!after) return "deleted";
  const keys: (keyof SharedFields)[] = ["type", "title", "details", "whenAt", "allDay", "place", "recurrence", "important"];
  const changed = keys.filter((k) => (before[k] ?? null) !== (after[k] ?? null));
  if (changed.length === 0) return null;
  // A repeating item whose time passed rolls forward on its own; nobody edited anything.
  const rolled = changed.length === 1 && changed[0] === "whenAt" && !!after.recurrence
    && before.whenAt !== undefined && after.whenAt !== undefined && before.whenAt < nowMs && after.whenAt > before.whenAt;
  return rolled ? null : "edited";
}

/** The fields whose change a push names, in the order a person cares about them. */
export function changedLabels(before: SharedFields, after: SharedFields): string[] {
  const out: string[] = [];
  if ((before.whenAt ?? null) !== (after.whenAt ?? null) || before.allDay !== after.allDay) out.push("thời gian");
  if (before.title !== after.title) out.push("tiêu đề");
  if ((before.place ?? "") !== (after.place ?? "")) out.push("địa điểm");
  if (before.details !== after.details) out.push("ghi chú");
  if ((before.recurrence ?? null) !== (after.recurrence ?? null)) out.push("lặp lại");
  if ((before.important ?? false) !== (after.important ?? false)) out.push("mức quan trọng");
  if (before.type !== after.type) out.push("loại");
  return out;
}
