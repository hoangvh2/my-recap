import {
  collection, deleteDoc, doc, limit, onSnapshot, orderBy, query, setDoc, writeBatch, type FirestoreError, type WriteBatch,
} from "firebase/firestore";
import { db } from "./firebase";
import { applyProposal, renew as renewLicense, type RenewalInput } from "./lib/flow";
import { buildGraph, customerCascade, type Graph } from "./lib/graph";
import { createStore } from "./lib/store";
import { normalizePrefs, type Capture, type Customer, type Item, type ItemType, type License, type Prefs, type Proposal } from "./lib/model";
import { nextInstance, rollForward } from "./lib/schedule";

export type SyncState = "connecting" | "ready" | "denied" | "error";

export const items = createStore<Item[]>([]);
export const captures = createStore<Capture[]>([]);
export const customers = createStore<Customer[]>([]);
export const licenses = createStore<License[]>([]);
export const proposals = createStore<Proposal[]>([]);
export const prefs = createStore<Prefs>(normalizePrefs(null));
export const syncState = createStore<SyncState>("connecting");

const LIMITS = { items: 1_000, captures: 100, customers: 2_000, licenses: 3_000, proposals: 500 } as const;

/** Firestore rejects `undefined`; drop absent optional fields. */
export function clean<T extends object>(rec: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(rec)) if (v !== undefined && v !== null) out[k] = v;
  return out as T;
}

export const newId = () => crypto.randomUUID().replaceAll("-", "");

type Coll = "items" | "customers" | "licenses" | "proposals";
const ref = (uid: string, c: Coll, id: string) => doc(db, "users", uid, c, id);
const prefsRef = (uid: string) => doc(db, "users", uid, "settings", "prefs");

/** Records grouped by collection: the unit that is saved, deleted and restored together. */
export interface Records {
  items?: Item[];
  customers?: Customer[];
  licenses?: License[];
  proposals?: Proposal[];
}

const COLLS: Coll[] = ["customers", "licenses", "items", "proposals"];
// A batch holds at most 500 writes; stay well under so a big import is several batches.
const BATCH = 400;

async function commit(uid: string, put: Records, del: Records = {}): Promise<void> {
  const ops: ((b: WriteBatch) => void)[] = [];
  for (const c of COLLS) {
    for (const r of (put[c] ?? []) as { id: string }[]) ops.push((b) => b.set(ref(uid, c, r.id), clean(r)));
    for (const r of (del[c] ?? []) as { id: string }[]) ops.push((b) => b.delete(ref(uid, c, r.id)));
  }
  for (let i = 0; i < ops.length; i += BATCH) {
    const batch = writeBatch(db);
    for (const op of ops.slice(i, i + BATCH)) op(batch);
    await batch.commit();
  }
}

/** Live-subscribes to the signed-in person's data. Returns the unsubscribe function. */
export function startSync(uid: string): () => void {
  syncState.set("connecting");
  const onError = (e: FirestoreError) => syncState.set(e.code === "permission-denied" ? "denied" : "error");
  const quiet = () => undefined;
  const list = <T,>(name: string, order: string, max: number, store: { set(v: T[]): void }, first?: () => void, err: (e: FirestoreError) => void = quiet) =>
    onSnapshot(
      query(collection(db, "users", uid, name), orderBy(order, "desc"), limit(max)),
      (snap) => {
        store.set(snap.docs.map((d) => d.data() as T));
        first?.();
      },
      err,
    );
  const offs = [
    list<Item>("items", "createdAt", LIMITS.items, items, () => { syncState.set("ready"); void rollRepeatingEvents(uid); }, onError),
    list<Capture>("captures", "createdAt", LIMITS.captures, captures),
    list<Customer>("customers", "createdAt", LIMITS.customers, customers),
    list<License>("licenses", "createdAt", LIMITS.licenses, licenses),
    list<Proposal>("proposals", "createdAt", LIMITS.proposals, proposals),
    onSnapshot(prefsRef(uid), (snap) => prefs.set(normalizePrefs(snap.data() as Partial<Prefs> | undefined)), quiet),
  ];
  return () => {
    offs.forEach((off) => off());
    items.set([]);
    captures.set([]);
    customers.set([]);
    licenses.set([]);
    proposals.set([]);
    prefs.set(normalizePrefs(null));
  };
}

