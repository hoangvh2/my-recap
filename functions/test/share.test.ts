import { describe, expect, it } from "vitest";
import type { Item } from "../../shared/model";
import { changeKind, changedLabels, cleanAlias, formatCode, maskEmail, normalizeCode, pairId, parentOfRepeat, repeatId, sharedFields, shareId, type Contact, type ShareDoc } from "../../shared/share";
import { MembersError, manageMembers, parseMembersRequest, type Member, type MembersStore } from "../src/members";
import {
  handleShare, hashCode, makeCode, onItemWritten, parseShareRequest, ShareError, whenText,
  type Caller, type CodeRecord, type Notice, type PushMessage, type ShareDeps, type ShareStore,
} from "../src/share/service";

// ---------------------------------------------------------------- an in-memory store with the same rules as Firestore's

class MemStore implements ShareStore {
  codes = new Map<string, CodeRecord>();
  fails = new Map<string, number[]>();
  links = new Set<string>();
  contacts = new Map<string, Map<string, Contact>>();
  items = new Map<string, Item>();
  shares = new Map<string, ShareDoc>();
  notices: { uid: string; n: Notice }[] = [];

  async putCode(hash: string, rec: CodeRecord) {
    for (const [h, r] of this.codes) if (r.ownerUid === rec.ownerUid) this.codes.delete(h);
    this.codes.set(hash, rec);
  }
  async getCode(hash: string) { return this.codes.get(hash) ?? null; }
  async failures(uid: string, since: number) { return (this.fails.get(uid) ?? []).filter((t) => t > since).length; }
  async recordFailure(uid: string, now: number) { this.fails.set(uid, [...(this.fails.get(uid) ?? []), now]); }
  async join(hash: string, joinerSees: Contact, ownerSees: Contact, now: number) {
    const rec = this.codes.get(hash);
    if (!rec || rec.expiresAt <= now) throw new ShareError("not-found", "gone");
    if (this.links.has(pairId(joinerSees.uid, ownerSees.uid))) throw new ShareError("already-exists", "linked");
    this.links.add(pairId(joinerSees.uid, ownerSees.uid));
    this.book(ownerSees.uid).set(joinerSees.uid, joinerSees);
    this.book(joinerSees.uid).set(ownerSees.uid, ownerSees);
    this.codes.delete(hash);
  }
  book(uid: string) {
    if (!this.contacts.has(uid)) this.contacts.set(uid, new Map());
    return this.contacts.get(uid)!;
  }
  async contact(uid: string, other: string) { return this.book(uid).get(other) ?? null; }
  async contactIds(uid: string) { return [...this.book(uid).keys()]; }
  async revoke(owner: string, reader: string) {
    let n = 0;
    for (const [id, d] of this.shares) {
      if (d.ownerUid !== owner || !d.audience.includes(reader)) continue;
      n++;
      const left = d.audience.filter((u) => u !== reader);
      if (left.length === 0) this.shares.delete(id);
      else this.shares.set(id, { ...d, audience: left });
    }
    return n;
  }
  async unlink(a: string, b: string) {
    this.links.delete(pairId(a, b));
    this.book(a).delete(b);
    this.book(b).delete(a);
  }
  async item(uid: string, id: string) { return this.items.get(`${uid}/${id}`) ?? null; }
  async share(id: string) { return this.shares.get(id) ?? null; }
  async putShare(d: ShareDoc) { this.shares.set(d.id, d); }
  async deleteShare(id: string) { this.shares.delete(id); }
  async addNotice(uid: string, n: Notice) { this.notices.push({ uid, n }); }
}

const T0 = Date.UTC(2026, 9, 13, 5, 0); // 12:00 in Hà Nội
function setup() {
  const store = new MemStore();
  const pushes: { uid: string; m: PushMessage }[] = [];
  let now = T0;
  let seq = 0;
  const deps: ShareDeps = {
    store,
    pusher: { send: async (uid, m) => void pushes.push({ uid, m }) },
    now: () => now,
    randomInt: (n) => (seq++ * 7) % n,
    appUrl: "https://desk.example.com",
  };
  return { store, pushes, deps, tick: (ms: number) => (now += ms) };
}

const an: Caller = { uid: "uAn", email: "an.vu@gmail.com", name: "Vũ Huy An" };
const lan: Caller = { uid: "uLan", email: "lan.nguyen@gmail.com", name: "Nguyễn Thị Lan" };
const tuan: Caller = { uid: "uTuan", email: "tuan@gmail.com", name: "" };

