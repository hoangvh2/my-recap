import type { Item } from "./model";
import type { Contact, ShareDoc } from "../../../shared/share";

/** Whose items a list shows: everything, only mine, or only what others shared with me (optionally one person's). */
export type OwnerFilter = { who: "all" | "mine" | "shared"; person?: string };

export const OWNER_FILTERS: { key: OwnerFilter["who"]; label: string }[] = [
  { key: "all", label: "Tất cả" },
  { key: "mine", label: "Của tôi" },
  { key: "shared", label: "Được chia sẻ" },
];

/**
 * My items plus what others shared with me, as one list the existing views can draw. Received views use the
 * share id, so they never collide with mine and are recognised by the rows (read-only, labelled).
 */
export function withReceived(mine: Item[], received: ShareDoc[], f: OwnerFilter): Item[] {
  const theirs = received.filter((d) => !f.person || d.ownerUid === f.person).map(asItem);
  if (f.who === "mine") return mine;
  if (f.who === "shared") return theirs;
  return [...mine, ...theirs];
}

/** People who shared something with me, with how many items each, most first. */
export function sharers(received: ShareDoc[]): { uid: string; count: number }[] {
  const m = new Map<string, number>();
  for (const d of received) m.set(d.ownerUid, (m.get(d.ownerUid) ?? 0) + 1);
  return [...m].map(([uid, count]) => ({ uid, count })).sort((a, b) => b.count - a.count);
}

/** How many of my items each contact can see. */
export function sharedWithCounts(audiences: Map<string, string[]>): Map<string, number> {
  const m = new Map<string, number>();
  for (const list of audiences.values()) for (const u of list) m.set(u, (m.get(u) ?? 0) + 1);
  return m;
}

/** The name I gave the owner of a shared item (their Google name until we linked or if the list is loading). */
export function ownerLabel(d: Pick<ShareDoc, "ownerUid" | "ownerName">, list: Contact[]): string {
  return list.find((c) => c.uid === d.ownerUid)?.alias || d.ownerName;
}

/** A shared view in the shape the lists and the timeline already draw. Its id is the share id, never one of mine. */
export function asItem(d: ShareDoc): Item {
  const i: Item = { id: d.id, type: d.type, status: d.status, title: d.title, details: d.details, allDay: d.allDay, createdAt: d.createdAt };
  if (d.whenAt !== undefined) i.whenAt = d.whenAt;
  if (d.place) i.place = d.place;
  if (d.recurrence) i.recurrence = d.recurrence;
  if (d.important) i.important = true;
  if (d.doneAt !== undefined) i.doneAt = d.doneAt;
  return i;
}
