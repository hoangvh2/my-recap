import { describe, expect, it } from "vitest";
import { isoDateIn } from "../../shared/dates";
import { DEFAULT_PREFS, normalizePrefs, validMilestones, type Customer, type Item, type License } from "../../shared/model";
import {
  calendarMilestones, daysLeftText, dueNudges, lastTouchOf, monthlyForecast, nextExpiryOf, nextMilestone, nextPeriod, nudgeFor, offsetsFor,
  openValueOf, quietCustomers, upcomingNudges, urgencyOf, type Ctx,
} from "../../shared/renewals";

const ms = (iso: string) => Date.parse(`${iso}T12:00:00Z`);
const ctx = (today: string, prefs = DEFAULT_PREFS): Ctx => ({ today, prefs, zone: "UTC" });
const lic = (over: Partial<License> = {}): License => ({
  id: "l1", customerId: "c1", status: "OPEN", product: "Kế toán", kind: "LICENSE", endDate: "2027-03-15",
  stage: "ACTIVE", stageAt: ms("2026-06-01"), createdAt: 1, updatedAt: 1, ...over,
});
const cust = (over: Partial<Customer> = {}): Customer => ({ id: "c1", status: "OPEN", name: "Công ty ABC", createdAt: 1, updatedAt: 1, ...over });
// With end date 2027-03-15 and 90/60/30/14: ASK 2026-12-15, QUOTE 2027-01-14, CONTRACT 2027-02-13, URGENT 2027-03-01.