const item = (over: Partial<Item> = {}): Item => ({ id: "e1", type: "EVENT", status: "OPEN", title: "Nhậu hội lớp", details: "Về trễ", allDay: false, whenAt: Date.UTC(2026, 9, 17, 12, 0), createdAt: T0, place: "Quán Gió", ...over });

async function link(deps: ShareDeps, a: Caller, b: Caller, alias = "") {
  const { code } = (await handleShare(deps, a, { action: "linkCreate" })) as { code: string };
  return handleShare(deps, b, { action: "linkJoin", code, alias });
}

// ---------------------------------------------------------------- tests

describe("codes and small helpers", () => {
  it("makes 8-character codes from an alphabet without look-alikes, and reads them back loosely", () => {
    const code = makeCode((n) => n - 1);
    expect(code).toHaveLength(8);
    expect(code).not.toMatch(/[IO01]/);
    expect(normalizeCode(" k7m4-qx9d ")).toBe("K7M4QX9D");
    expect(normalizeCode("K7M4QX9")).toBeNull();
    expect(normalizeCode("K7M4QX9O")).toBeNull();
    expect(formatCode("K7M4QX9D")).toBe("K7M4 QX9D");
    expect(hashCode("K7M4QX9D")).not.toContain("K7M4");
  });
  it("masks addresses, cleans aliases, finds pair ids and repeat parents", () => {
    expect(maskEmail("lan.nguyen@gmail.com")).toBe("lan•••@gmail.com");
    expect(maskEmail("ab@x.vn")).toBe("a•••@x.vn");
    expect(cleanAlias("  Em\n  Lan  ")).toBe("Em Lan");
    expect(cleanAlias("x".repeat(60))).toHaveLength(40);
    expect(pairId("b", "a")).toBe("a_b");
    expect(parentOfRepeat("t1_n1760000000000")).toBe("t1");
    expect(parentOfRepeat("t1_n1760000000000_n1760086400000")).toBe("t1");
    expect(parentOfRepeat("t1")).toBeNull();
    expect(repeatId("t1", 5)).toBe("t1_n5");
    expect(repeatId("t1_n5_n6", 7)).toBe("t1_n7");
    expect(parentOfRepeat("t1")).toBeNull();
  });
  it("shows a recipient only what is safe: never customer, licence, money, people, quotes or sources", () => {
    const f = sharedFields(item({ customerId: "c1", licenseId: "l1", person: "anh Nam", quote: "nói gì đó", sourceId: "s1", amount: 5 } as Partial<Item>));
    expect(Object.keys(f).sort()).toEqual(["allDay", "createdAt", "details", "place", "status", "title", "type", "whenAt"]);
  });
  it("tells a real edit from a tick or an automatic roll-forward", () => {
    const a = sharedFields(item());
    expect(changeKind(null, a, T0)).toBe("new");
    expect(changeKind(a, null, T0)).toBe("deleted");
    expect(changeKind(a, { ...a, status: "DONE" }, T0)).toBeNull();
    expect(changeKind(a, { ...a, whenAt: a.whenAt! - 3600_000 }, T0)).toBe("edited");
    const rep = { ...a, recurrence: "WEEKLY" as const, whenAt: T0 - 1000 };
    expect(changeKind(rep, { ...rep, whenAt: T0 + 7 * 86_400_000 }, T0)).toBeNull();
    expect(changedLabels(a, { ...a, whenAt: 1, place: "Khác" })).toEqual(["thời gian", "địa điểm"]);
  });
  it("writes times in Vietnamese", () => {
    expect(whenText({ whenAt: Date.UTC(2026, 9, 17, 11, 0), allDay: false })).toBe("Thứ Bảy 17/10 · 18:00");
    expect(whenText({ whenAt: Date.UTC(2026, 9, 17, 11, 0), allDay: true })).toBe("Cả ngày Thứ Bảy 17/10");
    expect(whenText({ allDay: false })).toBe("");
  });
});

describe("requests", () => {
  it("accepts the five actions and refuses anything else", () => {
    expect(parseShareRequest({ action: "linkCreate" })).toEqual({ action: "linkCreate" });
    expect(parseShareRequest({ action: "linkJoin", code: "k7m4 qx9d", alias: " Em Lan " })).toEqual({ action: "linkJoin", code: "K7M4QX9D", alias: "Em Lan" });
    expect(parseShareRequest({ action: "shareSet", itemId: "e1", audience: ["a", "a", "b"] })).toEqual({ action: "shareSet", itemId: "e1", audience: ["a", "b"] });
    for (const bad of [null, [], {}, { action: "nope" }, { action: "linkCreate", x: 1 }, { action: "linkPreview", code: "short" },
      { action: "shareSet", itemId: "../x", audience: [] }, { action: "shareSet", itemId: "e1", audience: Array.from({ length: 51 }, (_, i) => `u${i}`) },
      { action: "linkRemove", uid: "a/b" }]) {
      expect(() => parseShareRequest(bad)).toThrow(ShareError);
    }
  });
});

