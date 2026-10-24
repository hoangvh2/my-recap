import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { normalizePrefs, type Item, type License, type Prefs } from "../../../shared/model";
import { digest, digestDue, group, readWindow, reminders, sampleMessage, type DigestKind, type Message } from "./plan";
import type { Sender } from "./pushover";
import { readSettings, SettingsError, type NotifySettings, type SettingsRequest } from "./settings";

/** Hard ceiling per person and day, whatever goes wrong upstream: a reminder system must never become spam. */
export const DAILY_CAP = 60;
const DAY_MS = 86_400_000;

export interface NotifyStore {
  /** Owners who switched notifications on. */
  notifiers(): Promise<string[]>;
  settings(uid: string): Promise<NotifySettings | null>;
  /** Items whose time is within [from, to], plus every repeating item (its next time may fall in the window). */
  items(uid: string, from: number, to: number): Promise<Item[]>;
  names(uid: string, customerIds: string[]): Promise<Map<string, string>>;
  licenses(uid: string): Promise<License[]>;
  prefs(uid: string): Promise<Prefs>;
  drafts(uid: string): Promise<number>;
  /** Which of these keys were already sent (or are being sent). */
  sent(uid: string, keys: string[]): Promise<Set<string>>;
  /** Marks keys as being sent, atomically: false when one is taken already or the day's cap is reached. */
  claim(uid: string, keys: string[], nowMs: number, zoneDay: string): Promise<"ok" | "taken" | "capped">;
  /** Records the outcome; an emergency also keeps its receipt (so it can be cancelled) until it expires. */
  finish(uid: string, keys: string[], state: "sent" | "failed", receipt?: { receipt: string; itemId: string; until: number }): Promise<void>;
  /** Emergencies that may still be repeating on the phone. */
  activeReceipts(uid: string, nowMs: number): Promise<{ key: string; receipt: string; itemId: string }[]>;
  clearReceipt(uid: string, key: string): Promise<void>;
  itemsById(uid: string, ids: string[]): Promise<Map<string, Item>>;
  /** Forget a claim after a temporary failure, so the next run tries again. */
  release(uid: string, keys: string[]): Promise<void>;
  /** Save, read or switch off the owner's settings. */
  saveSettings(uid: string, s: NotifySettings): Promise<void>;
  disable(uid: string): Promise<void>;
}

export interface TickResult {
  users: number;
  sent: number;
  failed: number;
  capped: number;
  /** Emergencies stopped because their item was done, no longer important, or deleted. */
  cancelled: number;
}

/** One scheduler run (every minute): reminders that fell due, and the summaries whose time has come. */
export async function tick(store: NotifyStore, sender: Sender, nowMs: number, appUrl?: string, log: (msg: string, data: object) => void = () => undefined): Promise<TickResult> {
  const result: TickResult = { users: 0, sent: 0, failed: 0, capped: 0, cancelled: 0 };
  for (const uid of await store.notifiers()) {
    try {
      const s = await store.settings(uid);
      if (!s) continue;
      result.users++;
      result.cancelled += await stopFinished(store, sender, uid, nowMs);
      const messages = await plan(store, uid, s, nowMs, appUrl);
      for (const m of messages) {
        const keys = m.parts ?? [m.key];
        const day = new Intl.DateTimeFormat("en-CA", { timeZone: s.zone }).format(nowMs);
        const claim = await store.claim(uid, keys, nowMs, day);
        if (claim === "capped") {
          result.capped++;
          continue;
        }
        if (claim === "taken") continue;
        const r = await sender.send(s.pushoverUser, s.device, m);
        if (r.ok) {
          const receipt = m.priority === 2 && r.receipt && m.itemId ? { receipt: r.receipt, itemId: m.itemId, until: nowMs + (m.expireSec ?? 3600) * 1000 } : undefined;
          await store.finish(uid, keys, "sent", receipt);
          result.sent++;
        } else if (r.retry) {
          await store.release(uid, keys);
          result.failed++;
        } else {
          await store.finish(uid, keys, "failed");
          result.failed++;
          log("pushover refused", { status: r.status ?? 0 });
        }
      }
    } catch (e) {
      result.failed++;
      log("notify user failed", { error: e instanceof Error ? e.name : "unknown" });
    }
  }
  return result;
}

/** An emergency keeps ringing until acknowledged: stop it as soon as its item is done, unmarked or deleted. */
async function stopFinished(store: NotifyStore, sender: Sender, uid: string, nowMs: number): Promise<number> {
  const active = await store.activeReceipts(uid, nowMs);
  if (active.length === 0) return 0;
  const items = await store.itemsById(uid, active.map((a) => a.itemId));
  let stopped = 0;
  for (const a of active) {
    const item = items.get(a.itemId);
    if (item && item.status === "OPEN" && item.important) continue;
    const r = await sender.cancel(a.receipt);
    if (r.ok || !r.retry) {
      await store.clearReceipt(uid, a.key);
      if (r.ok) stopped++;
    }
  }
  return stopped;
}