describe("nudgeFor: the 90/60/30/14 playbook", () => {
  it("is quiet until the first milestone, then asks the customer", () => {
    expect(nudgeFor(lic(), ctx("2026-12-14"))).toBeNull();
    const n = nudgeFor(lic(), ctx("2026-12-15"))!;
    expect(n).toMatchObject({ kind: "ASK", severity: "info", dueDate: "2026-12-15", overdueDays: 0, daysToEnd: 90 });
    expect(nudgeFor(lic(), ctx("2026-12-20"))).toMatchObject({ kind: "ASK", overdueDays: 5 });
  });
  it("moves on to the quote, the contract and the red alert as the date nears", () => {
    expect(nudgeFor(lic(), ctx("2027-01-14"))).toMatchObject({ kind: "QUOTE", severity: "warn" });
    expect(nudgeFor(lic(), ctx("2027-02-13"))).toMatchObject({ kind: "CONTRACT", severity: "warn" });
    expect(nudgeFor(lic(), ctx("2027-03-01"))).toMatchObject({ kind: "URGENT", severity: "danger", daysToEnd: 14 });
  });
  it("turns into 'expired and not closed' after the end date", () => {
    expect(nudgeFor(lic(), ctx("2027-03-15"))).toMatchObject({ kind: "URGENT" });
    expect(nudgeFor(lic(), ctx("2027-03-18"))).toMatchObject({ kind: "EXPIRED", severity: "danger", overdueDays: 3, daysToEnd: -3 });
  });
  it("skips steps the stage has already passed", () => {
    // asked: no more "ask"; the next step is the quote
    expect(nudgeFor(lic({ stage: "ASKED", stageAt: ms("2027-01-12") }), ctx("2027-01-13"))).toBeNull();
    expect(nudgeFor(lic({ stage: "ASKED", stageAt: ms("2027-01-12") }), ctx("2027-01-14"))).toMatchObject({ kind: "QUOTE" });
    // quoted: past the quote; the contract is next
    expect(nudgeFor(lic({ stage: "QUOTED", stageAt: ms("2027-02-12") }), ctx("2027-02-13"))).toMatchObject({ kind: "CONTRACT" });
    // contracting: only the red alert and expiry remain
    expect(nudgeFor(lic({ stage: "CONTRACTING", stageAt: ms("2027-02-20") }), ctx("2027-02-25"))).toBeNull();
    expect(nudgeFor(lic({ stage: "CONTRACTING", stageAt: ms("2027-02-20") }), ctx("2027-03-02"))).toMatchObject({ kind: "URGENT" });
  });
  it("shows the most advanced step when several are overdue", () => {
    expect(nudgeFor(lic(), ctx("2027-02-20"))).toMatchObject({ kind: "CONTRACT" });
  });
  it("is silent for finished, draft and snoozed licences", () => {
    expect(nudgeFor(lic({ stage: "RENEWED" }), ctx("2027-03-20"))).toBeNull();
    expect(nudgeFor(lic({ stage: "LOST" }), ctx("2027-03-20"))).toBeNull();
    expect(nudgeFor(lic({ status: "DRAFT" }), ctx("2027-03-20"))).toBeNull();
    expect(nudgeFor(lic({ snoozeUntil: "2027-01-20" }), ctx("2027-01-15"))).toBeNull();
    expect(nudgeFor(lic({ snoozeUntil: "2027-01-20" }), ctx("2027-01-20"))).toMatchObject({ kind: "QUOTE" });
  });
  it("counts back from the notice deadline when the contract needs notice", () => {
    const l = lic({ noticeDays: 30 }); // deadline 2027-02-13, ASK 2026-11-15
    expect(nudgeFor(l, ctx("2026-11-14"))).toBeNull();
    expect(nudgeFor(l, ctx("2026-11-15"))).toMatchObject({ kind: "ASK", daysToEnd: 120 });
  });
  it("uses the shorter rhythm for terms of six months or less", () => {
    const l = lic({ termMonths: 6 });
    expect(offsetsFor(l, DEFAULT_PREFS)).toEqual([60, 30, 14, 7]);
    expect(nudgeFor(l, ctx("2027-01-13"))).toBeNull();
    expect(nudgeFor(l, ctx("2027-01-14"))).toMatchObject({ kind: "ASK" }); // 60 days before
    expect(offsetsFor(lic({ termMonths: 12 }), DEFAULT_PREFS)).toEqual([90, 60, 30, 14]);
    // the term can also come from the dates
    expect(offsetsFor(lic({ startDate: "2026-09-16" }), DEFAULT_PREFS)).toEqual([60, 30, 14, 7]);
  });
  it("follows the owner's own milestones", () => {
    const prefs = normalizePrefs({ milestones: [120, 75, 45, 20] });
    expect(nudgeFor(lic(), ctx("2026-11-15", prefs))).toMatchObject({ kind: "ASK", daysToEnd: 120 });
  });
  it("reminds to follow up when asked or quoted and nothing has happened", () => {
    const asked = lic({ stage: "ASKED", stageAt: ms("2026-12-16") });
    expect(nudgeFor(asked, ctx("2026-12-20"))).toBeNull();
    expect(nudgeFor(asked, ctx("2026-12-21"))).toMatchObject({ kind: "FOLLOW_UP", dueDate: "2026-12-21", severity: "warn" });
    expect(nudgeFor(lic({ stage: "QUOTED", stageAt: ms("2026-12-16") }), ctx("2026-12-23"))).toMatchObject({ kind: "FOLLOW_UP", overdueDays: 2 });
    // but not once contracting, and a milestone wins over it
    expect(nudgeFor(lic({ stage: "CONTRACTING", stageAt: ms("2026-12-16") }), ctx("2026-12-30"))).toBeNull();
    expect(nudgeFor(lic({ stage: "ASKED", stageAt: ms("2026-12-16") }), ctx("2027-01-20"))).toMatchObject({ kind: "QUOTE" });
  });
});

