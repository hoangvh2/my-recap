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

const customer = (over: Record<string, unknown> = {}) => ({ id: "c1", status: "OPEN", name: "Công ty ABC", createdAt: 1_760_000_000_000, updatedAt: 1_760_000_000_000, ...over });
const license = (over: Record<string, unknown> = {}) => ({
  id: "l1", customerId: "c1", status: "OPEN", product: "Phần mềm kế toán", kind: "LICENSE", endDate: "2027-03-15", stage: "ACTIVE",
  stageAt: 1_760_000_000_000, createdAt: 1_760_000_000_000, updatedAt: 1_760_000_000_000, ...over,
});

describe("customers", () => {
  const put = (data: Record<string, unknown>, id = "c1") => setDoc(doc(ownerCtx(), `users/uOwner/customers/${id}`), data);
  it("lets the owner manage their own customers and nobody else touch them", async () => {
    await assertSucceeds(put(customer({ contact: "anh Nam", phone: "0912345678", email: "nam@abc.vn", note: "Khách cũ", sourceId: "cap1" })));
    await assertSucceeds(getDoc(doc(ownerCtx(), "users/uOwner/customers/c1")));
    await assertSucceeds(getDocs(query(collection(ownerCtx(), "users/uOwner/customers"), limit(2000))));
    await assertSucceeds(updateDoc(doc(ownerCtx(), "users/uOwner/customers/c1"), { name: "ABC Corp", updatedAt: 2 }));
    await assertFails(getDoc(doc(memberCtx(), "users/uOwner/customers/c1")));
    await assertFails(setDoc(doc(memberCtx(), "users/uOwner/customers/c9"), customer({ id: "c9" })));
    await assertSucceeds(deleteDoc(doc(ownerCtx(), "users/uOwner/customers/c1")));
  });
  it("enforces the schema", async () => {
    await assertFails(put(customer({ extra: 1 })));
    await assertFails(put(customer({ name: "" })));
    await assertFails(put(customer({ name: "x".repeat(121) })));
    await assertFails(put(customer({ status: "ARCHIVED" })));
    await assertFails(put(customer({ phone: "9".repeat(41) })));
    await assertFails(put(customer({ note: "x".repeat(2001) })));
    await assertFails(put(customer({ id: "other" })));
    await assertFails(put(customer({ createdAt: "now" })));
    await assertFails(put(customer({ name: 5 })));
    const { name: _n, ...noName } = customer();
    await assertFails(put(noName));
  });
  it("keeps the creation time and requires a bounded list", async () => {
    await assertSucceeds(put(customer()));
    await assertFails(updateDoc(doc(ownerCtx(), "users/uOwner/customers/c1"), { createdAt: 5 }));
    await assertFails(getDocs(collection(ownerCtx(), "users/uOwner/customers")));
    await assertFails(getDocs(query(collection(ownerCtx(), "users/uOwner/customers"), limit(5000))));
  });
});

describe("licences", () => {
  const put = (data: Record<string, unknown>, id = "l1") => setDoc(doc(ownerCtx(), `users/uOwner/licenses/${id}`), data);
  it("stores every field the app writes, for the owner only", async () => {
    await assertSucceeds(put(license({
      startDate: "2026-03-16", termMonths: 12, noticeDays: 30, value: 120_000_000, contractNo: "HD-01", note: "x", snoozeUntil: "2026-12-01",
      lostReason: "Giá cao", renewedFromId: "l0", renewedToId: "l2", sourceId: "cap1",
    })));
    await assertSucceeds(updateDoc(doc(ownerCtx(), "users/uOwner/licenses/l1"), { stage: "ASKED", updatedAt: 2 }));
    await assertFails(getDoc(doc(memberCtx(), "users/uOwner/licenses/l1")));
    await assertFails(put(license({ id: "l9" }), "l9").then(() => setDoc(doc(memberCtx(), "users/uOwner/licenses/l9"), license({ id: "l9" }))));
    await assertSucceeds(getDocs(query(collection(ownerCtx(), "users/uOwner/licenses"), limit(3000))));
    await assertSucceeds(deleteDoc(doc(ownerCtx(), "users/uOwner/licenses/l1")));
  });
  it("rejects bad enums, dates and numbers", async () => {
    await assertFails(put(license({ kind: "TRIAL" })));
    await assertFails(put(license({ stage: "DONE" })));
    await assertFails(put(license({ endDate: "15/03/2027" })));
    await assertFails(put(license({ endDate: "2027-3-15" })));
    await assertFails(put(license({ startDate: "soon" })));
    await assertFails(put(license({ snoozeUntil: 5 })));
    await assertFails(put(license({ termMonths: 0 })));
    await assertFails(put(license({ termMonths: 121 })));
    await assertFails(put(license({ noticeDays: -1 })));
    await assertFails(put(license({ noticeDays: 366 })));
    await assertFails(put(license({ value: -5 })));
    await assertFails(put(license({ value: 1.5 })));
    await assertSucceeds(put(license({ value: 0, noticeDays: 0 })));
  });
  it("rejects unknown fields, missing fields, long text and odd ids", async () => {
    await assertFails(put(license({ admin: true })));
    const { endDate: _e, ...noEnd } = license();
    await assertFails(put(noEnd));
    const { customerId: _c, ...noCustomer } = license();
    await assertFails(put(noCustomer));
    await assertFails(put(license({ product: "" })));
    await assertFails(put(license({ product: "x".repeat(121) })));
    await assertFails(put(license({ contractNo: "x".repeat(61) })));
    await assertFails(put(license({ lostReason: "x".repeat(201) })));
    await assertFails(put(license({ customerId: "has space" })));
    await assertFails(put(license({ id: "other" })));
    await assertFails(put(license({ id: "a b" }), "a b"));
  });
  it("keeps the creation time", async () => {
    await assertSucceeds(put(license()));
    await assertFails(updateDoc(doc(ownerCtx(), "users/uOwner/licenses/l1"), { createdAt: 1 }));
  });
});

