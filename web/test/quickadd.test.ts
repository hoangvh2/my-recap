import { describe, expect, it } from "vitest";
import { guessCategory } from "../src/lib/categories";
import type { Item } from "../src/lib/model";
import { parseQuick, toItem, type Part } from "../src/lib/quickadd";

const ms = (y: number, mo: number, d: number, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi).getTime();
// Wednesday 2026-10-14 10:00
const now = ms(2026, 10, 14, 10);
const parse = (text: string, off: Part[] = [], history: Item[] = []) => parseQuick(text, now, new Set(off), history);

describe("parseQuick: dates and times", () => {
  it("tomorrow with an afternoon time", () => {
    const r = parse("gọi anh Nam mai 3h chiều");
    expect(r.title).toBe("gọi anh Nam");
    expect(r.whenAt).toBe(ms(2026, 10, 15, 15));
    expect(r.allDay).toBe(false);
    expect(r.type).toBe("TASK");
  });

  it("reads the period before or after the hour", () => {
    expect(parse("uống thuốc mai 8 giờ tối").whenAt).toBe(ms(2026, 10, 15, 20));
    expect(parse("mai sáng 9h30 nộp báo cáo").whenAt).toBe(ms(2026, 10, 15, 9, 30));
    expect(parse("ăn trưa mai 1h trưa").whenAt).toBe(ms(2026, 10, 15, 13));
    expect(parse("dậy mai 7 giờ rưỡi").whenAt).toBe(ms(2026, 10, 15, 7, 30));
    expect(parse("mai 14:05 kiểm tra").whenAt).toBe(ms(2026, 10, 15, 14, 5));
  });

  it("weekdays and next week", () => {
    expect(parse("nộp báo cáo thứ 5").whenAt).toBe(ms(2026, 10, 15));
    expect(parse("họp nhóm thứ năm tuần sau 9h").whenAt).toBe(ms(2026, 10, 22, 9));
    expect(parse("đi chơi chủ nhật").whenAt).toBe(ms(2026, 10, 18));
    expect(parse("báo cáo thứ hai").whenAt).toBe(ms(2026, 10, 19)); // already passed this week: the next one
    expect(parse("việc đó thứ hai tuần này").whenAt).toBe(ms(2026, 10, 12));
  });

  it("explicit dates", () => {
    expect(parse("hạn nộp 20/11").whenAt).toBe(ms(2026, 11, 20));
    expect(parse("sinh nhật 5/3").whenAt).toBe(ms(2027, 3, 5)); // gone this year: next year
    expect(parse("ký hợp đồng ngày 1 tháng 12").whenAt).toBe(ms(2026, 12, 1));
    expect(parse("trả sách ngày 20").whenAt).toBe(ms(2026, 10, 20));
    expect(parse("đóng tiền ngày 5").whenAt).toBe(ms(2026, 11, 5));
    expect(parse("việc 31/2").whenAt).toBeUndefined();
  });

  it("a time alone means today, or tomorrow once it has passed", () => {
    expect(parse("gửi mail 3h chiều").whenAt).toBe(ms(2026, 10, 14, 15));
    expect(parse("gửi mail 9h sáng").whenAt).toBe(ms(2026, 10, 15, 9));
  });

  it("appointments become events only with a time or date", () => {
    const r = parse("họp với khách mai 9h");
    expect(r.type).toBe("EVENT");
    expect(r.title).toBe("họp với khách");
    expect(parse("họp với khách").type).toBe("TASK");
  });

  it("repeat and important", () => {
    const daily = parse("uống thuốc mỗi ngày 8h tối gấp");
    expect(daily.recurrence).toBe("DAILY");
    expect(daily.important).toBe(true);
    expect(daily.title).toBe("uống thuốc");
    expect(daily.whenAt).toBe(ms(2026, 10, 14, 20));
    const weekly = parse("họp giao ban mỗi thứ 2 9h");
    expect(weekly.recurrence).toBe("WEEKLY");
    expect(weekly.whenAt).toBe(ms(2026, 10, 19, 9));
    expect(parse("đóng tiền nhà hằng tháng ngày 5").recurrence).toBe("MONTHLY");
  });

  it("does not misread look-alike words", () => {
    expect(parse("gặp anh Nam mai").important).toBe(false);
    expect(parse("nộp hồ sơ gấp").important).toBe(true);
    expect(parse("xếp thứ tự ưu tiên").whenAt).toBeUndefined();
    expect(parse("kiểm tra thứ tư").whenAt).toBe(ms(2026, 10, 21));
    expect(parse("mua 3 cái bánh").whenAt).toBeUndefined();
    expect(parse("mua 2 hộp sữa").whenAt).toBeUndefined();
  });

  it("turning a part off keeps its words in the title", () => {
    const r = parse("gọi anh Nam mai 3h chiều", ["DAY"]);
    expect(r.title).toBe("gọi anh Nam mai");
    expect(r.whenAt).toBe(ms(2026, 10, 14, 15));
  });

  it("nothing recognised is a plain task", () => {
    const r = parse("mua sữa cho bé");
    expect(r.title).toBe("mua sữa cho bé");
    expect(r.whenAt).toBeUndefined();
    expect(r.spans).toEqual([]);
    expect(parse("mai").title).toBe("mai");
  });

  it("spans say what was understood", () => {
    const r = parse("gọi anh Nam mai 3h chiều");
    expect(r.spans.map((s) => s.shown)).toEqual(["Mai", "15:00"]);
    expect(r.spans.map((s) => s.part)).toEqual(["DAY", "TIME"]);
  });

  it("toItem keeps everything", () => {
    const item = toItem(parse("họp nhóm mai 9h gấp"), "x", now);
    expect(item).toMatchObject({ status: "OPEN", type: "EVENT", important: true, whenAt: ms(2026, 10, 15, 9) });
  });
});

