import { describe, expect, it } from "vitest";
import { buildContext, CONTEXT_LIMITS } from "../src/lib/context";
import { applyProposal, moveStage, renew, snooze, stepFor, suggestRenewal } from "../src/lib/flow";
import { buildGraph, customerCascade } from "../src/lib/graph";
import type { Customer, Item, License, Proposal } from "../src/lib/model";
import { parseRoute, tabOf, to } from "../src/lib/router";

const NOW = 1_760_000_000_000;
const cus = (id: string, name: string, over: Partial<Customer> = {}): Customer => ({ id, status: "OPEN", name, createdAt: NOW, updatedAt: NOW, ...over });
const lic = (id: string, customerId: string, endDate: string, over: Partial<License> = {}): License => ({
  id, customerId, status: "OPEN", product: `SP ${id}`, kind: "LICENSE", endDate, stage: "ACTIVE", stageAt: NOW, createdAt: NOW, updatedAt: NOW, ...over,
});
const item = (id: string, over: Partial<Item> = {}): Item => ({ id, type: "TASK", status: "OPEN", title: id, details: "", allDay: false, createdAt: NOW, ...over });

describe("graph", () => {
  const g = buildGraph(
    [cus("c1", "ABC"), cus("c2", "XYZ")],
    [lic("l2", "c1", "2027-06-01"), lic("l1", "c1", "2027-01-01"), lic("l3", "c2", "2027-02-01")],
    [item("i1", { customerId: "c1" }), item("i2", { customerId: "c1", licenseId: "l1" }), item("i3", { licenseId: "l3" }), item("i4")],
  );
  it("lists a customer's licences soonest first and finds work from either end", () => {
    expect(g.licensesOf("c1").map((l) => l.id)).toEqual(["l1", "l2"]);
    expect(g.licensesOf("none")).toEqual([]);
    expect(g.itemsOfCustomer("c1").map((i) => i.id)).toEqual(["i1", "i2"]);
    expect(g.itemsOfLicense("l1").map((i) => i.id)).toEqual(["i2"]);
  });
  it("names customers, and says so when one is gone", () => {
    expect(g.customerName("c1")).toBe("ABC");
    expect(g.customerName("zzz")).toBe("Khách đã xoá");
    expect(g.customerName(undefined)).toBe("");
  });
  it("lists everything a customer delete would take", () => {
    const c = customerCascade(g, "c1");
    expect(c.licenses.map((l) => l.id).sort()).toEqual(["l1", "l2"]);
    expect(c.items.map((i) => i.id).sort()).toEqual(["i1", "i2"]);
    const d = customerCascade(g, "c2");
    expect(d.items.map((i) => i.id)).toEqual(["i3"]);
  });
});

describe("flow", () => {
  const l = lic("l1", "c1", "2026-12-31", { stage: "ASKED", snoozeUntil: "2026-11-01", lostReason: "x", value: 5_000_000, termMonths: 12, startDate: "2026-01-01" });
  it("moves a stage, restarts the clock and clears snooze", () => {
    const m = moveStage(l, "QUOTED", NOW + 5);
    expect(m).toMatchObject({ stage: "QUOTED", stageAt: NOW + 5, updatedAt: NOW + 5 });
    expect("snoozeUntil" in m).toBe(false);
    expect("lostReason" in m).toBe(false);
    expect(moveStage(l, "LOST", NOW, "Giá cao").lostReason).toBe("Giá cao");
    expect("lostReason" in moveStage(l, "LOST", NOW)).toBe(false);
  });
  it("snoozes for a number of days from today", () => {
    expect(snooze(l, "2026-10-10", 3, NOW).snoozeUntil).toBe("2026-10-13");
  });
  it("suggests and opens the next period, linked both ways", () => {
    const s = suggestRenewal(l);
    expect(s).toMatchObject({ startDate: "2027-01-01", endDate: "2027-12-31", termMonths: 12, value: 5_000_000 });
    const r = renew(l, "n1", s, NOW + 9);
    expect(r.old).toMatchObject({ stage: "RENEWED", renewedToId: "n1" });
    expect(r.next).toMatchObject({ id: "n1", customerId: "c1", stage: "ACTIVE", status: "OPEN", renewedFromId: "l1", startDate: "2027-01-01", endDate: "2027-12-31", value: 5_000_000, product: l.product });
    expect(Object.values(r.next).every((v) => v !== undefined)).toBe(true);
  });
  it("applies a proposal: stage, value, date and appended note", () => {
    const p: Proposal = { id: "p", captureId: "c", licenseId: "l1", customerId: "c1", patch: { stage: "RENEWED", value: 7_000_000, endDate: "2027-01-31", note: "Ký thứ 5" }, summary: "", createdAt: NOW };
    const out = applyProposal({ ...l, note: "Cũ" }, p, NOW + 1);
    expect(out).toMatchObject({ stage: "RENEWED", value: 7_000_000, endDate: "2027-01-31", note: "Cũ\nKý thứ 5" });
  });
});

