import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import type { Item } from "../../shared/model";
import { itemFromRecord } from "../src/items";
import { explicitDate, fixTiming, isAppointment } from "../src/timing";

const zone = "Asia/Ho_Chi_Minh";
const NOW = DateTime.fromISO("2026-10-11T09:00", { zone }).toMillis();
const at = (iso: string) => DateTime.fromISO(iso, { zone }).toMillis();
const item = (over: Partial<Item>): Item => ({ id: "i", type: "TASK", status: "DRAFT", title: "", details: "", allDay: false, createdAt: NOW, ...over });

describe("isAppointment", () => {
  it("knows appointments from things to do", () => {
    for (const t of ["Họp anh Nam", "Hẹn gặp chị Lan", "Khám răng cho con", "Đón con lúc 5h", "Sinh nhật mẹ", "Demo ERP cho ABC"]) expect(isAppointment(t)).toBe(true);
    for (const t of ["Gửi báo giá", "Hẹn gửi hợp đồng thứ 5", "Gửi đơn xin nghỉ", "Gọi anh Nam", "Mua sữa"]) expect(isAppointment(t)).toBe(false);
  });
});

describe("explicitDate", () => {
  const f = (t: string) => explicitDate(t, NOW, zone);
  it("reads days that are said in full", () => {
    expect(f("hạn nộp 20/11")).toEqual({ year: 2026, month: 11, day: 20 });
    expect(f("ngày 5 tháng 3")).toEqual({ year: 2027, month: 3, day: 5 });
    expect(f("ngày 12 tháng 11 năm 2026")).toEqual({ year: 2026, month: 11, day: 12 });
    expect(f("ký 01/02/2027")).toEqual({ year: 2027, month: 2, day: 1 });
  });
  it("does not take amounts or fractions for dates", () => {
    expect(f("giá 3.5 triệu")).toBeNull();
    expect(f("chia 1/2 số tiền")).toBeNull();
    expect(f("ngày 31/2")).toBeNull();
  });
});

describe("fixTiming", () => {
  it("turns a dated task about an appointment into an appointment, keeping its time", () => {
    const i = item({ title: "Họp anh Nam", whenAt: at("2026-10-12T15:00") });
    fixTiming(i, NOW, zone);
    expect(i).toMatchObject({ type: "EVENT", whenAt: at("2026-10-12T15:00"), allDay: false });
  });
  it("keeps a dated action a task, so it can go overdue instead of silently passing", () => {
    const i = item({ title: "Gọi anh Nam", whenAt: at("2026-10-12T15:00") });
    fixTiming(i, NOW, zone);
    expect(i.type).toBe("TASK");
  });
  it("gives a task the day its words name when the model left it out", () => {
    const i = item({ title: "Nộp báo cáo thuế", details: "hạn chót ngày 20/10" });
    fixTiming(i, NOW, zone);
    expect(i).toMatchObject({ type: "TASK", whenAt: at("2026-10-20T00:00"), allDay: true });
  });
  it("makes a note about a coming appointment with a day an appointment, and leaves other notes alone", () => {
    const n = item({ type: "NOTE", title: "Sinh nhật vợ ngày 20/11" });
    fixTiming(n, NOW, zone);
    expect(n).toMatchObject({ type: "EVENT", allDay: true, whenAt: at("2026-11-20T00:00") });
    const fact = item({ type: "NOTE", title: "Anh Nam thích cà phê sữa" });
    fixTiming(fact, NOW, zone);
    expect(fact.type).toBe("NOTE");
    expect(fact.whenAt).toBeUndefined();
  });
  it("never touches expenses", () => {
    const e = item({ type: "EXPENSE", title: "Ăn tối với khách", whenAt: at("2026-10-11T00:00"), allDay: true });
    fixTiming(e, NOW, zone);
    expect(e.type).toBe("EXPENSE");
  });
});

describe("important", () => {
  it("is kept for tasks and appointments only", () => {
    const ctx = { zone, nowMs: NOW, sourceId: "s", newId: () => "x" };
    expect(itemFromRecord({ type: "task", title: "Đóng tiền nhà", important: true }, ctx)?.important).toBe(true);
    expect(itemFromRecord({ type: "event", title: "Họp", important: "true" }, ctx)?.important).toBe(true);
    expect(itemFromRecord({ type: "note", title: "Ghi chú", important: true }, ctx)?.important).toBeUndefined();
    expect(itemFromRecord({ type: "task", title: "Việc", important: false }, ctx)?.important).toBeUndefined();
  });
});