describe("what comes next, and the lists", () => {
  it("finds the next milestone ahead for the current stage", () => {
    expect(nextMilestone(lic(), ctx("2026-10-10"))).toMatchObject({ kind: "ASK", date: "2026-12-15" });
    expect(nextMilestone(lic({ stage: "QUOTED" }), ctx("2026-10-10"))).toMatchObject({ kind: "CONTRACT", date: "2027-02-13" });
    expect(nextMilestone(lic({ stage: "RENEWED" }), ctx("2026-10-10"))).toBeNull();
    expect(nextMilestone(lic({ stage: "CONTRACTING" }), ctx("2027-03-05"))).toBeNull();
  });
  it("lists every calendar milestone still ahead, plus the end date", () => {
    const m = calendarMilestones(lic(), ctx("2027-01-01"));
    expect(m.map((x) => [x.kind, x.date])).toEqual([["QUOTE", "2027-01-14"], ["CONTRACT", "2027-02-13"], ["URGENT", "2027-03-01"], ["END", "2027-03-15"]]);
    expect(calendarMilestones(lic({ stage: "RENEWED" }), ctx("2027-01-01"))).toEqual([]);
  });
  it("sorts due nudges most urgent first, and previews the coming ones", () => {
    const list = [
      lic({ id: "a", endDate: "2027-03-15" }), // ASK long overdue (info)
      lic({ id: "b", endDate: "2026-12-20" }), // expired (danger)
      lic({ id: "c", endDate: "2027-01-30", stage: "QUOTED", stageAt: ms("2026-12-30") }), // CONTRACT overdue (warn)
      lic({ id: "d", endDate: "2027-06-01" }), // nothing yet
    ];
    const c = ctx("2027-01-05");
    expect(dueNudges(list, c).map((n) => `${n.licenseId}:${n.kind}`)).toEqual(["b:EXPIRED", "c:CONTRACT", "a:ASK"]);
    const up = upcomingNudges(list, c, 14);
    expect(up.map((n) => n.licenseId)).toEqual([]);
    expect(upcomingNudges([lic({ id: "d", endDate: "2027-04-01" })], ctx("2026-12-30"), 14).map((n) => `${n.kind}:${n.dueDate}`)).toEqual(["ASK:2027-01-01"]);
  });
  it("colours a licence by how worried to be", () => {
    expect(urgencyOf(lic(), ctx("2026-10-10"))).toBe("calm");
    expect(urgencyOf(lic(), ctx("2026-12-20"))).toBe("info");
    expect(urgencyOf(lic(), ctx("2027-03-05"))).toBe("danger");
    expect(urgencyOf(lic({ stage: "RENEWED" }), ctx("2027-03-05"))).toBe("done");
    expect(urgencyOf(lic({ snoozeUntil: "2027-12-01" }), ctx("2027-03-05"))).toBe("danger"); // snooze hides reminders, not the colour
  });
  it("words the time left", () => {
    expect(daysLeftText(12)).toBe("Còn 12 ngày");
    expect(daysLeftText(90)).toBe("Còn 3 tháng");
    expect(daysLeftText(0)).toBe("Hết hạn hôm nay");
    expect(daysLeftText(-5)).toBe("Quá hạn 5 ngày");
  });
});
describe("renewing", () => {
  it("builds the next period from the last one", () => {
    expect(nextPeriod({ endDate: "2027-03-15", termMonths: 12 })).toEqual({ startDate: "2027-03-16", endDate: "2028-03-15", termMonths: 12 });
    expect(nextPeriod({ endDate: "2027-03-15", startDate: "2026-09-16" })).toEqual({ startDate: "2027-03-16", endDate: "2027-09-15", termMonths: 6 });
    expect(nextPeriod({ endDate: "2027-03-15" })).toEqual({ startDate: "2027-03-16", endDate: "2028-03-15", termMonths: 12 });
    expect(nextPeriod({ endDate: "2027-01-31", termMonths: 1 }).endDate).toBe("2027-02-28");
  });
});