export const currentGraph = (): Graph => buildGraph(customers.get(), licenses.get(), items.get());

// ---------------------------------------------------------------- items

/** Repeating appointments whose occurrence has passed move to the next one (idempotent across devices). */
export async function rollRepeatingEvents(uid: string, now = Date.now()): Promise<void> {
  const moved = items.get().map((i) => rollForward(i, now)).filter((i): i is Item => i !== null);
  if (moved.length === 0) return;
  await commit(uid, { items: moved }).catch(() => undefined);
}

export function blankItem(type: ItemType, link: { customerId?: string; licenseId?: string } = {}, now = Date.now()): Item {
  return {
    id: newId(), type, status: "OPEN", title: "", details: "", allDay: type === "EXPENSE", createdAt: now,
    ...(type === "EXPENSE" ? { category: "Khác" } : {}), ...link,
  };
}

export function saveItem(uid: string, item: Item): Promise<void> {
  return setDoc(ref(uid, "items", item.id), clean(item));
}

export function deleteItems(uid: string, ids: string[]): Promise<void> {
  if (ids.length === 1) return deleteDoc(ref(uid, "items", ids[0]));
  return commit(uid, {}, { items: ids.map((id) => ({ id }) as Item) });
}

/**
 * Ticks a task off, or back on. Finishing a repeating task also creates its next occurrence in the
 * same batch, under an id derived from the date, so a double tap or a second device cannot duplicate it.
 */
export function setDone(uid: string, item: Item, done: boolean, now = Date.now()): Promise<void> {
  if (done) {
    const finished: Item = { ...item, status: "DONE", doneAt: now };
    const probe = nextInstance(finished, "probe", now);
    const next = probe?.whenAt !== undefined ? { ...probe, id: `${item.id}_n${probe.whenAt}` } : null;
    return commit(uid, { items: next ? [finished, next] : [finished] });
  }
  const reopened: Item = { ...item, status: "OPEN" };
  delete reopened.doneAt;
  return commit(uid, { items: [reopened] });
}

// ---------------------------------------------------------------- customers and licences

export function blankCustomer(name = "", now = Date.now()): Customer {
  return { id: newId(), status: "OPEN", name, createdAt: now, updatedAt: now };
}

export function blankLicense(customerId: string, now = Date.now()): License {
  return { id: newId(), customerId, status: "OPEN", product: "", kind: "LICENSE", endDate: "", stage: "ACTIVE", stageAt: now, createdAt: now, updatedAt: now };
}

export function saveCustomer(uid: string, c: Customer): Promise<void> {
  return setDoc(ref(uid, "customers", c.id), clean({ ...c, updatedAt: Date.now() }));
}

export function saveLicense(uid: string, l: License): Promise<void> {
  return setDoc(ref(uid, "licenses", l.id), clean({ ...l, updatedAt: Date.now() }));
}

/** Opens the next period of a licence and closes this one as renewed, in one write. */
export function renew(uid: string, old: License, input: RenewalInput, now = Date.now()): Promise<{ old: License; next: License }> {
  const r = renewLicense(old, newId(), input, now);
  return commit(uid, { licenses: [r.old, r.next] }).then(() => r);
}

/** Returns what was removed, so "Hoàn tác" can put it back. */
export async function deleteCustomer(uid: string, g: Graph, id: string): Promise<Records> {
  const customer = g.customerById.get(id);
  const { licenses: ls, items: is } = customerCascade(g, id);
  const gone: Records = { customers: customer ? [customer] : [], licenses: ls, items: is };
  await commit(uid, {}, gone);
  return gone;
}

/** Removes a licence; work that pointed at it stays with the customer. Returns the way back. */
export async function deleteLicense(uid: string, g: Graph, l: License): Promise<() => Promise<void>> {
  const linked = g.itemsOfLicense(l.id);
  const unlinked = linked.map((i) => {
    const copy = { ...i };
    delete copy.licenseId;
    return copy;
  });
  await commit(uid, { items: unlinked }, { licenses: [l] });
  return () => commit(uid, { licenses: [l], items: linked });
}

