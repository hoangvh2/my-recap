import type { Firestore } from "firebase-admin/firestore";
import type { Messaging } from "firebase-admin/messaging";

/**
 * "Tìm điện thoại": from the web app, make one of my own Android phones ring so I can find it.
 *   list  → my phones that have the feature on (never their push tokens)
 *   ring  → push a data message "find-ring" to that phone (it rings for at most 30 s, with a Stop button)
 *   stop  → push "find-stop"
 * Only the caller's own devices are reachable. A ring command expires after a minute (FCM ttl), so a phone that was
 * off does not start ringing hours later. Rings to one phone are at least MIN_GAP_MS apart.
 *   users/{uid}/findphone/{deviceId}   { at }   (server only; the rules give clients no access)
 */

export class FindPhoneError extends Error {
  constructor(readonly code: "invalid-argument" | "not-found" | "resource-exhausted", message: string) {
    super(message);
  }
}

export const MIN_GAP_MS = 5_000;
export const RING_TTL_MS = 60_000;
const DEVICE_ID = /^[A-Za-z0-9_-]{1,64}$/;

export type FindRequest = { action: "list" } | { action: "ring" | "stop"; id: string };

export interface DeviceRecord {
  id: string;
  token: string;
  platform: string;
  name: string;
  updatedAt: number;
  /** The phone's own switch ("Cho phép tìm máy từ web"); older apps never wrote it, which means on. */
  find: boolean;
}

export interface PhoneInfo {
  id: string;
  name: string;
  updatedAt: number;
}

export interface FindStore {
  devices(uid: string): Promise<DeviceRecord[]>;
  forget(uid: string, id: string): Promise<void>;
  lastRing(uid: string, id: string): Promise<number>;
  markRing(uid: string, id: string, at: number): Promise<void>;
}

export interface FindSender {
  /** "gone" when the push service says the token is not valid any more. */
  send(token: string, command: "find-ring" | "find-stop"): Promise<"sent" | "gone">;
}

export type FindResult = { devices: PhoneInfo[] } | { sent: true };

export function parseFindRequest(data: unknown): FindRequest {
  const bad = () => new FindPhoneError("invalid-argument", "Yêu cầu không hợp lệ");
  if (!data || typeof data !== "object" || Array.isArray(data)) throw bad();
  const d = data as Record<string, unknown>;
  if (d.action === "list" && Object.keys(d).length === 1) return { action: "list" };
  if ((d.action === "ring" || d.action === "stop") && Object.keys(d).every((k) => k === "action" || k === "id")) {
    if (typeof d.id !== "string" || !DEVICE_ID.test(d.id)) throw bad();
    return { action: d.action, id: d.id };
  }
  throw bad();
}

export async function handleFindPhone(store: FindStore, sender: FindSender, uid: string, req: FindRequest, now: number): Promise<FindResult> {
  const phones = (await store.devices(uid)).filter((d) => d.platform === "android" && d.find);
  if (req.action === "list") {
    return { devices: phones.map((d) => ({ id: d.id, name: d.name, updatedAt: d.updatedAt })).sort((a, b) => b.updatedAt - a.updatedAt) };
  }
  const phone = phones.find((d) => d.id === req.id);
  if (!phone) throw new FindPhoneError("not-found", "Không thấy điện thoại này. Mở app Android, bật \"Cho phép tìm máy từ web\" rồi thử lại");
  if (req.action === "ring") {
    const last = await store.lastRing(uid, phone.id);
    if (now - last < MIN_GAP_MS) throw new FindPhoneError("resource-exhausted", "Vừa gửi lệnh xong, đợi vài giây rồi thử lại");
    await store.markRing(uid, phone.id, now);
  }
  const res = await sender.send(phone.token, req.action === "ring" ? "find-ring" : "find-stop");
  if (res === "gone") {
    await store.forget(uid, phone.id);
    throw new FindPhoneError("not-found", "Điện thoại này không nhận được lệnh nữa. Mở app Android và đăng nhập lại");
  }
  return { sent: true };
}

export class FirestoreFindStore implements FindStore {
  constructor(private readonly db: Firestore) {}

  async devices(uid: string): Promise<DeviceRecord[]> {
    const snap = await this.db.collection(`users/${uid}/devices`).limit(20).get();
    return snap.docs.map((d) => ({
      id: d.id,
      token: String(d.get("token") ?? ""),
      platform: String(d.get("platform") ?? ""),
      name: cleanName(d.get("name")),
      updatedAt: Number(d.get("updatedAt") ?? 0) || 0,
      find: d.get("find") !== false,
    })).filter((d) => d.token.length > 20);
  }

  async forget(uid: string, id: string): Promise<void> {
    await this.db.doc(`users/${uid}/devices/${id}`).delete();
  }

  async lastRing(uid: string, id: string): Promise<number> {
    return Number((await this.db.doc(`users/${uid}/findphone/${id}`).get()).get("at") ?? 0) || 0;
  }

  async markRing(uid: string, id: string, at: number): Promise<void> {
    await this.db.doc(`users/${uid}/findphone/${id}`).set({ at });
  }
}

export function cleanName(v: unknown): string {
  const s = typeof v === "string" ? v.replace(/\s+/g, " ").trim().slice(0, 60) : "";
  return s || "Điện thoại Android";
}

export class FcmFindSender implements FindSender {
  constructor(private readonly fcm: Messaging | null) {}

  async send(token: string, command: "find-ring" | "find-stop"): Promise<"sent" | "gone"> {
    if (!this.fcm) return "sent";
    try {
      // Data only and high priority, so the phone wakes up for it; it expires fast on purpose.
      await this.fcm.send({ token, data: { kind: command }, android: { priority: "high", ttl: RING_TTL_MS } });
      return "sent";
    } catch (e) {
      const code = (e as { code?: string } | null)?.code ?? "";
      if (/registration-token-not-registered|invalid-registration-token|invalid-argument/.test(code)) return "gone";
      throw e;
    }
  }
}
