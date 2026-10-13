import { createHash } from "node:crypto";
import { DateTime } from "luxon";
import type { Item } from "../../../shared/model";
import {
  changedLabels, changeKind, cleanAlias, CODE_ALPHABET, CODE_LENGTH, isShareable, maskEmail, normalizeCode, parentOfRepeat,
  SHARE_LIMITS, sharedFields, shareId, type Contact, type ShareDoc, type SharedFields,
} from "../../../shared/share";

/**
 * Sharing: link codes, contacts, which item is shared with whom, and the read-only views recipients get.
 * Pure orchestration over a `ShareStore` (Firestore in production, a map in tests) and a `SharePusher`.
 */

export class ShareError extends Error {
  constructor(
    readonly code: "invalid-argument" | "not-found" | "failed-precondition" | "resource-exhausted" | "permission-denied" | "already-exists",
    message: string,
  ) {
    super(message);
  }
}

/** Who is calling, from the verified ID token. */
export interface Caller {
  uid: string;
  email: string;
  /** Google display name; may be empty. */
  name: string;
}

export interface CodeRecord {
  ownerUid: string;
  ownerName: string;
  ownerEmail: string;
  expiresAt: number;
}

export interface Notice {
  id: string;
  kind: "linked" | "unlinked";
  fromUid: string;
  fromName: string;
  at: number;
}

export interface ShareStore {
  /** Saves a new code for `ownerUid`, removing any earlier code of theirs. */
  putCode(hash: string, rec: CodeRecord): Promise<void>;
  getCode(hash: string): Promise<CodeRecord | null>;
  /** Wrong codes entered by `uid` since `sinceMs`. */
  failures(uid: string, sinceMs: number): Promise<number>;
  recordFailure(uid: string, nowMs: number): Promise<void>;
  /**
   * Atomically: the code still exists and has not expired, the two are not linked yet and neither has too many
   * contacts; then creates the link and both contacts and deletes the code. Throws ShareError otherwise.
   * `joinerSees` is stored under the joiner (it describes the code's owner), `ownerSees` the other way round.
   */
  join(hash: string, joinerSees: Contact, ownerSees: Contact, nowMs: number): Promise<void>;
  contact(uid: string, other: string): Promise<Contact | null>;
  /** Uids of the contacts of `uid`. */
  contactIds(uid: string): Promise<string[]>;
  /** Removes `reader` from every share of `owner` (deleting a share left with nobody). Returns how many. */
  revoke(owner: string, reader: string): Promise<number>;
  /** Deletes the link and both contacts. */
  unlink(a: string, b: string): Promise<void>;
  item(uid: string, itemId: string): Promise<Item | null>;
  share(id: string): Promise<ShareDoc | null>;
  putShare(doc: ShareDoc): Promise<void>;
  deleteShare(id: string): Promise<void>;
  addNotice(uid: string, n: Notice): Promise<void>;
}

/** A push to one person, whatever channels they have (phone apps, browsers, Pushover). */
export interface PushMessage {
  title: string;
  body: string;
  /** Where tapping it leads (web address); the Android app opens its own screen from `shareId`. */
  url?: string;
  kind: "linked" | "unlinked" | "new" | "edited" | "deleted";
  shareId?: string;
}

export interface SharePusher {
  send(uid: string, m: PushMessage): Promise<void>;
}

export interface ShareDeps {
  store: ShareStore;
  pusher: SharePusher;
  now: () => number;
  /** Uniform random integer in [0, n). */
  randomInt: (n: number) => number;
  appUrl?: string;
  /** Time zone used in push texts. */
  zone?: string;
}

export const hashCode = (code: string): string => createHash("sha256").update(`vhub-link:${code}`).digest("hex");

export function makeCode(randomInt: (n: number) => number): string {
  let s = "";
  for (let i = 0; i < CODE_LENGTH; i++) s += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return s;
}

// ---------------------------------------------------------------- requests

export type ShareRequest =
  | { action: "linkCreate" }
  | { action: "linkPreview"; code: string }
  | { action: "linkJoin"; code: string; alias: string }
  | { action: "linkRemove"; uid: string }
  | { action: "shareSet"; itemId: string; audience: string[] };

const ID = /^[A-Za-z0-9_-]{1,128}$/;

