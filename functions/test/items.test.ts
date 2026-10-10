import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { ItemExtraction } from "../src/items";

const zone = "Asia/Ho_Chi_Minh";
// Saturday 10/10/2026 14:00 local, the same fixed "now" the Android tests use.
const now = DateTime.fromObject({ year: 2026, month: 10, day: 10, hour: 14 }, { zone });
const nowMs = now.toMillis();
let n = 0;
const parse = (raw: string, z = zone, t = nowMs) => ItemExtraction.parse(raw, z, t, "memo1", () => `i${n++}`);
const local = (ms: number, z = zone) => DateTime.fromMillis(ms, { zone: z }).toFormat("yyyy-MM-dd HH:mm");

describe("ItemExtraction.parse", () => {
  it("parses mixed items with dates, times and amounts", () => {
    const raw = `\`\`\`json
{"items":[
  {"type":"event","title":"Họp với anh Nam","details":"","date":"2026-10-15","time":"15:00","place":"Cafe Highlands","person":"anh Nam","quote":"thứ 5 tuần sau 3h chiều họp anh Nam"},
  {"type":"expense","title":"Ăn trưa","date":null,"time":null,"amount":"85k","category":"ăn uống","quote":"ăn trưa 85k"},
  {"type":"task","title":"Gửi báo giá","date":"2026-10-11","time":null},
  {"type":"note","title":"Ý tưởng app","details":"widget ghi nhanh","date":null,"time":null,"amount":5000}
]}
\`\`\``;
    const items = parse(raw)!;
    expect(items).toHaveLength(4);
    expect(items.every((i) => i.status === "DRAFT" && i.sourceId === "memo1")).toBe(true);

    const [event, expense, task, note] = items;
    expect(event.type).toBe("EVENT");
    expect(local(event.whenAt!)).toBe("2026-10-15 15:00");
    expect(event.allDay).toBe(false);
    expect(event.place).toBe("Cafe Highlands");
    expect(event.person).toBe("anh Nam");

    expect(expense.amount).toBe(85_000);
    expect(expense.category).toBe("Ăn uống");
    expect(expense.allDay).toBe(true);
    expect(local(expense.whenAt!)).toBe("2026-10-10 00:00");

    expect(task.allDay).toBe(true);
    expect(local(task.whenAt!)).toBe("2026-10-11 00:00");

    expect(note.amount).toBeUndefined();
    expect(note.whenAt).toBeUndefined();
  });

  it("treats a time without a day as the next occurrence", () => {
    const items = parse(`[{"type":"task","title":"Gọi mẹ","time":"9h"},{"type":"task","title":"Uống thuốc","time":"20:30"}]`)!;
    expect(local(items[0].whenAt!)).toBe("2026-10-11 09:00");
    expect(local(items[1].whenAt!)).toBe("2026-10-10 20:30");
  });

  it("falls back for unknown categories and types", () => {
    const items = parse(`{"items":[{"type":"expense","title":"Quà","amount":200000,"category":"Lặt vặt"},{"type":"idea","title":"X"}]}`)!;
    expect(items[0].category).toBe("Khác");
    expect(items[1].type).toBe("NOTE");
  });

  it("handles empty and broken output like the Android parser", () => {
    expect(parse(`{"items":[]}`)).toHaveLength(0);
    expect(parse(`Kết quả: {"items":[{"type":"task","title":"","details":""}]}`)).toHaveLength(0);
    expect(parse("Xin lỗi, tôi không hiểu.")).toBeNull();
    expect(parse(`{"items": [ {"type": `)).toBeNull();
    expect(parse("")).toBeNull();
  });

  it("reads repeat only for tasks and events that have a date", () => {
    const items = parse(`{"items":[{"type":"event","title":"Họp team","date":"2026-10-12","time":"09:00","repeat":"weekly"},
      {"type":"task","title":"Uống thuốc","time":"20:00","repeat":"daily"},
      {"type":"note","title":"Ý tưởng","repeat":"daily"},
      {"type":"task","title":"Không ngày","repeat":"weekly"}]}`)!;
    expect(items[0].recurrence).toBe("WEEKLY");
    expect(items[1].recurrence).toBe("DAILY");
    expect(items[2].recurrence).toBeUndefined();
    expect(items[3].recurrence).toBeUndefined();
  });

  it("uses the caller's time zone, including across daylight saving", () => {
    const ny = "America/New_York";
    const base = DateTime.fromObject({ year: 2026, month: 3, day: 7, hour: 12 }, { zone: ny }).toMillis();
    const items = parse(`[{"type":"event","title":"Họp","date":"2026-03-09","time":"09:00"}]`, ny, base)!;
    expect(local(items[0].whenAt!, ny)).toBe("2026-03-09 09:00");
  });

  it("rejects impossible dates and times instead of guessing", () => {
    const items = parse(`[{"type":"task","title":"A","date":"2026-02-30","time":"25:00"}]`)!;
    expect(items).toHaveLength(1);
    expect(items[0].whenAt).toBeUndefined();
  });

  it("clips and cleans model text so a hostile note cannot flood the database", () => {
    const long = "x".repeat(5_000);
    const items = parse(JSON.stringify({ items: [{ type: "note", title: `a\u0000b\n${long}`, details: long, place: long, person: long, quote: long }] }))!;
    const [it] = items;
    expect(it.title.length).toBeLessThanOrEqual(120);
    expect(it.title).not.toMatch(/[\u0000-\u001f]/);
    expect(it.details.length).toBeLessThanOrEqual(2_000);
    expect(it.place!.length).toBeLessThanOrEqual(120);
    expect(it.quote!.length).toBeLessThanOrEqual(400);
  });

  it("caps the number of items", () => {
    const many = Array.from({ length: 100 }, (_, i) => ({ type: "task", title: `t${i}` }));
    expect(parse(JSON.stringify({ items: many }))).toHaveLength(20);
  });

  it("never emits undefined fields (Firestore rejects them)", () => {
    const [it] = parse(`[{"type":"task","title":"A","place":null,"person":"","quote":"null"}]`)!;
    expect(Object.values(it).includes(undefined)).toBe(false);
    expect(Object.keys(it)).not.toContain("place");
    expect(Object.keys(it)).not.toContain("person");
    expect(Object.keys(it)).not.toContain("quote");
  });

  it("falls back to details for the title when the title is empty", () => {
    const [it] = parse(`[{"type":"note","title":"","details":"mua sữa cho bé"}]`)!;
    expect(it.title).toBe("mua sữa cho bé");
    expect(it.details).toBe("");
  });
});

describe("ItemExtraction.userMessage", () => {
  it("carries today and the next weekdays", () => {
    const msg = ItemExtraction.userMessage("mai họp", now);
    expect(msg).toContain("Thứ Bảy, 2026-10-10 14:00");
    expect(msg).toContain("- Chủ Nhật 2026-10-11 (mai)");
    expect(msg).toContain("- Thứ Năm 2026-10-15");
    expect(msg).toContain("mai họp");
  });
  it("tells the model the note is data, not instructions", () => {
    expect(ItemExtraction.system()).toContain("DỮ LIỆU");
  });
});
