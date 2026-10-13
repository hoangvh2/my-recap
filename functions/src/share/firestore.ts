import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { Messaging } from "firebase-admin/messaging";
import type { Item } from "../../../shared/model";
import { pairId, SHARE_LIMITS, type Contact, type ShareDoc } from "../../../shared/share";
import { escapeHtml } from "../notify/plan";
import type { Sender } from "../notify/pushover";
import { readSettings } from "../notify/settings";
import { ShareError, type CodeRecord, type Notice, type PushMessage, type SharePusher, type ShareStore } from "./service";

/**
 * Where sharing lives in Firestore (every path here is closed to clients except where the rules say so):
 *   links/{pairId}                  { members: [a, b], createdAt }
 *   users/{uid}/contacts/{other}    Contact (the owner may change alias and notify)
 *   shares/{owner_item}             ShareDoc (read by its owner and its audience)
 *   shareCodes/{sha256}             CodeRecord
 *   users/{uid}/notices/{id}        Notice (read and deleted by the owner)
 *   users/{uid}/devices/{id}        { token, platform, updatedAt } (written by the owner's apps)
 *   users/{uid}/meta/share          { failures: number[] } (wrong codes in the last hour)
 */
export class FirestoreShareStore implements ShareStore {
  constructor(private readonly db: Firestore) {}

  private codeRef = (hash: string) => this.db.doc(`shareCodes/${hash}`);
  private contactRef = (uid: string, other: string) => this.db.doc(`users/${uid}/contacts/${other}`);
  private linkRef = (a: string, b: string) => this.db.doc(`links/${pairId(a, b)}`);
  private metaRef = (uid: string) => this.db.doc(`users/${uid}/meta/share`);
  private shareRef = (id: string) => this.db.doc(`shares/${id}`);

  async putCode(hash: string, rec: CodeRecord): Promise<void> {
    const old = await this.db.collection("shareCodes").where("ownerUid", "==", rec.ownerUid).limit(20).get();
    const batch = this.db.batch();
    for (const d of old.docs) batch.delete(d.ref);
    // expireAt (a Timestamp) lets Firestore's TTL delete codes nobody used; the check itself reads expiresAt.
    batch.set(this.codeRef(hash), { ...rec, expireAt: Timestamp.fromMillis(rec.expiresAt + 3600_000) });
    await batch.commit();
  }

  async getCode(hash: string): Promise<CodeRecord | null> {
    const snap = await this.codeRef(hash).get();
    return snap.exists ? (snap.data() as CodeRecord) : null;
  }

  async failures(uid: string, sinceMs: number): Promise<number> {
    const list = ((await this.metaRef(uid).get()).get("failures") as number[] | undefined) ?? [];
    return list.filter((t) => t > sinceMs).length;
  }

  async recordFailure(uid: string, nowMs: number): Promise<void> {
    await this.db.runTransaction(async (tx) => {
      const ref = this.metaRef(uid);
      const list = (((await tx.get(ref)).get("failures") as number[] | undefined) ?? []).filter((t) => t > nowMs - 3600_000);
      tx.set(ref, { failures: [...list, nowMs].slice(-20) }, { merge: true });
    });
  }

  async join(hash: string, joinerSees: Contact, ownerSees: Contact, nowMs: number): Promise<void> {
    const joinerUid = ownerSees.uid;
    const ownerUid = joinerSees.uid;
    await this.db.runTransaction(async (tx) => {
      const [code, link, joinerCount, ownerCount] = await Promise.all([
        tx.get(this.codeRef(hash)),
        tx.get(this.linkRef(joinerUid, ownerUid)),
        tx.get(this.db.collection(`users/${joinerUid}/contacts`).count()),
        tx.get(this.db.collection(`users/${ownerUid}/contacts`).count()),
      ]);
      const rec = code.data() as CodeRecord | undefined;
      if (!rec || rec.expiresAt <= nowMs || rec.ownerUid !== ownerUid) throw new ShareError("not-found", "Mã không đúng hoặc đã hết hạn. Nhờ người kia tạo mã mới");
      if (link.exists) throw new ShareError("already-exists", "Hai bạn đã liên kết với nhau rồi");
      if (joinerCount.data().count >= SHARE_LIMITS.contacts || ownerCount.data().count >= SHARE_LIMITS.contacts) {
        throw new ShareError("resource-exhausted", `Mỗi người có tối đa ${SHARE_LIMITS.contacts} liên hệ`);
      }
      tx.set(this.linkRef(joinerUid, ownerUid), { members: [joinerUid, ownerUid].sort(), createdAt: nowMs });
      tx.set(this.contactRef(joinerUid, ownerUid), joinerSees);
      tx.set(this.contactRef(ownerUid, joinerUid), ownerSees);
      tx.delete(this.codeRef(hash));
    });
  }

  async contact(uid: string, other: string): Promise<Contact | null> {
    const snap = await this.contactRef(uid, other).get();
    return snap.exists ? (snap.data() as Contact) : null;
  }