describe("items linked to customers and licences", () => {
  const put = (data: Record<string, unknown>) => setDoc(doc(ownerCtx(), "users/uOwner/items/a1"), data);
  it("accepts the two link fields and checks them", async () => {
    await assertSucceeds(put(item({ customerId: "c1", licenseId: "l1" })));
    await assertFails(put(item({ customerId: 5 })));
    await assertFails(put(item({ licenseId: "x".repeat(65) })));
  });
});

describe("AI proposals", () => {
  it("can be read and discarded by the owner but never written by a client", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users/uOwner/proposals/p1"), { id: "p1", captureId: "c", licenseId: "l1", customerId: "c1", patch: { stage: "QUOTED" }, summary: "x", createdAt: 1 });
    });
    const db = ownerCtx();
    await assertSucceeds(getDoc(doc(db, "users/uOwner/proposals/p1")));
    await assertSucceeds(getDocs(query(collection(db, "users/uOwner/proposals"), limit(10))));
    await assertFails(setDoc(doc(db, "users/uOwner/proposals/p2"), { id: "p2" }));
    await assertFails(updateDoc(doc(db, "users/uOwner/proposals/p1"), { summary: "changed" }));
    await assertFails(getDoc(doc(memberCtx(), "users/uOwner/proposals/p1")));
    await assertSucceeds(deleteDoc(doc(db, "users/uOwner/proposals/p1")));
  });
});

describe("settings", () => {
  const put = (data: Record<string, unknown>, who = ownerCtx(), uid = "uOwner") => setDoc(doc(who, `users/${uid}/settings/prefs`), data);
  it("stores the reminder rhythm and import column names", async () => {
    await assertSucceeds(put({ milestones: [90, 60, 30, 14], shortMilestones: [60, 30, 14, 7], shortTermMonths: 6, staleDays: 5, quietDays: 60, updatedAt: 1,
      importColumns: { customer: "Tên KH, Công ty", product: "Phần mềm", endDate: "Hết hạn" } }));
    await assertSucceeds(getDoc(doc(ownerCtx(), "users/uOwner/settings/prefs")));
    await assertSucceeds(put({}));
  });
  it("requires four strictly descending day counts", async () => {
    await assertFails(put({ milestones: [90, 60, 60, 14] }));
    await assertFails(put({ milestones: [14, 30, 60, 90] }));
    await assertFails(put({ milestones: [90, 60, 30] }));
    await assertFails(put({ milestones: [90, 60, 30, 0] }));
    await assertFails(put({ milestones: [400, 60, 30, 14] }));
    await assertFails(put({ milestones: [90, 60, 30, 1.5] }));
    await assertFails(put({ shortMilestones: "60,30,14,7" }));
    await assertSucceeds(put({ milestones: [120, 75, 45, 20] }));
  });
  it("bounds the other numbers and rejects unknown keys", async () => {
    await assertFails(put({ staleDays: 0 }));
    await assertFails(put({ staleDays: 61 }));
    await assertFails(put({ quietDays: 366 }));
    await assertFails(put({ shortTermMonths: 61 }));
    await assertFails(put({ evil: true }));
    await assertFails(put({ importColumns: { unknownField: "x" } }));
    await assertFails(put({ importColumns: { customer: "x".repeat(201) } }));
    await assertFails(put({ importColumns: { customer: 5 } }));
  });
  it("is private and only one document", async () => {
    await assertFails(getDoc(doc(memberCtx(), "users/uOwner/settings/prefs")));
    await assertFails(put({}, memberCtx(), "uOwner"));
    await assertFails(setDoc(doc(ownerCtx(), "users/uOwner/settings/other"), {}));
  });
});

describe("calendar feed tokens", () => {
  it("are server-only: no client can read, list or write them", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "feeds/abc"), { uid: "uOwner", createdAt: 1 });
    });
    await assertFails(getDoc(doc(ownerCtx(), "feeds/abc")));
    await assertFails(setDoc(doc(ownerCtx(), "feeds/zzz"), { uid: "uOwner" }));
    await assertFails(getDocs(query(collection(ownerCtx(), "feeds"), limit(5))));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "feeds/abc")));
  });
});

describe("generated config", () => {
  it("contains the throwaway test addresses only", () => {
    const rules = readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8");
    expect(rules).toContain("'owner@example.com'");
    expect(rules).not.toContain("__ALLOWED_EMAILS__");
  });
});