/** Validates the untrusted payload of the callable. Anything unexpected is refused, not ignored. */
export function parseShareRequest(data: unknown): ShareRequest {
  const bad = (m = "Yêu cầu không hợp lệ") => new ShareError("invalid-argument", m);
  if (!data || typeof data !== "object" || Array.isArray(data)) throw bad();
  const d = data as Record<string, unknown>;
  const only = (...keys: string[]) => {
    for (const k of Object.keys(d)) if (!keys.includes(k)) throw bad();
  };
  switch (d.action) {
    case "linkCreate":
      only("action");
      return { action: "linkCreate" };
    case "linkPreview": {
      only("action", "code");
      const code = normalizeCode(d.code);
      if (!code) throw bad("Mã gồm 8 ký tự chữ và số");
      return { action: "linkPreview", code };
    }
    case "linkJoin": {
      only("action", "code", "alias");
      const code = normalizeCode(d.code);
      if (!code) throw bad("Mã gồm 8 ký tự chữ và số");
      if (typeof d.alias !== "string") throw bad();
      return { action: "linkJoin", code, alias: cleanAlias(d.alias) };
    }
    case "linkRemove":
      only("action", "uid");
      if (typeof d.uid !== "string" || !ID.test(d.uid)) throw bad();
      return { action: "linkRemove", uid: d.uid };
    case "shareSet": {
      only("action", "itemId", "audience");
      if (typeof d.itemId !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(d.itemId)) throw bad();
      if (!Array.isArray(d.audience) || d.audience.length > SHARE_LIMITS.audience) throw bad(`Chia sẻ tối đa ${SHARE_LIMITS.audience} người mỗi mục`);
      if (!d.audience.every((u) => typeof u === "string" && ID.test(u))) throw bad();
      return { action: "shareSet", itemId: d.itemId, audience: [...new Set(d.audience as string[])] };
    }
    default:
      throw bad();
  }
}

// ---------------------------------------------------------------- the callable

export async function handleShare(deps: ShareDeps, caller: Caller, req: ShareRequest): Promise<unknown> {
  switch (req.action) {
    case "linkCreate": return linkCreate(deps, caller);
    case "linkPreview": return linkPreview(deps, caller, req.code);
    case "linkJoin": return linkJoin(deps, caller, req.code, req.alias);
    case "linkRemove": return linkRemove(deps, caller, req.uid);
    case "shareSet": return shareSet(deps, caller, req.itemId, req.audience);
  }
}

const displayName = (c: Caller): string => cleanAlias(c.name) || c.email.split("@")[0] || "Người dùng";

async function linkCreate(deps: ShareDeps, caller: Caller): Promise<{ code: string; expiresAt: number }> {
  const now = deps.now();
  if ((await deps.store.contactIds(caller.uid)).length >= SHARE_LIMITS.contacts) {
    throw new ShareError("resource-exhausted", `Đã đủ ${SHARE_LIMITS.contacts} liên hệ, hãy huỷ bớt trước khi thêm người mới`);
  }
  const code = makeCode(deps.randomInt);
  const expiresAt = now + SHARE_LIMITS.codeMinutes * 60_000;
  await deps.store.putCode(hashCode(code), { ownerUid: caller.uid, ownerName: displayName(caller), ownerEmail: caller.email, expiresAt });
  return { code, expiresAt };
}

/** Finds a live code, counting a miss against the caller (the brute-force brake). */
async function liveCode(deps: ShareDeps, caller: Caller, code: string): Promise<CodeRecord> {
  const now = deps.now();
  if ((await deps.store.failures(caller.uid, now - 3600_000)) >= SHARE_LIMITS.failuresPerHour) {
    throw new ShareError("resource-exhausted", "Nhập sai quá nhiều lần. Thử lại sau 1 giờ");
  }
  const rec = await deps.store.getCode(hashCode(code));
  if (!rec || rec.expiresAt <= now) {
    await deps.store.recordFailure(caller.uid, now);
    throw new ShareError("not-found", "Mã không đúng hoặc đã hết hạn. Nhờ người kia tạo mã mới");
  }
  if (rec.ownerUid === caller.uid) throw new ShareError("failed-precondition", "Đây là mã của chính bạn. Gửi mã cho người kia nhập");
  return rec;
}

async function linkPreview(deps: ShareDeps, caller: Caller, code: string): Promise<{ name: string; maskedEmail: string; already: boolean }> {
  const rec = await liveCode(deps, caller, code);
  const already = (await deps.store.contact(caller.uid, rec.ownerUid)) !== null;
  return { name: rec.ownerName, maskedEmail: maskEmail(rec.ownerEmail), already };
}