describe("stepFor", () => {
  it("follows the reminder on screen, but never goes backwards", () => {
    const active = lic("a", "c1", "2027-01-01");
    expect(stepFor(active)).toEqual({ to: "ASKED", label: "Đã hỏi khách" });
    expect(stepFor(active, "ASK")?.to).toBe("ASKED");
    expect(stepFor(active, "QUOTE")).toEqual({ to: "QUOTED", label: "Đã gửi báo giá" });
    expect(stepFor(active, "CONTRACT")?.to).toBe("CONTRACTING");
    expect(stepFor(active, "URGENT")?.to).toBe("ASKED");
    expect(stepFor({ ...active, stage: "QUOTED" }, "ASK")?.to).toBe("CONTRACTING");
    expect(stepFor({ ...active, stage: "ASKED" }, "QUOTE")?.to).toBe("QUOTED");
  });
  it("ends at renewal, and has nothing for closed licences", () => {
    expect(stepFor(lic("a", "c1", "2027-01-01", { stage: "CONTRACTING" }))?.to).toBe("RENEWED");
    expect(stepFor(lic("a", "c1", "2027-01-01", { stage: "RENEWED" }))).toBeNull();
    expect(stepFor(lic("a", "c1", "2027-01-01", { stage: "LOST" }))).toBeNull();
  });
});

describe("buildContext", () => {
  it("sends open licences and their customers, soonest first, never closed or draft ones", () => {
    const g = buildGraph(
      [cus("c1", "ABC", { contact: "anh Nam" }), cus("c2", "XYZ"), cus("c3", "Nháp", { status: "DRAFT" })],
      [lic("a", "c1", "2027-03-01"), lic("b", "c1", "2027-01-01"), lic("c", "c2", "2026-12-01", { stage: "RENEWED" }), lic("d", "c3", "2027-01-01", { status: "DRAFT" })],
      [],
    );
    const ctx = buildContext(g);
    expect(ctx.licenses.map((l) => l.id)).toEqual(["b", "a"]);
    expect(ctx.customers.map((c) => c.id)).toEqual(["c1", "c2"]);
    expect(ctx.customers[0]).toEqual({ id: "c1", name: "ABC", contact: "anh Nam" });
    expect(ctx.focus).toBeUndefined();
  });
  it("keeps the focused licence and customer even when closed", () => {
    const g = buildGraph([cus("c1", "ABC")], [lic("c", "c1", "2026-12-01", { stage: "RENEWED" })], []);
    const ctx = buildContext(g, { licenseId: "c", customerId: "c1" });
    expect(ctx.licenses.map((l) => l.id)).toEqual(["c"]);
    expect(ctx.focus).toEqual({ customerId: "c1", licenseId: "c" });
  });
  it("stays within the limits the server accepts", () => {
    const customers = Array.from({ length: 400 }, (_, i) => cus(`c${i}`, `K${i}`));
    const licenses = Array.from({ length: 700 }, (_, i) => lic(`l${i}`, `c${i % 400}`, "2027-01-01"));
    const ctx = buildContext(buildGraph(customers, licenses, []));
    expect(ctx.customers.length).toBeLessThanOrEqual(CONTEXT_LIMITS.customers);
    expect(ctx.licenses.length).toBeLessThanOrEqual(CONTEXT_LIMITS.licenses);
    const ids = new Set(ctx.customers.map((c) => c.id));
    expect(ctx.licenses.every((l) => ids.has(l.customerId))).toBe(true);
  });
});

describe("router", () => {
  it("round-trips every screen", () => {
    expect(parseRoute(to.today)).toEqual({ name: "today" });
    expect(parseRoute(to.customers)).toEqual({ name: "customers" });
    expect(parseRoute(to.customer("abc"))).toEqual({ name: "customer", id: "abc" });
    expect(parseRoute(to.renewals)).toEqual({ name: "renewals" });
    expect(parseRoute(to.license("x_1"))).toEqual({ name: "license", id: "x_1" });
    expect(parseRoute(to.work)).toEqual({ name: "work" });
    expect(parseRoute(to.settings)).toEqual({ name: "settings" });
    expect(parseRoute(to.import)).toEqual({ name: "import" });
  });
  it("falls back to Today or the list for nonsense", () => {
    expect(parseRoute("")).toEqual({ name: "today" });
    expect(parseRoute("#/lung-tung")).toEqual({ name: "today" });
    expect(parseRoute("#/khach/a b")).toEqual({ name: "customers" });
    expect(parseRoute("#/khach/a/b/c")).toEqual({ name: "today" });
  });
  it("keeps a customer or licence under its own tab", () => {
    expect(tabOf({ name: "customer", id: "a" })).toBe("customers");
    expect(tabOf({ name: "license", id: "a" })).toBe("renewals");
    expect(tabOf({ name: "settings" })).toBeNull();
  });
});