describe("overview numbers", () => {
  const list = [
    lic({ id: "a", endDate: "2027-03-10", value: 100_000_000 }),
    lic({ id: "b", endDate: "2027-03-20", value: 50_000_000, stage: "RENEWED" }),
    lic({ id: "c", endDate: "2027-03-25", value: 20_000_000, stage: "LOST" }),
    lic({ id: "d", endDate: "2027-04-02", value: 7_000_000 }),
    lic({ id: "e", endDate: "2027-03-30", value: 9_000_000, status: "DRAFT" }),
  ];
  it("summarises each month's expiries", () => {
    const [mar, apr] = monthlyForecast(list, "2027-03", 2);
    expect(mar).toEqual({ month: "2027-03", open: { count: 1, value: 100_000_000 }, renewed: { count: 1, value: 50_000_000 }, lost: { count: 1, value: 20_000_000 } });
    expect(apr.open).toEqual({ count: 1, value: 7_000_000 });
  });
  it("totals what is still open and finds the soonest expiry", () => {
    expect(openValueOf(list)).toBe(107_000_000);
    expect(nextExpiryOf(list)).toBe("2027-03-10");
    expect(nextExpiryOf([lic({ stage: "LOST" })])).toBeNull();
  });
  it("finds customers nobody has touched for a long time", () => {
    const now = ms("2027-01-01");
    const day = 86_400_000;
    const customers = [cust({ id: "c1", name: "Quiet", createdAt: now - 200 * day, updatedAt: now - 200 * day }), cust({ id: "c2", name: "Busy", createdAt: now - 200 * day, updatedAt: now - 200 * day }), cust({ id: "c3", name: "No licence", createdAt: 1, updatedAt: 1 })];
    const licenses = [
      lic({ id: "x", customerId: "c1", stageAt: now - 100 * day, updatedAt: now - 100 * day, value: 5 }),
      lic({ id: "y", customerId: "c2", stageAt: now - 100 * day, updatedAt: now - 100 * day, value: 9 }),
    ];
    const items: Item[] = [{ id: "i", type: "NOTE", status: "OPEN", title: "n", details: "", allDay: false, createdAt: now - 3 * day, customerId: "c2" }];
    const q = quietCustomers(customers, licenses, items, now, 60);
    expect(q.map((x) => x.customer.name)).toEqual(["Quiet"]);
    expect(q[0].daysQuiet).toBe(100);
    expect(lastTouchOf(customers[1], licenses.filter((l) => l.customerId === "c2"), items)).toBe(now - 3 * day);
  });
});

describe("settings", () => {
  it("accepts four descending day counts only", () => {
    expect(validMilestones([90, 60, 30, 14])).toBe(true);
    expect(validMilestones([90, 60, 60, 14])).toBe(false);
    expect(validMilestones([14, 30, 60, 90])).toBe(false);
    expect(validMilestones([90, 60, 30])).toBe(false);
    expect(validMilestones([90, 60, 30, 0])).toBe(false);
    expect(validMilestones([90, 60, 30, 1.5])).toBe(false);
    expect(validMilestones([400, 60, 30, 14])).toBe(false);
  });
  it("falls back to the defaults for anything missing or invalid", () => {
    expect(normalizePrefs(null)).toEqual(DEFAULT_PREFS);
    expect(normalizePrefs({ milestones: [1, 2, 3, 4], staleDays: -3, quietDays: 90 }).milestones).toEqual([90, 60, 30, 14]);
    expect(normalizePrefs({ staleDays: -3 }).staleDays).toBe(5);
    expect(normalizePrefs({ quietDays: 90 }).quietDays).toBe(90);
    expect(normalizePrefs({ milestones: [120, 75, 45, 20] }).milestones).toEqual([120, 75, 45, 20]);
  });
  it("keeps edited import column titles and defaults the blank ones", () => {
    const p = normalizePrefs({ importColumns: { customer: "Tên KH, Công ty", product: "  " } as never });
    expect(p.importColumns.customer).toBe("Tên KH, Công ty");
    expect(p.importColumns.product).toBe("Sản phẩm");
  });
  it("turns a stage timestamp into the right local date", () => {
    expect(isoDateIn(ms("2026-12-16"), "UTC")).toBe("2026-12-16");
  });
});