describe("linking", () => {
  it("links two people with a one-time code: preview shows who made it, both get a contact, the maker is told", async () => {
    const { deps, store, pushes } = setup();
    const { code, expiresAt } = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string; expiresAt: number };
    expect(expiresAt).toBe(T0 + 10 * 60_000);
    expect(store.codes.size).toBe(1);
    expect([...store.codes.keys()][0]).toBe(hashCode(code)); // only the hash is stored

    expect(await handleShare(deps, lan, { action: "linkPreview", code })).toEqual({ name: "Vũ Huy An", maskedEmail: "an.•••@gmail.com", already: false });
    const r = (await handleShare(deps, lan, { action: "linkJoin", code, alias: "Anh An" })) as { contact: Contact };
    expect(r.contact).toMatchObject({ uid: "uAn", alias: "Anh An", googleName: "Vũ Huy An" });
    expect(await store.contact("uLan", "uAn")).toMatchObject({ alias: "Anh An", notify: true });
    expect(await store.contact("uAn", "uLan")).toMatchObject({ alias: "Nguyễn Thị Lan", maskedEmail: "lan•••@gmail.com" });
    expect(store.codes.size).toBe(0);
    expect(store.notices).toEqual([{ uid: "uAn", n: expect.objectContaining({ kind: "linked", fromName: "Nguyễn Thị Lan" }) }]);
    expect(pushes).toEqual([{ uid: "uAn", m: expect.objectContaining({ kind: "linked", title: "Nguyễn Thị Lan đã liên kết với bạn" }) }]);

    // Used once: the same code does not work again.
    await expect(handleShare(deps, tuan, { action: "linkJoin", code, alias: "" })).rejects.toMatchObject({ code: "not-found" });
  });

  it("uses the Google name when no alias is given, and the address when Google has no name", async () => {
    const { deps, store } = setup();
    await link(deps, tuan, lan);
    expect(await store.contact("uLan", "uTuan")).toMatchObject({ alias: "tuan" });
  });

  it("refuses an expired code, your own code, a second link, and a new code replaces the old one", async () => {
    const { deps, tick } = setup();
    const first = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string };
    const second = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string };
    await expect(handleShare(deps, lan, { action: "linkPreview", code: first.code })).rejects.toMatchObject({ code: "not-found" });
    await expect(handleShare(deps, an, { action: "linkPreview", code: second.code })).rejects.toMatchObject({ code: "failed-precondition" });
    tick(10 * 60_000);
    await expect(handleShare(deps, lan, { action: "linkJoin", code: second.code, alias: "" })).rejects.toMatchObject({ code: "not-found" });
    const third = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string };
    await handleShare(deps, lan, { action: "linkJoin", code: third.code, alias: "" });
    const fourth = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string };
    expect(await handleShare(deps, lan, { action: "linkPreview", code: fourth.code })).toMatchObject({ already: true });
    await expect(handleShare(deps, lan, { action: "linkJoin", code: fourth.code, alias: "" })).rejects.toMatchObject({ code: "already-exists" });
  });

  it("locks out a person who guesses codes", async () => {
    const { deps, tick } = setup();
    for (let i = 0; i < 5; i++) await expect(handleShare(deps, lan, { action: "linkPreview", code: "AAAAAAAA" })).rejects.toMatchObject({ code: "not-found" });
    const { code } = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string };
    await expect(handleShare(deps, lan, { action: "linkPreview", code })).rejects.toMatchObject({ code: "resource-exhausted" });
    tick(3600_001);
    const fresh = (await handleShare(deps, an, { action: "linkCreate" })) as { code: string };
    await expect(handleShare(deps, lan, { action: "linkPreview", code: fresh.code })).resolves.toMatchObject({ name: "Vũ Huy An" });
  });

  it("unlinks from either side without asking the other: shares both ways disappear, the other is told", async () => {
    const { deps, store, pushes } = setup();
    await link(deps, an, lan, "Anh An");
    await link(deps, an, tuan);
    store.items.set("uAn/e1", item());
    store.items.set("uLan/n1", item({ id: "n1", type: "NOTE", title: "Đơn thuốc bé Na" }));
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan", "uTuan"] });
    await handleShare(deps, lan, { action: "shareSet", itemId: "n1", audience: ["uAn"] });
    pushes.length = 0;

    await handleShare(deps, lan, { action: "linkRemove", uid: "uAn" });
    expect(store.shares.get(shareId("uAn", "e1"))!.audience).toEqual(["uTuan"]);
    expect(store.shares.has(shareId("uLan", "n1"))).toBe(false);
    expect(await store.contact("uAn", "uLan")).toBeNull();
    expect(await store.contact("uLan", "uAn")).toBeNull();
    expect(store.links.has(pairId("uAn", "uLan"))).toBe(false);
    // An calls Lan "Nguyễn Thị Lan" (the default), so that is the name in An's push.
    expect(pushes).toEqual([{ uid: "uAn", m: expect.objectContaining({ kind: "unlinked", title: "Nguyễn Thị Lan đã huỷ liên kết với bạn" }) }]);
    expect(store.notices.at(-1)).toMatchObject({ uid: "uAn", n: { kind: "unlinked" } });
    // Pressing again is harmless.
    await expect(handleShare(deps, lan, { action: "linkRemove", uid: "uAn" })).resolves.toEqual({ removed: true });
  });
});

