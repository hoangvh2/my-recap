import {
  collection, deleteDoc, doc, limit, onSnapshot, orderBy, query, setDoc, writeBatch, type FirestoreError,
} from "firebase/firestore";
import { db } from "./firebase";
import { createStore } from "./lib/store";
import type { Capture, Item, ItemType } from "./lib/model";
import { nextInstance, rollForward } from "./lib/schedule";

export type SyncState = "connecting" | "ready" | "denied" | "error";

export const items = createStore<Item[]>([]);
export const captures = createStore<Capture[]>([]);
export const syncState = createStore<SyncState>("connecting");

const LIMIT_ITEMS = 1_000;
const LIMIT_CAPTURES = 100;

/** Firestore rejects `undefined`; drop absent optional fields. */
export function clean(item: Item): Item {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(item)) if (v !== undefined && v !== null) out[k] = v;
  return out as unknown as Item;
}

export const newId = () => crypto.randomUUID().replaceAll("-", "");

const itemDoc = (uid: string, id: string) => doc(db, "users", uid, "items", id);

/** Live-subscribes to the signed-in person's items and captures. Returns the unsubscribe function. */
export function startSync(uid: string): () => void {
  syncState.set("connecting");
  let first = true;
  const onError = (e: FirestoreError) => {
    syncState.set(e.code === "permission-denied" ? "denied" : "error");
  };
  const offItems = onSnapshot(
    query(collection(db, "users", uid, "items"), orderBy("createdAt", "desc"), limit(LIMIT_ITEMS)),
    (snap) => {
      items.set(snap.docs.map((d) => d.data() as Item));
      syncState.set("ready");
      if (first) first = false;
      void rollRepeatingEvents(uid);
    },
    onError,
  );
  const offCaptures = onSnapshot(
    query(collection(db, "users", uid, "captures"), orderBy("createdAt", "desc"), limit(LIMIT_CAPTURES)),
    (snap) => captures.set(snap.docs.map((d) => d.data() as Capture)),
    () => undefined,
  );
  return () => {
    offItems();
    offCaptures();
    items.set([]);
    captures.set([]);
  };
}

/** Repeating appointments whose occurrence has passed move to the next one (idempotent across devices). */
export async function rollRepeatingEvents(uid: string, now = Date.now()): Promise<void> {
  const moved = items.get().map((i) => rollForward(i, now)).filter((i): i is Item => i !== null);
  if (moved.length === 0) return;
  const batch = writeBatch(db);
  for (const i of moved) batch.set(itemDoc(uid, i.id), clean(i));
  await batch.commit().catch(() => undefined);
}

export function blankItem(type: ItemType, now = Date.now()): Item {
  return { id: newId(), type, status: "OPEN", title: "", details: "", allDay: type === "EXPENSE", createdAt: now, ...(type === "EXPENSE" ? { category: "Khác" } : {}) };
}

/** Creates or replaces one item. */
export function saveItem(uid: string, item: Item): Promise<void> {
  return setDoc(itemDoc(uid, item.id), clean(item));
}

export function deleteItems(uid: string, ids: string[]): Promise<void> {
  if (ids.length === 1) return deleteDoc(itemDoc(uid, ids[0]));
  const batch = writeBatch(db);
  for (const id of ids) batch.delete(itemDoc(uid, id));
  return batch.commit();
}

/** Drafts the owner has reviewed become real items. */
export function confirmDrafts(uid: string, drafts: Item[]): Promise<void> {
  const batch = writeBatch(db);
  for (const d of drafts) batch.set(itemDoc(uid, d.id), clean({ ...d, status: "OPEN" }));
  return batch.commit();
}

/**
 * Ticks a task off, or back on. Finishing a repeating task also creates its next occurrence in the
 * same batch, under an id derived from the date, so a double tap or a second device cannot duplicate it.
 */
export function setDone(uid: string, item: Item, done: boolean, now = Date.now()): Promise<void> {
  const batch = writeBatch(db);
  if (done) {
    const finished: Item = { ...item, status: "DONE", doneAt: now };
    batch.set(itemDoc(uid, item.id), clean(finished));
    const probe = nextInstance(finished, "probe", now);
    if (probe?.whenAt !== undefined) {
      const next = { ...probe, id: `${item.id}_n${probe.whenAt}` };
      batch.set(itemDoc(uid, next.id), clean(next));
    }
  } else {
    const reopened: Item = { ...item, status: "OPEN" };
    delete reopened.doneAt;
    batch.set(itemDoc(uid, item.id), clean(reopened));
  }
  return batch.commit();
}