async function plan(store: NotifyStore, uid: string, s: NotifySettings, nowMs: number, appUrl?: string): Promise<Message[]> {
  const w = readWindow(nowMs, s);
  const items = await store.items(uid, w.from, w.to);
  const names = await store.names(uid, idsOf(items));
  const due = reminders({ items, names, settings: s, nowMs, appUrl });
  const out: Message[] = [];

  for (const kind of ["morning", "evening"] as DigestKind[]) {
    const key = digestDue(kind, s, nowMs);
    if (!key || (await store.sent(uid, [key])).has(key)) continue;
    // Summaries need a wider view: the last 30 days (overdue) to tomorrow, and the licences.
    const wide = await store.items(uid, nowMs - 30 * DAY_MS, nowMs + 2 * DAY_MS);
    const [licenses, prefs, drafts] = await Promise.all([store.licenses(uid), store.prefs(uid), store.drafts(uid)]);
    const allNames = await store.names(uid, [...idsOf(wide), ...licenses.map((l) => l.customerId)]);
    const m = digest(kind, key, { items: wide, names: allNames, settings: s, nowMs, appUrl, licenses, prefs, drafts });
    if (m) out.push(m);
  }

  const already = await store.sent(uid, due.map((m) => m.key));
  out.push(...group(due.filter((m) => !already.has(m.key)), nowMs, appUrl));
  return out;
}

const idsOf = (items: readonly Item[]) => [...new Set(items.map((i) => i.customerId).filter((x): x is string => !!x))];

// ---------------------------------------------------------------- settings (the callable)

export class NotifyError extends Error {
  constructor(
    readonly code: "invalid-argument" | "failed-precondition" | "unavailable",
    message: string,
  ) {
    super(message);
  }
}

/** Save (after Pushover confirms the key), send a test, or switch off. */
export async function manageNotify(store: NotifyStore, sender: Sender, uid: string, req: SettingsRequest, nowMs: number, appUrl?: string): Promise<{ enabled: boolean }> {
  if (req.action === "off") {
    await store.disable(uid);
    return { enabled: false };
  }
  if (req.action === "test") {
    const s = await store.settings(uid);
    if (!s) throw new NotifyError("failed-precondition", "Chưa bật thông báo: nhập User Key rồi bấm Lưu trước");
    const r = await sender.send(s.pushoverUser, s.device, sampleMessage(s, nowMs, appUrl));
    if (!r.ok) throw r.retry ? new NotifyError("unavailable", "Pushover chưa phản hồi, thử lại sau ít phút") : new NotifyError("failed-precondition", "Pushover từ chối: kiểm tra User Key và thiết bị");
    return { enabled: true };
  }
  const v = await sender.validate(req.settings.pushoverUser, req.settings.device);
  if (!v.ok) {
    throw v.retry
      ? new NotifyError("unavailable", "Pushover chưa phản hồi, thử lại sau ít phút")
      : new NotifyError("invalid-argument", req.settings.device ? "User Key hoặc tên thiết bị không đúng" : "User Key không đúng, chép lại từ app Pushover");
  }
  await store.saveSettings(uid, { ...req.settings, enabled: true, updatedAt: nowMs });
  return { enabled: true };
}

export { SettingsError };

// ---------------------------------------------------------------- Firestore

