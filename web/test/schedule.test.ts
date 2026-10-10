import { afterEach, describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { next, nextInstance, rollForward } from "../src/lib/schedule";

const ms = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime();
const fmt = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, "0")}`;
};
const base = (over: Partial<Item>): Item => ({ id: "t", type: "TASK", status: "OPEN", title: "x", details: "", allDay: false, createdAt: 0, ...over });
const originalTz = process.env.TZ;
afterEach(() => { process.env.TZ = originalTz; });

describe("next occurrence", () => {
  const fri = ms(2026, 10, 9, 9);
  it("steps by the rule", () => {
    expect(fmt(next(fri, "DAILY"))).toBe("2026-10-10 9:00");
    expect(fmt(next(fri, "WEEKDAYS"))).toBe("2026-10-12 9:00"); // skips the weekend
    expect(fmt(next(fri, "WEEKLY"))).toBe("2026-10-16 9:00");
    expect(fmt(next(fri, "MONTHLY"))).toBe("2026-11-9 9:00");
  });
  it("moves the 31st to the last day of a short month, and stays there", () => {
    const feb = next(ms(2026, 1, 31, 9), "MONTHLY");
    expect(fmt(feb)).toBe("2026-2-28 9:00");
    expect(fmt(next(feb, "MONTHLY"))).toBe("2026-3-28 9:00");
  });
  it("keeps local wall-clock time across daylight saving", () => {
    process.env.TZ = "America/New_York";
    const before = new Date(2026, 2, 7, 9, 0).getTime();
    const after = new Date(next(before, "DAILY"));
    expect([after.getDate(), after.getHours()]).toEqual([8, 9]);
  });
});

describe("completing a repeating task", () => {
  const task = base({ status: "DONE", title: "Uống thuốc", whenAt: ms(2026, 10, 7, 20), recurrence: "DAILY", doneAt: 1 });
  it("creates the next one that is still ahead", () => {
    const late = nextInstance(task, "t2", ms(2026, 10, 10, 10))!;
    expect(late.id).toBe("t2");
    expect(late.status).toBe("OPEN");
    expect("doneAt" in late).toBe(false);
    expect(fmt(late.whenAt!)).toBe("2026-10-10 20:00");
    const early = nextInstance({ ...task, whenAt: ms(2026, 10, 10, 20) }, "t3", ms(2026, 10, 10, 8))!;
    expect(fmt(early.whenAt!)).toBe("2026-10-11 20:00");
  });
  it("is null for one-off tasks and for appointments", () => {
    expect(nextInstance({ ...task, recurrence: undefined }, "x", 0)).toBeNull();
    expect(nextInstance({ ...task, type: "EVENT" }, "x", 0)).toBeNull();
  });
  it("brings an all-day task back today when it is finished late", () => {
    const t = base({ status: "DONE", whenAt: ms(2026, 10, 5), allDay: true, recurrence: "DAILY" });
    expect(fmt(nextInstance(t, "n", ms(2026, 10, 10, 15))!.whenAt!)).toBe("2026-10-11 0:00");
  });
});

describe("rolling a repeating appointment forward", () => {
  const standup = base({ type: "EVENT", title: "Họp team", whenAt: ms(2026, 10, 5, 9), recurrence: "WEEKLY" });
  it("moves a finished occurrence to the next one", () => {
    expect(fmt(rollForward(standup, ms(2026, 10, 10, 12))!.whenAt!)).toBe("2026-10-12 9:00");
  });
  it("leaves a running, one-off or non-open appointment alone", () => {
    expect(rollForward(standup, ms(2026, 10, 5, 9, 30))).toBeNull();
    expect(rollForward({ ...standup, recurrence: undefined }, ms(2026, 12, 1))).toBeNull();
    expect(rollForward({ ...standup, status: "DONE" }, ms(2026, 12, 1))).toBeNull();
  });
  it("is idempotent, so two devices rolling the same item agree", () => {
    const now = ms(2026, 10, 10, 12);
    const once = rollForward(standup, now)!;
    expect(rollForward(once, now)).toBeNull();
  });
});
