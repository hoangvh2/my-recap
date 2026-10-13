import type { Firestore } from "firebase-admin/firestore";
import type { TokenClaims } from "./auth";

/**
 * Who may use the app beyond the addresses fixed at deploy time (ALLOWED_EMAILS). Those fixed addresses are the
 * administrators: they add family and friends here, and the change applies at once, with no redeploy.
 *   members/{email}   { email, addedBy, addedAt }   (no client access; the rules only check that it exists)
 */

export class MembersError extends Error {
  constructor(readonly code: "invalid-argument" | "permission-denied" | "resource-exhausted", message: string) {
    super(message);
  }
}

export const MAX_MEMBERS = 200;
const EMAIL = /^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$/;

export interface Member {
  email: string;
  addedBy: string;
  addedAt: number;
}

export interface MembersStore {
  has(email: string): Promise<boolean>;
  list(): Promise<Member[]>;
  add(m: Member): Promise<void>;
  remove(email: string): Promise<void>;
}

export type MembersRequest = { action: "list" } | { action: "add"; email: string } | { action: "remove"; email: string };

export function parseMembersRequest(data: unknown): MembersRequest {
  const bad = (m = "Yêu cầu không hợp lệ") => new MembersError("invalid-argument", m);
  if (!data || typeof data !== "object" || Array.isArray(data)) throw bad();
  const d = data as Record<string, unknown>;
  if (d.action === "list" && Object.keys(d).length === 1) return { action: "list" };
  if ((d.action === "add" || d.action === "remove") && Object.keys(d).every((k) => k === "action" || k === "email")) {
    const email = typeof d.email === "string" ? d.email.trim().toLowerCase() : "";
    if (!EMAIL.test(email) || email.length > 120) throw bad("Địa chỉ Gmail chưa đúng");
    return { action: d.action, email };
  }
  throw bad();
}

/** Verified Google account whose address is a member (the fixed list is checked separately, without a read). */
export async function isMemberToken(store: MembersStore, token: TokenClaims | undefined): Promise<boolean> {
  if (!token || token.email_verified !== true || token.firebase?.sign_in_provider !== "google.com") return false;
  const email = token.email?.trim().toLowerCase();
  return !!email && EMAIL.test(email) && (await store.has(email));
}

export async function manageMembers(store: MembersStore, callerEmail: string, admins: readonly string[], req: MembersRequest, nowMs: number): Promise<{ admin: boolean; admins: string[]; members: Member[] }> {
  const me = callerEmail.trim().toLowerCase();
  const admin = admins.includes(me);
  if (!admin) {
    if (req.action === "list") return { admin: false, admins: [], members: [] };
    throw new MembersError("permission-denied", "Chỉ quản trị viên mới thêm hoặc bớt người dùng");
  }
  if (req.action === "add") {
    if (admins.includes(req.email)) throw new MembersError("invalid-argument", "Địa chỉ này đã là quản trị viên");
    if ((await store.list()).length >= MAX_MEMBERS) throw new MembersError("resource-exhausted", `Tối đa ${MAX_MEMBERS} người dùng`);
    await store.add({ email: req.email, addedBy: me, addedAt: nowMs });
  }
  if (req.action === "remove") await store.remove(req.email);
  const members = (await store.list()).sort((a, b) => a.email.localeCompare(b.email));
  return { admin: true, admins: [...admins], members };
}

export class FirestoreMembersStore implements MembersStore {
  constructor(private readonly db: Firestore) {}
  async has(email: string): Promise<boolean> {
    return (await this.db.doc(`members/${email}`).get()).exists;
  }
  async list(): Promise<Member[]> {
    const snap = await this.db.collection("members").limit(MAX_MEMBERS + 10).get();
    return snap.docs.map((d) => d.data() as Member);
  }
  async add(m: Member): Promise<void> {
    await this.db.doc(`members/${m.email}`).set(m);
  }
  async remove(email: string): Promise<void> {
    await this.db.doc(`members/${email}`).delete();
  }
}
