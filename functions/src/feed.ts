import { createHash, randomBytes } from "node:crypto";
import type { Firestore } from "firebase-admin/firestore";
import { isoDateIn } from "../../shared/dates";
import { eventForItem, eventsForLicense, renderCalendar, type CalEvent } from "../../shared/ics";
import { normalizePrefs, type Customer, type Item, type License, type Prefs } from "../../shared/model";
import { isValidZone } from "./items";

/**
 * The calendar subscription: a secret link that phone calendars poll. The link carries a random
 * 256-bit token; only its SHA-256 is used for lookup, so the address is the only thing that grants
 * access and it can be replaced or switched off at any time. Phone numbers and e-mail addresses are
 * never put in the feed.
 */

export type FeedAction = "get" | "rotate" | "revoke";
export interface FeedRequest {
  action: FeedAction;
  zone: string;
}

export class FeedRequestError extends Error {}

export function parseFeedRequest(data: unknown): FeedRequest {
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new FeedRequestError("Yêu cầu không hợp lệ");
  const d = data as Record<string, unknown>;
  if (Object.keys(d).some((k) => k !== "action" && k !== "zone")) throw new FeedRequestError("Yêu cầu không hợp lệ");
  if (d.action !== "get" && d.action !== "rotate" && d.action !== "revoke") throw new FeedRequestError("Yêu cầu không hợp lệ");
  if (!isValidZone(d.zone)) throw new FeedRequestError("Múi giờ không hợp lệ");
  return { action: d.action, zone: d.zone };
}

export const newToken = (): string => randomBytes(32).toString("base64url");
export const hashToken = (token: string): string => createHash("sha256").update(token).digest("hex");
const TOKEN_PATH = /^\/calendar\/([A-Za-z0-9_-]{43})\.ics$/;

/** The token in a request path like /calendar/<token>.ics, or null. */
export function tokenFromPath(path: string): string | null {
  return TOKEN_PATH.exec(path)?.[1] ?? null;
}

export interface FeedRecord {
  token: string;
  zone: string;
}

export interface FeedData {
  items: Item[];
  customers: Pick<Customer, "id" | "name">[];
  licenses: License[];
  prefs: Prefs;
}

export interface FeedStore {
  get(uid: string): Promise<FeedRecord | null>;
  /** Replaces the owner's token (and the lookup entry of the old one). */
  put(uid: string, record: FeedRecord, previous: FeedRecord | null): Promise<void>;
  remove(uid: string, previous: FeedRecord): Promise<void>;
  uidForHash(hash: string): Promise<{ uid: string; zone: string } | null>;
  load(uid: string): Promise<FeedData>;
}

/** What the signed-in app asks for: show the current link, make a new one (the old one stops), or switch it off. */
export async function manageFeed(store: FeedStore, uid: string, req: FeedRequest, token: () => string = newToken): Promise<{ token: string | null }> {
  const current = await store.get(uid);
  if (req.action === "revoke") {
    if (current) await store.remove(uid, current);
    return { token: null };
  }
  if (req.action === "rotate" || (current && current.zone !== req.zone)) {
    const record = { token: req.action === "rotate" || !current ? token() : current.token, zone: req.zone };
    await store.put(uid, record, current);
    return { token: record.token };
  }
  return { token: current?.token ?? null };
}

/** The calendar text for one owner: dated items plus every renewal reminder still ahead. */
export async function renderFeed(store: FeedStore, uid: string, zone: string, nowMs: number): Promise<string> {
  const data = await store.load(uid);
  const names = new Map(data.customers.map((c) => [c.id, c.name]));
  const today = isoDateIn(nowMs, zone);
  const events: CalEvent[] = [];
  for (const item of data.items) {
    if (item.status !== "OPEN" || item.type === "EXPENSE") continue;
    const e = eventForItem(item, item.customerId ? names.get(item.customerId) : undefined, zone);
    if (e) events.push(e);
  }
  for (const l of data.licenses) {
    if (l.status !== "OPEN") continue;
    events.push(...eventsForLicense(l, names.get(l.customerId) ?? "Khách", { today, prefs: data.prefs, zone }));
  }
  return renderCalendar(events, { stamp: nowMs, name: "Thư ký · Việc & Gia hạn", refreshHours: 1 });
}