const RECURRING = ["DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "LUNAR_MONTHLY", "LUNAR_YEARLY"];
const LOG_TTL_MS = 14 * DAY_MS;

export class FirestoreNotifyStore implements NotifyStore {
  constructor(private readonly db: Firestore) {}

  private settingsRef = (uid: string) => this.db.doc(`users/${uid}/settings/notify`);
  private log = (uid: string, key: string) => this.db.doc(`users/${uid}/notifylog/${key}`);
  private counter = (uid: string) => this.db.doc(`users/${uid}/meta/notify`);
  private col = (uid: string, name: string) => this.db.collection(`users/${uid}/${name}`);

  async notifiers(): Promise<string[]> {
    const snap = await this.db.collection("notifiers").where("enabled", "==", true).limit(50).get();
    return snap.docs.map((d) => d.id);
  }

  async settings(uid: string): Promise<NotifySettings | null> {
    return readSettings((await this.settingsRef(uid).get()).data());
  }

  async items(uid: string, from: number, to: number): Promise<Item[]> {
    const [ranged, repeating] = await Promise.all([
      this.col(uid, "items").where("whenAt", ">=", from).where("whenAt", "<=", to).limit(500).get(),
      this.col(uid, "items").where("recurrence", "in", RECURRING).limit(200).get(),
    ]);
    const byId = new Map<string, Item>();
    for (const d of [...ranged.docs, ...repeating.docs]) byId.set(d.id, { ...(d.data() as Omit<Item, "id">), id: d.id });
    return [...byId.values()];
  }

  async names(uid: string, ids: string[]): Promise<Map<string, string>> {
    const unique = [...new Set(ids)].slice(0, 300);
    if (unique.length === 0) return new Map();
    const docs = await this.db.getAll(...unique.map((id) => this.col(uid, "customers").doc(id)));
    return new Map(docs.filter((d) => d.exists).map((d) => [d.id, String(d.get("name") ?? "")]));
  }

  async licenses(uid: string): Promise<License[]> {
    const snap = await this.col(uid, "licenses").where("status", "==", "OPEN").limit(3000).get();
    return snap.docs.map((d) => ({ ...(d.data() as Omit<License, "id">), id: d.id }));
  }

  async prefs(uid: string): Promise<Prefs> {
    return normalizePrefs((await this.db.doc(`users/${uid}/settings/prefs`).get()).data() as Partial<Prefs> | undefined);
  }

  async drafts(uid: string): Promise<number> {
    const snap = await this.col(uid, "items").where("status", "==", "DRAFT").count().get();
    return snap.data().count;
  }

  async sent(uid: string, keys: string[]): Promise<Set<string>> {
    if (keys.length === 0) return new Set();
    const docs = await this.db.getAll(...keys.map((k) => this.log(uid, k)));
    return new Set(docs.filter((d) => d.exists).map((d) => d.id));
  }

  async claim(uid: string, keys: string[], nowMs: number, zoneDay: string): Promise<"ok" | "taken" | "capped"> {
    return this.db.runTransaction(async (tx) => {
      const [counter, ...logs] = await tx.getAll(this.counter(uid), ...keys.map((k) => this.log(uid, k)));
      if (logs.some((d) => d.exists)) return "taken";
      const c = counter.data() as { day?: string; count?: number } | undefined;
      const count = c?.day === zoneDay ? (c.count ?? 0) : 0;
      if (count >= DAILY_CAP) return "capped";
      tx.set(this.counter(uid), { day: zoneDay, count: count + 1 });
      const expireAt = Timestamp.fromMillis(nowMs + LOG_TTL_MS);
      for (const k of keys) tx.create(this.log(uid, k), { state: "sending", at: nowMs, expireAt });
      return "ok";
    });
  }

  async finish(uid: string, keys: string[], state: "sent" | "failed", receipt?: { receipt: string; itemId: string; until: number }): Promise<void> {
    const batch = this.db.batch();
    for (const k of keys) batch.update(this.log(uid, k), { state, ...(receipt ? { receipt: receipt.receipt, itemId: receipt.itemId, receiptUntil: receipt.until } : {}) });
    await batch.commit();
  }

  async activeReceipts(uid: string, nowMs: number): Promise<{ key: string; receipt: string; itemId: string }[]> {
    const snap = await this.db.collection(`users/${uid}/notifylog`).where("receiptUntil", ">", nowMs).limit(50).get();
    return snap.docs
      .filter((d) => typeof d.get("receipt") === "string" && typeof d.get("itemId") === "string")
      .map((d) => ({ key: d.id, receipt: d.get("receipt") as string, itemId: d.get("itemId") as string }));
  }

  async clearReceipt(uid: string, key: string): Promise<void> {
    await this.log(uid, key).update({ receiptUntil: FieldValue.delete(), receipt: FieldValue.delete() });
  }

  async itemsById(uid: string, ids: string[]): Promise<Map<string, Item>> {
    const unique = [...new Set(ids)].slice(0, 100);
    if (unique.length === 0) return new Map();
    const docs = await this.db.getAll(...unique.map((id) => this.col(uid, "items").doc(id)));
    return new Map(docs.filter((d) => d.exists).map((d) => [d.id, { ...(d.data() as Omit<Item, "id">), id: d.id }]));
  }

  async release(uid: string, keys: string[]): Promise<void> {
    const batch = this.db.batch();
    for (const k of keys) batch.delete(this.log(uid, k));
    batch.set(this.counter(uid), { count: FieldValue.increment(-1) }, { merge: true });
    await batch.commit();
  }

  async saveSettings(uid: string, s: NotifySettings): Promise<void> {
    const batch = this.db.batch();
    batch.set(this.settingsRef(uid), s);
    batch.set(this.db.doc(`notifiers/${uid}`), { enabled: true, updatedAt: s.updatedAt });
    await batch.commit();
  }

  async disable(uid: string): Promise<void> {
    const batch = this.db.batch();
    batch.set(this.settingsRef(uid), { enabled: false, updatedAt: Date.now() }, { merge: true });
    batch.delete(this.db.doc(`notifiers/${uid}`));
    await batch.commit();
  }
}