  async contactIds(uid: string): Promise<string[]> {
    const snap = await this.db.collection(`users/${uid}/contacts`).select().limit(SHARE_LIMITS.contacts + 10).get();
    return snap.docs.map((d) => d.id);
  }

  async revoke(owner: string, reader: string): Promise<number> {
    // No composite index needed: a reader receives at most a few hundred shares, filtered here by owner.
    const snap = await this.db.collection("shares").where("audience", "array-contains", reader).limit(SHARE_LIMITS.received * 2).get();
    const mine = snap.docs.filter((d) => d.get("ownerUid") === owner);
    for (let i = 0; i < mine.length; i += 400) {
      const batch = this.db.batch();
      for (const d of mine.slice(i, i + 400)) {
        const left = ((d.get("audience") as string[]) ?? []).filter((u) => u !== reader);
        if (left.length === 0) batch.delete(d.ref);
        else batch.update(d.ref, { audience: FieldValue.arrayRemove(reader) });
      }
      await batch.commit();
    }
    return mine.length;
  }

  async unlink(a: string, b: string): Promise<void> {
    const batch = this.db.batch();
    batch.delete(this.linkRef(a, b));
    batch.delete(this.contactRef(a, b));
    batch.delete(this.contactRef(b, a));
    await batch.commit();
  }

  async item(uid: string, itemId: string): Promise<Item | null> {
    const snap = await this.db.doc(`users/${uid}/items/${itemId}`).get();
    return snap.exists ? ({ ...(snap.data() as Omit<Item, "id">), id: snap.id }) : null;
  }

  async share(id: string): Promise<ShareDoc | null> {
    const snap = await this.shareRef(id).get();
    return snap.exists ? (snap.data() as ShareDoc) : null;
  }

  async putShare(doc: ShareDoc): Promise<void> {
    const clean = Object.fromEntries(Object.entries(doc).filter(([, v]) => v !== undefined && v !== null));
    await this.shareRef(doc.id).set(clean);
  }

  async deleteShare(id: string): Promise<void> {
    await this.shareRef(id).delete();
  }

  async addNotice(uid: string, n: Notice): Promise<void> {
    await this.db.doc(`users/${uid}/notices/${n.id}`).set(n);
  }
}

/**
 * Sends one message to every channel a person has: each signed-in phone app and browser (FCM), and Pushover
 * when they switched it on in Settings. Tokens FCM reports as gone are deleted.
 */
export class MultiChannelPusher implements SharePusher {
  constructor(
    private readonly db: Firestore,
    private readonly fcm: Messaging | null,
    private readonly pushover: Sender | null,
    private readonly log: (msg: string, data: object) => void,
  ) {}

  async send(uid: string, m: PushMessage): Promise<void> {
    await Promise.all([this.viaFcm(uid, m), this.viaPushover(uid, m)]);
  }

  private async viaFcm(uid: string, m: PushMessage): Promise<void> {
    if (!this.fcm) return;
    const devices = await this.db.collection(`users/${uid}/devices`).limit(20).get();
    const tokens = devices.docs.map((d) => String(d.get("token") ?? "")).filter((t) => t.length > 20);
    if (tokens.length === 0) return;
    const data: Record<string, string> = { kind: m.kind, title: m.title, body: m.body };
    if (m.shareId) data.shareId = m.shareId;
    if (m.url) data.url = m.url;
    const res = await this.fcm.sendEachForMulticast({
      tokens,
      // Data only on Android: the app builds the notification itself (channel, tap target).
      data,
      android: { priority: "high", ttl: 24 * 3600_000 },
      webpush: {
        notification: { title: m.title, body: m.body, icon: "/icons/icon-192.png", tag: m.shareId ?? m.kind },
        fcmOptions: m.url ? { link: m.url } : undefined,
        headers: { Urgency: "high", TTL: String(24 * 3600) },
      },
    });
    const gone = res.responses
      .map((r, i) => (!r.success && /registration-token-not-registered|invalid-registration-token|invalid-argument/.test(r.error?.code ?? "") ? i : -1))
      .filter((i) => i >= 0);
    await Promise.all(gone.map((i) => devices.docs.find((d) => d.get("token") === tokens[i])?.ref.delete()));
    if (res.failureCount > gone.length) this.log("share push partly failed", { failed: res.failureCount - gone.length });
  }

  private async viaPushover(uid: string, m: PushMessage): Promise<void> {
    if (!this.pushover) return;
    const s = readSettings((await this.db.doc(`users/${uid}/settings/notify`).get()).data());
    if (!s) return;
    const r = await this.pushover.send(s.pushoverUser, s.device, {
      key: `share-${m.kind}-${Date.now()}`,
      priority: 0,
      title: m.title,
      body: escapeHtml(m.body),
      at: Date.now(),
      url: m.url,
      urlTitle: m.url ? "Mở trong app" : undefined,
      ttlSec: 7 * 24 * 3600,
    });
    if (!r.ok) this.log("share pushover failed", { retry: r.retry, status: r.status ?? 0 });
  }
}