describe("sharing one item", () => {
  async function linked() {
    const s = setup();
    await link(s.deps, an, lan, "Anh An");
    await link(s.deps, an, tuan, "An");
    s.store.items.set("uAn/e1", item());
    s.pushes.length = 0;
    return s;
  }

  it("shares with the chosen contacts only, and pushes to the newly added ones", async () => {
    const { deps, store, pushes } = await linked();
    expect(await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan"] })).toEqual({ audience: ["uLan"] });
    const doc = store.shares.get("uAn_e1")!;
    expect(doc).toMatchObject({ ownerUid: "uAn", ownerName: "Vũ Huy An", itemId: "e1", audience: ["uLan"], title: "Nhậu hội lớp", place: "Quán Gió" });
    expect(pushes).toEqual([{ uid: "uLan", m: expect.objectContaining({ kind: "new", title: "Anh An chia sẻ: Nhậu hội lớp", body: "Thứ Bảy 17/10 · 19:00 · Quán Gió", url: "https://desk.example.com/#/chia-se/uAn_e1" }) }]);
    pushes.length = 0;
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan", "uTuan"] });
    expect(pushes.map((p) => p.uid)).toEqual(["uTuan"]);
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: [] });
    expect(store.shares.has("uAn_e1")).toBe(false);
  });

  it("refuses strangers, expenses, drafts and missing items", async () => {
    const { deps, store } = await linked();
    await expect(handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uStranger"] })).rejects.toMatchObject({ code: "permission-denied" });
    store.items.set("uAn/x1", item({ id: "x1", type: "EXPENSE", amount: 50_000 }));
    await expect(handleShare(deps, an, { action: "shareSet", itemId: "x1", audience: ["uLan"] })).rejects.toMatchObject({ code: "failed-precondition" });
    store.items.set("uAn/d1", item({ id: "d1", status: "DRAFT" }));
    await expect(handleShare(deps, an, { action: "shareSet", itemId: "d1", audience: ["uLan"] })).rejects.toMatchObject({ code: "failed-precondition" });
    await expect(handleShare(deps, an, { action: "shareSet", itemId: "nope", audience: ["uLan"] })).rejects.toMatchObject({ code: "not-found" });
    // Lan cannot share An's item.
    await expect(handleShare(deps, lan, { action: "shareSet", itemId: "e1", audience: ["uAn"] })).rejects.toMatchObject({ code: "not-found" });
  });

  it("an edit of a shared item reaches every recipient: 19:00 moved to 18:00 tells the whole group", async () => {
    const { deps, store, pushes, tick } = await linked();
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan", "uTuan"] });
    pushes.length = 0;
    tick(60_000);
    const moved = item({ whenAt: Date.UTC(2026, 9, 17, 11, 0) });
    store.items.set("uAn/e1", moved);
    expect(await onItemWritten(deps, "uAn", "e1", moved)).toBe("updated");
    expect(store.shares.get("uAn_e1")).toMatchObject({ whenAt: moved.whenAt, updatedAt: T0 + 60_000 });
    expect(pushes.map((p) => [p.uid, p.m.title, p.m.body])).toEqual([
      ["uLan", "Anh An đã sửa: Nhậu hội lớp", "Đổi thời gian · Thứ Bảy 17/10 · 18:00 · Quán Gió"],
      ["uTuan", "An đã sửa: Nhậu hội lớp", "Đổi thời gian · Thứ Bảy 17/10 · 18:00 · Quán Gió"],
    ]);
  });

  it("a tick updates the view quietly; a recipient who muted the owner gets nothing", async () => {
    const { deps, store, pushes } = await linked();
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan", "uTuan"] });
    pushes.length = 0;
    const done = item({ status: "DONE", doneAt: T0 });
    expect(await onItemWritten(deps, "uAn", "e1", done)).toBe("updated");
    expect(store.shares.get("uAn_e1")).toMatchObject({ status: "DONE", doneAt: T0 });
    expect(pushes).toEqual([]);
    store.book("uTuan").set("uAn", { ...store.book("uTuan").get("uAn")!, notify: false });
    await onItemWritten(deps, "uAn", "e1", { ...done, title: "Nhậu hội lớp (đổi quán)" });
    expect(pushes.map((p) => p.uid)).toEqual(["uLan"]);
    // Writing the same thing again changes nothing and tells nobody.
    pushes.length = 0;
    expect(await onItemWritten(deps, "uAn", "e1", { ...done, title: "Nhậu hội lớp (đổi quán)" })).toBe("none");
    expect(pushes).toEqual([]);
  });

  it("deleting the original removes the view and tells recipients; turning it into an expense takes it down quietly", async () => {
    const { deps, store, pushes } = await linked();
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan"] });
    pushes.length = 0;
    expect(await onItemWritten(deps, "uAn", "e1", null)).toBe("deleted");
    expect(store.shares.has("uAn_e1")).toBe(false);
    expect(pushes).toEqual([{ uid: "uLan", m: expect.objectContaining({ kind: "deleted", title: "Anh An đã xoá: Nhậu hội lớp" }) }]);

    store.items.set("uAn/e1", item());
    await handleShare(deps, an, { action: "shareSet", itemId: "e1", audience: ["uLan"] });
    pushes.length = 0;
    expect(await onItemWritten(deps, "uAn", "e1", item({ type: "EXPENSE", amount: 1 }))).toBe("deleted");
    expect(pushes).toEqual([]);
  });

  it("the next occurrence of a repeating task keeps the audience (minus anyone unlinked since)", async () => {
    const { deps, store } = await linked();
    const t = item({ id: "t1", type: "TASK", recurrence: "WEEKLY" });
    store.items.set("uAn/t1", t);
    await handleShare(deps, an, { action: "shareSet", itemId: "t1", audience: ["uLan", "uTuan"] });
    await handleShare(deps, tuan, { action: "linkRemove", uid: "uAn" });
    const next = { ...t, id: "t1_n1761000000000", whenAt: 1_761_000_000_000 };
    expect(await onItemWritten(deps, "uAn", next.id, next)).toBe("created");
    expect(store.shares.get(`uAn_${next.id}`)).toMatchObject({ itemId: next.id, audience: ["uLan"], whenAt: 1_761_000_000_000 });
    // An unshared item's next occurrence stays private.
    expect(await onItemWritten(deps, "uAn", "x_n1", { ...t, id: "x_n1" })).toBe("none");
  });
});