describe("parseQuick: amounts", () => {
  it("an amount makes an expense", () => {
    const r = parse("cà phê 45k");
    expect(r).toMatchObject({ type: "EXPENSE", title: "cà phê", amount: 45_000, category: "Ăn uống", whenAt: ms(2026, 10, 14), allDay: true });
    expect(r.spans.map((s) => s.part)).toEqual(["AMOUNT"]);
    expect(r.spans[0].shown).toBe("45.000 đ");
  });

  it("reads the common amount forms", () => {
    const cases: [string, number][] = [
      ["tiền điện 1tr2", 1_200_000], ["sửa xe 1tr25", 1_250_000], ["học phí 1 triệu 200", 1_200_000], ["quà 1,5 triệu", 1_500_000],
      ["tiền nhà 5tr", 5_000_000], ["điện thoại 2 củ", 2_000_000], ["ăn trưa 120.000đ", 120_000], ["gửi xe 50000 vnd", 50_000],
      ["phở 85 nghìn", 85_000], ["trà đá 2.5k", 2_500],
    ];
    for (const [text, amount] of cases) expect(parse(text).amount, text).toBe(amount);
  });

  it("an expense can carry a past day and a time", () => {
    const r = parse("grab 120k hôm qua 8h");
    expect(r).toMatchObject({ type: "EXPENSE", title: "grab", whenAt: ms(2026, 10, 13, 8), allDay: false, category: "Di chuyển" });
  });

  it("bare numbers are not money", () => {
    const r = parse("gọi 3 người mai 9h");
    expect(r.type).toBe("TASK");
    expect(r.amount).toBeUndefined();
    expect(parse("đọc 200 trang sách").amount).toBeUndefined();
    expect(parse("phòng 1203").amount).toBeUndefined();
    expect(parse("chạy 5km").amount).toBeUndefined();
  });

  it("amount off keeps it a task", () => {
    const r = parse("cà phê 45k", ["AMOUNT"]);
    expect(r).toMatchObject({ type: "TASK", title: "cà phê 45k" });
    expect(r.amount).toBeUndefined();
  });

  it("the category follows the user's own history first", () => {
    const earlier: Item = { id: "a", type: "EXPENSE", status: "DONE", title: "Cà phê", details: "", allDay: true, createdAt: now - 86_400_000, amount: 40_000, category: "Công việc" };
    expect(parse("cà phê 45k", [], [earlier]).category).toBe("Công việc");
    expect(guessCategory("mua điện thoại", [])).toBe("Mua sắm");
    expect(guessCategory("tiền điện", [])).toBe("Hoá đơn");
    expect(guessCategory("vé phim", [])).toBe("Giải trí");
    expect(guessCategory("quà", [])).toBe("Khác");
  });
});