export const restore = (uid: string, rec: Records): Promise<void> => commit(uid, rec);

// ---------------------------------------------------------------- what the AI drafted

/** Everything one capture produced that is still waiting for the owner. */
export function pendingOf(sourceId: string, g: Graph, all: Proposal[]) {
  return {
    items: g.items.filter((i) => i.status === "DRAFT" && i.sourceId === sourceId),
    customers: g.customers.filter((c) => c.status === "DRAFT" && c.sourceId === sourceId),
    licenses: g.licenses.filter((l) => l.status === "DRAFT" && l.sourceId === sourceId),
    proposals: all.filter((p) => p.captureId === sourceId),
  };
}

/** Saves everything the owner reviewed: drafts become real and proposed changes are applied. */
export function confirmCapture(uid: string, g: Graph, sourceId: string, all: Proposal[], now = Date.now()): Promise<void> {
  const p = pendingOf(sourceId, g, all);
  const changed: License[] = [];
  for (const prop of p.proposals) {
    const target = g.licenseById.get(prop.licenseId);
    if (target) changed.push(applyProposal(target, prop, now));
  }
  return commit(
    uid,
    {
      customers: p.customers.map((c) => ({ ...c, status: "OPEN" as const, updatedAt: now })),
      licenses: [...p.licenses.map((l) => ({ ...l, status: "OPEN" as const, updatedAt: now })), ...changed],
      items: p.items.map((i) => ({ ...i, status: "OPEN" as const })),
    },
    { proposals: p.proposals },
  );
}

/** Throws a whole capture away. Returns the way back. */
export async function discardCapture(uid: string, g: Graph, sourceId: string, all: Proposal[]): Promise<() => Promise<void>> {
  const p = pendingOf(sourceId, g, all);
  await commit(uid, {}, p);
  return () => commit(uid, p);
}

/** Drops one proposed customer together with the licences drafted for it; drafted work just loses the link. */
export async function dropDraftCustomer(uid: string, g: Graph, c: Customer): Promise<void> {
  const ls = g.licensesOf(c.id).filter((l) => l.status === "DRAFT");
  const lids = new Set(ls.map((l) => l.id));
  const relinked = g.items
    .filter((i) => i.status === "DRAFT" && (i.customerId === c.id || (i.licenseId && lids.has(i.licenseId))))
    .map((i) => {
      const copy = { ...i };
      if (copy.customerId === c.id) delete copy.customerId;
      if (copy.licenseId && lids.has(copy.licenseId)) delete copy.licenseId;
      return copy;
    });
  await commit(uid, { items: relinked }, { customers: [c], licenses: ls });
}

export async function dropDraftLicense(uid: string, g: Graph, l: License): Promise<void> {
  const relinked = g.itemsOfLicense(l.id).filter((i) => i.status === "DRAFT").map((i) => {
    const copy = { ...i };
    delete copy.licenseId;
    return copy;
  });
  await commit(uid, { items: relinked }, { licenses: [l] });
}

export function dropDraftItem(uid: string, item: Item): Promise<void> {
  return deleteDoc(ref(uid, "items", item.id));
}

export function dismissProposal(uid: string, p: Proposal): Promise<void> {
  return deleteDoc(ref(uid, "proposals", p.id));
}

/** Applies one proposal right away (from the licence screen). */
export function acceptProposal(uid: string, g: Graph, p: Proposal, now = Date.now()): Promise<void> {
  const target = g.licenseById.get(p.licenseId);
  return commit(uid, target ? { licenses: [applyProposal(target, p, now)] } : {}, { proposals: [p] });
}

// ---------------------------------------------------------------- settings and import

export function savePrefs(uid: string, p: Prefs): Promise<void> {
  return setDoc(prefsRef(uid), { ...p, updatedAt: Date.now() });
}

export const importRecords = (uid: string, rec: Records): Promise<void> => commit(uid, rec);