describe("members (who may use the app)", () => {
  class MemMembers implements MembersStore {
    m = new Map<string, Member>();
    async has(e: string) { return this.m.has(e); }
    async list() { return [...this.m.values()]; }
    async add(x: Member) { this.m.set(x.email, x); }
    async remove(e: string) { this.m.delete(e); }
  }
  const admins = ["an.vu@gmail.com"];

  it("lets an administrator add and remove people; others only learn they are not one", async () => {
    const s = new MemMembers();
    const r = await manageMembers(s, "AN.VU@gmail.com", admins, parseMembersRequest({ action: "add", email: " Lan.Nguyen@Gmail.com " }), 5);
    expect(r).toEqual({ admin: true, admins, members: [{ email: "lan.nguyen@gmail.com", addedBy: "an.vu@gmail.com", addedAt: 5 }] });
    expect(await manageMembers(s, "lan.nguyen@gmail.com", admins, { action: "list" }, 6)).toEqual({ admin: false, admins: [], members: [] });
    await expect(manageMembers(s, "lan.nguyen@gmail.com", admins, { action: "add", email: "x@y.vn" }, 6)).rejects.toBeInstanceOf(MembersError);
    await expect(manageMembers(s, "an.vu@gmail.com", admins, { action: "add", email: "an.vu@gmail.com" }, 6)).rejects.toBeInstanceOf(MembersError);
    expect((await manageMembers(s, "an.vu@gmail.com", admins, { action: "remove", email: "lan.nguyen@gmail.com" }, 7)).members).toEqual([]);
    expect(() => parseMembersRequest({ action: "add", email: "not an email" })).toThrow(MembersError);
  });
});