async function linkJoin(deps: ShareDeps, caller: Caller, code: string, alias: string): Promise<{ contact: Contact }> {
  const rec = await liveCode(deps, caller, code);
  const now = deps.now();
  const joinerName = displayName(caller);
  // What the joiner calls the owner (their choice), and what the owner calls the joiner until they rename it.
  const forJoiner: Contact = { uid: rec.ownerUid, alias: alias || rec.ownerName, googleName: rec.ownerName, maskedEmail: maskEmail(rec.ownerEmail), linkedAt: now, notify: true };
  const forOwner: Contact = { uid: caller.uid, alias: joinerName, googleName: joinerName, maskedEmail: maskEmail(caller.email), linkedAt: now, notify: true };
  await deps.store.join(hashCode(code), forJoiner, forOwner, now);
  await deps.store.addNotice(rec.ownerUid, { id: `linked-${caller.uid}-${now}`, kind: "linked", fromUid: caller.uid, fromName: joinerName, at: now });
  await safePush(deps, rec.ownerUid, {
    kind: "linked",
    title: `${joinerName} đã liên kết với bạn`,
    body: "Hai bạn có thể chia sẻ từng việc, lịch hẹn, ghi chú cho nhau. Mở app để đặt tên gợi nhớ.",
    url: deps.appUrl ? `${deps.appUrl}/#/lien-he` : undefined,
  });
  return { contact: forJoiner };
}

async function linkRemove(deps: ShareDeps, caller: Caller, other: string): Promise<{ removed: true }> {
  const mine = await deps.store.contact(caller.uid, other);
  const theirs = await deps.store.contact(other, caller.uid);
  if (!mine && !theirs) return { removed: true };
  // Access first, the link last: if anything fails half-way the link is still there and pressing again is safe.
  await deps.store.revoke(caller.uid, other);
  await deps.store.revoke(other, caller.uid);
  await deps.store.unlink(caller.uid, other);
  const now = deps.now();
  const name = theirs?.alias || displayName(caller);
  await deps.store.addNotice(other, { id: `unlinked-${caller.uid}-${now}`, kind: "unlinked", fromUid: caller.uid, fromName: name, at: now });
  await safePush(deps, other, {
    kind: "unlinked",
    title: `${name} đã huỷ liên kết với bạn`,
    body: "Các mục hai bạn chia sẻ cho nhau không còn hiện nữa.",
    url: deps.appUrl ? `${deps.appUrl}/#/lien-he` : undefined,
  });
  return { removed: true };
}

async function shareSet(deps: ShareDeps, caller: Caller, itemId: string, audience: string[]): Promise<{ audience: string[] }> {
  const item = await deps.store.item(caller.uid, itemId);
  if (!item) throw new ShareError("not-found", "Không tìm thấy mục này (có thể chưa lưu xong). Thử lại sau giây lát");
  const id = shareId(caller.uid, itemId);
  const existing = await deps.store.share(id);
  if (audience.length === 0) {
    if (existing) await deps.store.deleteShare(id);
    return { audience: [] };
  }
  if (!isShareable(item.type)) throw new ShareError("failed-precondition", "Chỉ chia sẻ được việc, lịch hẹn và ghi chú");
  if (item.status === "DRAFT") throw new ShareError("failed-precondition", "Lưu mục này trước rồi mới chia sẻ");
  const contacts = new Set(await deps.store.contactIds(caller.uid));
  const strangers = audience.filter((u) => !contacts.has(u));
  if (strangers.length > 0) throw new ShareError("permission-denied", "Chỉ chia sẻ được với người đã liên kết");
  const now = deps.now();
  const doc: ShareDoc = {
    id, ownerUid: caller.uid, ownerName: displayName(caller), itemId, audience: [...audience].sort(),
    ...sharedFields(item), updatedAt: now,
  };
  await deps.store.putShare(doc);
  const before = new Set(existing?.audience ?? []);
  const added = audience.filter((u) => !before.has(u));
  await Promise.all(added.map((u) => pushAbout(deps, u, doc, "new")));
  return { audience: doc.audience };
}

// ---------------------------------------------------------------- the trigger (every write of an item)

/**
 * Keeps the read-only view in step with its original: an edit updates it (and tells the recipients),
 * a delete removes it (and tells them), a type that cannot be shared or a draft takes it down. The next
 * occurrence of a repeating task inherits its parent's audience.
 */