// ---------------------------------------------------------------- Firestore

const FEED_LIMIT = 3000;

export class FirestoreFeedStore implements FeedStore {
  constructor(private readonly db: Firestore) {}

  private meta = (uid: string) => this.db.doc(`users/${uid}/meta/feed`);
  private lookup = (token: string) => this.db.doc(`feeds/${hashToken(token)}`);

  async get(uid: string): Promise<FeedRecord | null> {
    const d = (await this.meta(uid).get()).data() as Partial<FeedRecord> | undefined;
    return d && typeof d.token === "string" && typeof d.zone === "string" ? { token: d.token, zone: d.zone } : null;
  }

  async put(uid: string, record: FeedRecord, previous: FeedRecord | null): Promise<void> {
    const batch = this.db.batch();
    if (previous && previous.token !== record.token) batch.delete(this.lookup(previous.token));
    batch.set(this.lookup(record.token), { uid, zone: record.zone });
    batch.set(this.meta(uid), record);
    await batch.commit();
  }

  async remove(uid: string, previous: FeedRecord): Promise<void> {
    const batch = this.db.batch();
    batch.delete(this.lookup(previous.token));
    batch.delete(this.meta(uid));
    await batch.commit();
  }

  async uidForHash(hash: string): Promise<{ uid: string; zone: string } | null> {
    const d = (await this.db.doc(`feeds/${hash}`).get()).data() as { uid?: unknown; zone?: unknown } | undefined;
    return d && typeof d.uid === "string" && typeof d.zone === "string" ? { uid: d.uid, zone: d.zone } : null;
  }

  async load(uid: string): Promise<FeedData> {
    const col = (name: string) => this.db.collection(`users/${uid}/${name}`);
    const [items, customers, licenses, prefs] = await Promise.all([
      col("items").where("status", "==", "OPEN").limit(FEED_LIMIT).get(),
      col("customers").select("name").limit(FEED_LIMIT).get(),
      col("licenses").where("status", "==", "OPEN").limit(FEED_LIMIT).get(),
      this.db.doc(`users/${uid}/settings/prefs`).get(),
    ]);
    return {
      items: items.docs.map((d) => ({ ...(d.data() as Omit<Item, "id">), id: d.id })),
      customers: customers.docs.map((d) => ({ id: d.id, name: String(d.get("name") ?? "") })),
      licenses: licenses.docs.map((d) => ({ ...(d.data() as Omit<License, "id">), id: d.id })),
      prefs: normalizePrefs(prefs.data() as Partial<Prefs> | undefined),
    };
  }
}

// ---------------------------------------------------------------- the public endpoint

export interface FeedResponse {
  status: 200 | 404 | 405;
  body: string;
}

/** Short-lived cache so a phone polling every hour (or a crawler) does not turn into many reads. */
export class FeedHandler {
  private cache = new Map<string, { at: number; body: string }>();
  private misses = new Map<string, number>();
  constructor(
    private readonly store: FeedStore,
    private readonly now: () => number = Date.now,
    private readonly ttlMs = 10 * 60_000,
  ) {}

  async handle(method: string, path: string): Promise<FeedResponse> {
    if (method !== "GET" && method !== "HEAD") return { status: 405, body: "" };
    const token = tokenFromPath(path);
    if (!token) return { status: 404, body: "" };
    const hash = hashToken(token);
    const t = this.now();
    const hit = this.cache.get(hash);
    if (hit && t - hit.at < this.ttlMs) return { status: 200, body: hit.body };
    const missAt = this.misses.get(hash);
    if (missAt !== undefined && t - missAt < 60_000) return { status: 404, body: "" };

    const owner = await this.store.uidForHash(hash);
    if (!owner) {
      if (this.misses.size > 1000) this.misses.clear();
      this.misses.set(hash, t);
      this.cache.delete(hash);
      return { status: 404, body: "" };
    }
    const body = await renderFeed(this.store, owner.uid, owner.zone, t);
    if (this.cache.size > 50) this.cache.clear();
    this.cache.set(hash, { at: t, body });
    return { status: 200, body };
  }
}
