import { readFileSync } from "node:fs";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, limit, query, setDoc, updateDoc } from "firebase/firestore";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";

// The rules are generated from firestore.rules.tmpl with the throwaway list in config/allowed-emails.local
// (owner@example.com, member@example.com): `node scripts/configure.mjs`.
let env: RulesTestEnvironment;

const google = (email: string, extra: Record<string, unknown> = {}) => ({
  email,
  email_verified: true,
  firebase: { sign_in_provider: "google.com" },
  ...extra,
});

const ownerCtx = () => env.authenticatedContext("uOwner", google("owner@example.com")).firestore();
const memberCtx = () => env.authenticatedContext("uMember", google("member@example.com")).firestore();

const item = (over: Record<string, unknown> = {}) => ({
  id: "a1",
  type: "TASK",
  status: "OPEN",
  title: "Gọi mẹ",
  details: "",
  allDay: false,
  createdAt: 1_760_000_000_000,
  ...over,
});

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-myrecap-rules",
    firestore: { rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
  });
});
afterAll(async () => env.cleanup());
beforeEach(async () => env.clearFirestore());

describe("who may use the database", () => {
  it("lets an allowlisted Google account read and write its own items", async () => {
    const db = ownerCtx();
    await assertSucceeds(setDoc(doc(db, "users/uOwner/items/a1"), item()));
    await assertSucceeds(getDoc(doc(db, "users/uOwner/items/a1")));
    await assertSucceeds(getDocs(query(collection(db, "users/uOwner/items"), limit(100))));
    await assertSucceeds(updateDoc(doc(db, "users/uOwner/items/a1"), { title: "Gọi bố" }));
    await assertSucceeds(deleteDoc(doc(db, "users/uOwner/items/a1")));
  });

  it("keeps the two people apart", async () => {
    await assertSucceeds(setDoc(doc(memberCtx(), "users/uMember/items/w1"), item({ id: "w1" })));
    await assertFails(getDoc(doc(ownerCtx(), "users/uMember/items/w1")));
    await assertFails(setDoc(doc(ownerCtx(), "users/uMember/items/x"), item({ id: "x" })));
    await assertFails(deleteDoc(doc(ownerCtx(), "users/uMember/items/w1")));
    await assertFails(getDocs(query(collection(ownerCtx(), "users/uMember/items"), limit(10))));
  });

  it("denies anonymous and signed-out access", async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "users/uOwner/items/a1")));
    const anon = env.authenticatedContext("uOwner", { firebase: { sign_in_provider: "anonymous" } }).firestore();
    await assertFails(setDoc(doc(anon, "users/uOwner/items/a1"), item()));
  });

  it("denies a Google account that is not on the list, even under its own uid", async () => {
    const stranger = env.authenticatedContext("uX", google("stranger@example.com")).firestore();
    await assertFails(setDoc(doc(stranger, "users/uX/items/a1"), item()));
    await assertFails(getDoc(doc(stranger, "users/uX/items/a1")));
  });

  it("denies an unverified address, a non-Google provider, and look-alike addresses", async () => {
    const mk = (claims: Record<string, unknown>) => env.authenticatedContext("uOwner", claims).firestore();
    const target = (db: ReturnType<typeof mk>) => setDoc(doc(db, "users/uOwner/items/a1"), item());
    await assertFails(target(mk(google("owner@example.com", { email_verified: false }))));
    await assertFails(target(mk({ email: "owner@example.com", email_verified: true, firebase: { sign_in_provider: "password" } })));
    await assertFails(target(mk(google("owner@example.com.evil.io"))));
    await assertFails(target(mk(google("xowner@example.com"))));
    await assertFails(target(mk({ email_verified: true, firebase: { sign_in_provider: "google.com" } })));
  });

  it("matches the allowlist regardless of letter case", async () => {
    const db = env.authenticatedContext("uOwner", google("Owner@Example.COM")).firestore();
    await assertSucceeds(setDoc(doc(db, "users/uOwner/items/a1"), item()));
  });

  it("denies every path it does not know", async () => {
    const db = ownerCtx();
    await assertFails(setDoc(doc(db, "secrets/x"), { a: 1 }));
    await assertFails(getDoc(doc(db, "users/uOwner")));
    await assertFails(setDoc(doc(db, "users/uOwner/other/x"), { a: 1 }));
  });
});

