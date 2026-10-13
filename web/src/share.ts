import { collection, deleteDoc, doc, limit, onSnapshot, query, setDoc, updateDoc, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { toApiError } from "./api";
import { db, functions } from "./firebase";
import { createStore } from "./lib/store";
import { SHARE_LIMITS, type Contact, type ShareDoc } from "../../shared/share";

/**
 * Sharing, seen from this app: my contacts, what others share with me, who each of my items is shared with,
 * and the "X linked / unlinked" notices. All read live from Firestore; every change goes through the
 * `share` function (the rules let a client rename a contact and nothing else).
 */

export interface Notice {
  id: string;
  kind: "linked" | "unlinked";
  fromUid: string;
  fromName: string;
  at: number;
}

export const contacts = createStore<Contact[]>([]);
/** Read-only views others shared with me. */
export const received = createStore<ShareDoc[]>([]);
/** itemId → uids it is shared with (my own items only). */
export const audiences = createStore<Map<string, string[]>>(new Map());
export const notices = createStore<Notice[]>([]);

export function startShareSync(uid: string): () => void {
  const quiet = () => undefined;
  const offs = [
    onSnapshot(query(collection(db, "users", uid, "contacts"), limit(200)), (s) => {
      contacts.set(s.docs.map((d) => d.data() as Contact).sort((a, b) => a.alias.localeCompare(b.alias, "vi")));
    }, quiet),
    onSnapshot(query(collection(db, "shares"), where("audience", "array-contains", uid), limit(SHARE_LIMITS.received)), (s) => {
      received.set(s.docs.map((d) => d.data() as ShareDoc));
    }, quiet),
    onSnapshot(query(collection(db, "shares"), where("ownerUid", "==", uid), limit(500)), (s) => {
      audiences.set(new Map(s.docs.map((d) => [String(d.get("itemId")), (d.get("audience") as string[]) ?? []])));
    }, quiet),
    onSnapshot(query(collection(db, "users", uid, "notices"), limit(50)), (s) => {
      notices.set(s.docs.map((d) => d.data() as Notice).sort((a, b) => b.at - a.at));
    }, quiet),
  ];
  return () => {
    offs.forEach((off) => off());
    contacts.set([]);
    received.set([]);
    audiences.set(new Map());
    notices.set([]);
  };
}

export { asItem, ownerLabel } from "./lib/sharing";

// ---------------------------------------------------------------- the function

type Req =
  | { action: "linkCreate" }
  | { action: "linkPreview"; code: string }
  | { action: "linkJoin"; code: string; alias: string }
  | { action: "linkRemove"; uid: string }
  | { action: "shareSet"; itemId: string; audience: string[] };

const call = httpsCallable<Req, unknown>(functions, "share", { timeout: 60_000 });

async function run<T>(req: Req): Promise<T> {
  try {
    return (await call(req)).data as T;
  } catch (e) {
    // The function's own refusals carry a Vietnamese message meant for the person.
    const code = (e as { code?: string } | null)?.code ?? "";
    const msg = (e as { message?: string } | null)?.message ?? "";
    if (/^functions\/(not-found|failed-precondition|already-exists|resource-exhausted|permission-denied|invalid-argument)$/.test(code) && msg && !/^[a-z-]+$/.test(msg)) {
      throw new Error(msg);
    }
    throw new Error(toApiError(e).message.replace("ghi nhanh", "yêu cầu"));
  }
}

export const createCode = () => run<{ code: string; expiresAt: number }>({ action: "linkCreate" });
export const previewCode = (code: string) => run<{ name: string; maskedEmail: string; already: boolean }>({ action: "linkPreview", code });
export const joinCode = (code: string, alias: string) => run<{ contact: Contact }>({ action: "linkJoin", code, alias });
export const unlink = (uid: string) => run<{ removed: true }>({ action: "linkRemove", uid });
export const setAudience = (itemId: string, audience: string[]) => run<{ audience: string[] }>({ action: "shareSet", itemId, audience });

export function renameContact(me: string, c: Contact, alias: string): Promise<void> {
  return updateDoc(doc(db, "users", me, "contacts", c.uid), { alias, notify: c.notify });
}

export function setContactNotify(me: string, c: Contact, notify: boolean): Promise<void> {
  return updateDoc(doc(db, "users", me, "contacts", c.uid), { alias: c.alias, notify });
}

export function dismissNotice(me: string, id: string): Promise<void> {
  return deleteDoc(doc(db, "users", me, "notices", id));
}

/** Remembers where to push for this browser (one document per FCM token). */
export function saveDevice(me: string, id: string, token: string): Promise<void> {
  return setDoc(doc(db, "users", me, "devices", id), { token, platform: "web", updatedAt: Date.now() });
}

export function forgetDevice(me: string, id: string): Promise<void> {
  return deleteDoc(doc(db, "users", me, "devices", id));
}
