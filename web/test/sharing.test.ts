import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { sharedWithCounts, sharers, withReceived } from "../src/lib/sharing";
import { agenda, overview } from "../src/lib/views";
import { asItem, ownerLabel } from "../src/lib/sharing";
import type { Contact, ShareDoc } from "../../shared/share";

const day = (h: number, d = 0) => { const t = new Date(); t.setHours(h, 0, 0, 0); t.setDate(t.getDate() + d); return t.getTime(); };
const NOW = day(14);
const mine = (id: string, over: Partial<Item> = {}): Item => ({ id, type: "TASK", status: "OPEN", title: id, details: "", allDay: false, createdAt: 1, ...over });
const share = (id: string, owner: string, over: Partial<ShareDoc> = {}): ShareDoc => ({
  id: `${owner}_${id}`, ownerUid: owner, ownerName: `G ${owner}`, itemId: id, audience: ["me"], type: "EVENT", status: "OPEN",
  title: id, details: "", allDay: false, createdAt: 1, updatedAt: 2, ...over,
});

describe("what others share with me, next to my own items", () => {
  const rec = [share("e1", "uLan", { whenAt: day(20), place: "Zoom" }), share("t1", "uTuan", { type: "TASK", whenAt: day(9) }), share("t2", "uTuan", { type: "TASK", whenAt: day(9, -2) })];
  it("filters by whose, and by one person", () => {
    const m = [mine("a")];
    expect(withReceived(m, rec, { who: "all" }).map((i) => i.id)).toEqual(["a", "uLan_e1", "uTuan_t1", "uTuan_t2"]);
    expect(withReceived(m, rec, { who: "mine" }).map((i) => i.id)).toEqual(["a"]);
    expect(withReceived(m, rec, { who: "shared", person: "uLan" }).map((i) => i.id)).toEqual(["uLan_e1"]);
    expect(sharers(rec)).toEqual([{ uid: "uTuan", count: 2 }, { uid: "uLan", count: 1 }]);
  });
  it("never counts someone else's item as overdue for me: today's stays in today, older ones drop off", () => {
    const all = withReceived([mine("late", { whenAt: day(9, -1) })], rec, { who: "all" });
    const isRec = (i: Item) => i.id.includes("_");
    const o = overview(all, NOW, isRec);
    expect(o.overdue.map((i) => i.id)).toEqual(["late"]);
    expect(o.today.map((i) => i.id).sort()).toEqual(["uLan_e1", "uTuan_t1"]);
    const a = agenda(all, NOW, isRec);
    expect(a.overdue.map((i) => i.id)).toEqual(["late"]);
    expect(a.ahead.map((i) => i.id)).toEqual(["uTuan_t1", "uLan_e1"]);
  });
  it("draws a shared view as an item with only the shared fields, and names its owner by my alias", () => {
    expect(asItem(rec[0])).toEqual({ id: "uLan_e1", type: "EVENT", status: "OPEN", title: "e1", details: "", allDay: false, createdAt: 1, whenAt: day(20), place: "Zoom" });
    const c: Contact = { uid: "uLan", alias: "Em Lan", googleName: "G uLan", maskedEmail: "", linkedAt: 1, notify: true };
    expect(ownerLabel(rec[0], [c])).toBe("Em Lan");
    expect(ownerLabel(rec[1], [c])).toBe("G uTuan");
  });
  it("counts how many of my items each contact sees", () => {
    expect([...sharedWithCounts(new Map([["i1", ["a", "b"]], ["i2", ["a"]]]))]).toEqual([["a", 2], ["b", 1]]);
  });
});