export async function onItemWritten(deps: ShareDeps, ownerUid: string, itemId: string, after: Item | null): Promise<"none" | "created" | "updated" | "deleted"> {
  const id = shareId(ownerUid, itemId);
  const existing = await deps.store.share(id);
  const now = deps.now();

  if (!existing) {
    const parent = after ? parentOfRepeat(itemId) : null;
    if (!after || !parent || !isShareable(after.type) || after.status === "DRAFT") return "none";
    const from = await deps.store.share(shareId(ownerUid, parent));
    if (!from) return "none";
    const contacts = new Set(await deps.store.contactIds(ownerUid));
    const audience = from.audience.filter((u) => contacts.has(u));
    if (audience.length === 0) return "none";
    await deps.store.putShare({ ...from, id, itemId, audience, ...sharedFields(after), updatedAt: now });
    return "created";
  }

  if (!after || !isShareable(after.type) || after.status === "DRAFT") {
    await deps.store.deleteShare(id);
    if (!after) await Promise.all(existing.audience.map((u) => pushAbout(deps, u, existing, "deleted")));
    return "deleted";
  }

  const next = sharedFields(after);
  const prev = fieldsOf(existing);
  if (same(prev, next)) return "none";
  const doc: ShareDoc = { ...stripFields(existing), ...next, updatedAt: now };
  await deps.store.putShare(doc);
  if (changeKind(prev, next, now) === "edited") {
    await Promise.all(existing.audience.map((u) => pushAbout(deps, u, doc, "edited", changedLabels(prev, next))));
  }
  return "updated";
}

const FIELD_KEYS = ["type", "status", "title", "details", "whenAt", "allDay", "place", "recurrence", "important", "doneAt", "createdAt"] as const;

function fieldsOf(d: ShareDoc): SharedFields {
  const out: Record<string, unknown> = {};
  for (const k of FIELD_KEYS) if (d[k] !== undefined && d[k] !== null) out[k] = d[k];
  return out as unknown as SharedFields;
}

function stripFields(d: ShareDoc): ShareDoc {
  const out = { ...d } as Record<string, unknown>;
  for (const k of FIELD_KEYS) delete out[k];
  return out as unknown as ShareDoc;
}

function same(a: SharedFields, b: SharedFields): boolean {
  return FIELD_KEYS.every((k) => (a[k] ?? null) === (b[k] ?? null));
}

// ---------------------------------------------------------------- push texts

const WEEKDAY = ["", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"];

/** "Thứ Sáu 17/10 · 18:00", "Cả ngày Thứ Sáu 17/10", or "" when the item has no date. */
export function whenText(f: Pick<SharedFields, "whenAt" | "allDay">, zone = "Asia/Ho_Chi_Minh"): string {
  if (f.whenAt === undefined) return "";
  const d = DateTime.fromMillis(f.whenAt, { zone });
  const day = `${WEEKDAY[d.weekday]} ${d.toFormat("dd/MM")}`;
  return f.allDay ? `Cả ngày ${day}` : `${day} · ${d.toFormat("HH:mm")}`;
}

async function pushAbout(deps: ShareDeps, recipient: string, doc: ShareDoc, kind: "new" | "edited" | "deleted", changed: string[] = []): Promise<void> {
  const c = await deps.store.contact(recipient, doc.ownerUid);
  if (!c || !c.notify) return;
  const who = c.alias || doc.ownerName;
  const when = whenText(doc, deps.zone);
  const facts = [when, doc.place].filter(Boolean).join(" · ");
  const title =
    kind === "new" ? `${who} chia sẻ: ${doc.title}` :
    kind === "edited" ? `${who} đã sửa: ${doc.title}` :
    `${who} đã xoá: ${doc.title}`;
  const body =
    kind === "edited" ? [changed.length ? `Đổi ${changed.join(", ")}` : "", facts].filter(Boolean).join(" · ") :
    kind === "deleted" ? "Mục này không còn nữa." :
    facts || doc.details.slice(0, 120) || "Mở app để xem.";
  await safePush(deps, recipient, {
    kind, title: title.slice(0, 200), body: body.slice(0, 500), shareId: doc.id,
    url: deps.appUrl ? (kind === "deleted" ? `${deps.appUrl}/#/` : `${deps.appUrl}/#/chia-se/${doc.id}`) : undefined,
  });
}

/** A push that fails must never undo the change it is about. */
async function safePush(deps: ShareDeps, uid: string, m: PushMessage): Promise<void> {
  try {
    await deps.pusher.send(uid, m);
  } catch {
    // Logged by the pusher; nothing to roll back.
  }
}