describe("server-owned data", () => {
  it("never lets a client touch the quota counter, even its own", async () => {
    const db = ownerCtx();
    await assertFails(setDoc(doc(db, "users/uOwner/meta/usage"), { day: "2026-10-10", count: 0 }));
    await assertFails(getDoc(doc(db, "users/uOwner/meta/usage")));
    await assertFails(deleteDoc(doc(db, "users/uOwner/meta/usage")));
  });

  it("lets the owner read and delete captures but not forge them", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users/uOwner/captures/c1"), { id: "c1", kind: "text", transcript: "x", createdAt: 1, itemCount: 0 });
    });
    const db = ownerCtx();
    await assertSucceeds(getDoc(doc(db, "users/uOwner/captures/c1")));
    await assertFails(setDoc(doc(db, "users/uOwner/captures/c2"), { id: "c2", kind: "text", transcript: "x", createdAt: 1, itemCount: 0 }));
    await assertFails(updateDoc(doc(db, "users/uOwner/captures/c1"), { transcript: "changed" }));
    await assertSucceeds(deleteDoc(doc(db, "users/uOwner/captures/c1")));
  });
});

describe("item schema", () => {
  const put = (data: Record<string, unknown>, id = "a1") => setDoc(doc(ownerCtx(), `users/uOwner/items/${id}`), data);

  it("accepts every field the app writes", async () => {
    await assertSucceeds(put(item({
      type: "EXPENSE", title: "Taxi", details: "sân bay", whenAt: 1_760_000_000_000, allDay: true, amount: 250_000,
      category: "Di chuyển", place: "Tân Sơn Nhất", person: "tài xế", sourceId: "cap1", quote: "taxi 250k", doneAt: 5,
    })));
    await assertSucceeds(put(item({ id: "r", recurrence: "WEEKLY", whenAt: 1 }), "r"));
  });

  it("rejects unknown fields, wrong types and enum values", async () => {
    await assertFails(put(item({ admin: true })));
    await assertFails(put(item({ type: "EVIL" })));
    await assertFails(put(item({ status: "ARCHIVED" })));
    await assertFails(put(item({ title: 5 })));
    await assertFails(put(item({ allDay: "yes" })));
    await assertFails(put(item({ createdAt: "now" })));
    await assertFails(put(item({ whenAt: "tomorrow" })));
    await assertFails(put(item({ recurrence: "HOURLY" })));
  });

  it("rejects missing required fields and mismatched ids", async () => {
    const { title: _t, ...noTitle } = item();
    await assertFails(put(noTitle));
    await assertFails(put(item({ id: "other" })));
    await assertFails(put(item({ title: "" })));
  });

  it("enforces size limits", async () => {
    await assertSucceeds(put(item({ title: "x".repeat(120) })));
    await assertFails(put(item({ title: "x".repeat(121) })));
    await assertFails(put(item({ details: "x".repeat(2001) })));
    await assertFails(put(item({ place: "x".repeat(121) })));
    await assertFails(put(item({ quote: "x".repeat(401) })));
    await assertFails(put(item({ category: "x".repeat(41) })));
  });

  it("only expenses carry an amount, and it must be a sane integer", async () => {
    await assertFails(put(item({ type: "TASK", amount: 100 })));
    await assertFails(put(item({ type: "EXPENSE", amount: -5 })));
    await assertFails(put(item({ type: "EXPENSE", amount: 1.5 })));
    await assertFails(put(item({ type: "EXPENSE", amount: 10 ** 13 })));
    await assertSucceeds(put(item({ type: "EXPENSE", amount: 85_000 })));
  });

  it("rejects ids that could be paths or junk", async () => {
    await assertFails(put(item({ id: "a b" }), "a b"));
    await assertFails(put(item({ id: "x".repeat(65) }), "x".repeat(65)));
  });

  it("does not let an update rewrite the creation time", async () => {
    await assertSucceeds(put(item()));
    await assertFails(updateDoc(doc(ownerCtx(), "users/uOwner/items/a1"), { createdAt: 1 }));
    await assertFails(updateDoc(doc(ownerCtx(), "users/uOwner/items/a1"), { nonsense: 1 }));
  });

  it("requires a bounded list query", async () => {
    const db = ownerCtx();
    await assertFails(getDocs(collection(db, "users/uOwner/items")));
    await assertFails(getDocs(query(collection(db, "users/uOwner/items"), limit(5000))));
    await assertSucceeds(getDocs(query(collection(db, "users/uOwner/items"), limit(2000))));
  });
});

describe("generated config", () => {
  it("contains the throwaway test addresses only", () => {
    const rules = readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8");
    expect(rules).toContain("'owner@example.com'");
    expect(rules).not.toContain("__ALLOWED_EMAILS__");
  });
});
