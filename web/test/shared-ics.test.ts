import { describe, expect, it } from "vitest";
import { buildItemsCalendar, eventsForLicense, renderCalendar } from "../../shared/ics";
import { DEFAULT_PREFS, type Item, type License } from "../../shared/model";
import type { Ctx } from "../../shared/renewals";

const stamp = Date.UTC(2026, 9, 10, 7, 0, 0);
const lic = (over: Partial<License> = {}): License => ({
  id: "l1", customerId: "c1", status: "OPEN", product: "Phần mềm kế toán", kind: "LICENSE", endDate: "2027-03-15", value: 120_000_000,
  contractNo: "HD-01", stage: "ACTIVE", stageAt: 1, createdAt: 1, updatedAt: 1, ...over,
});
const ctx: Ctx = { today: "2026-10-10", prefs: DEFAULT_PREFS, zone: "UTC" };
/** Calendar lines longer than 75 bytes are folded; read them back as one line. */
const unfold = (s: string) => s.replace(/\r\n /g, "");

describe("licence calendar entries", () => {
  it("puts each milestone and the end date on its day as an all-day entry with a 9:00 alarm", () => {
    const ics = unfold(renderCalendar(eventsForLicense(lic(), "Công ty ABC", ctx), { stamp }));
    for (const day of ["20261215", "20270114", "20270213", "20270301", "20270315"]) expect(ics).toContain(`DTSTART;VALUE=DATE:${day}`);
    expect(ics).toContain("UID:l1-ASK@myrecap");
    expect(ics).toContain("SUMMARY:Gia hạn · Hỏi khách có gia hạn không: Công ty ABC (Phần mềm kế toán)");
    expect(ics).toContain("SUMMARY:Hết hạn: Phần mềm kế toán · Công ty ABC");
    expect(ics).toContain("TRIGGER;RELATED=START:PT9H");
    expect(ics).toContain("Giai đoạn: Chưa liên hệ");
    expect(ics).toContain("Số hợp đồng: HD-01");
  });
  it("leaves out milestones the stage has passed and everything for closed licences", () => {
    const quoted = unfold(renderCalendar(eventsForLicense(lic({ stage: "QUOTED" }), "ABC", ctx), { stamp }));
    expect(quoted).not.toContain("l1-ASK@");
    expect(quoted).not.toContain("l1-QUOTE@");
    expect(quoted).toContain("l1-CONTRACT@");
    expect(eventsForLicense(lic({ stage: "RENEWED" }), "ABC", ctx)).toEqual([]);
  });
  it("never writes phone numbers or e-mail addresses", () => {
    const ics = unfold(renderCalendar(eventsForLicense(lic(), "ABC", ctx), { stamp }));
    expect(ics).not.toMatch(/@(?!myrecap)[a-z0-9-]+\./i);
  });
  it("marks a subscription: name, refresh interval, publish method", () => {
    const ics = renderCalendar([], { stamp, name: "My Recap · Gia hạn", refreshHours: 1 });
    expect(ics).toContain("X-WR-CALNAME:My Recap · Gia hạn");
    expect(ics).toContain("REFRESH-INTERVAL;VALUE=DURATION:PT1H");
    expect(ics).toContain("X-PUBLISHED-TTL:PT1H");
    expect(ics).toContain("METHOD:PUBLISH");
  });
});

describe("item entries read in the owner's time zone", () => {
  const item: Item = { id: "e1", type: "EVENT", status: "OPEN", title: "Họp", details: "", allDay: false, createdAt: 0, whenAt: Date.UTC(2026, 9, 15, 8, 0), customerId: "c1" };
  it("shows 15:00 for 08:00 UTC in Ho Chi Minh, however the server is set", () => {
    const ics = buildItemsCalendar([item], { stamp, zone: "Asia/Ho_Chi_Minh", names: new Map([["c1", "ABC"]]) });
    expect(ics).toContain("DTSTART:20261015T150000");
    expect(ics).toContain("SUMMARY:Họp · ABC");
  });
  it("puts an all-day item on the owner's date", () => {
    const allDay: Item = { ...item, allDay: true, whenAt: Date.UTC(2026, 9, 14, 17, 0) }; // 00:00 on the 15th in Ho Chi Minh
    expect(buildItemsCalendar([allDay], { stamp, zone: "Asia/Ho_Chi_Minh" })).toContain("DTSTART;VALUE=DATE:20261015");
  });
});
