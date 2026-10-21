import { describe, expect, it } from "vitest";
import {
  appendLines, chipLabel, entriesFrom, parseLines, progress, remainingLabel, reset, splitChecklistLine, tickAll, toggleEntry,
} from "../src/lib/checklist";
import {
  DEFAULTS, addTemplate, instantiate, matching, removeTemplate, restoreDefaults, scheduleLabel, updateTemplate, usedTemplate, validate, byUse,
} from "../src/lib/checklistTemplates";
import type { Item } from "../src/lib/model";
import { finishEvent, nextInstance, rollForward } from "../src/lib/schedule";

const ms = (d: number, h = 12, mi = 0) => new Date(2026, 9, d, h, mi).getTime();
const base = (over: Partial<Item> = {}): Item => ({ id: "a", type: "TASK", status: "OPEN", title: "Ra khỏi nhà", details: "", allDay: false, createdAt: ms(1), ...over });
const withList = (texts: string[], over: Partial<Item> = {}) => base({ checklist: entriesFrom(texts), ...over });

describe("checklist", () => {
  it("parses lines from paste or commas, dropping bullets, blanks and repeats", () => {
    expect(parseLines("ví, chìa khoá;  sạc , Ví")).toEqual(["ví", "chìa khoá", "sạc"]);
    expect(parseLines("- Ví\n[ ] Chìa khoá\n\n* Sạc\nví")).toEqual(["Ví", "Chìa khoá", "Sạc"]);
    expect(parseLines("Ví, ghi chú", false)).toEqual(["Ví, ghi chú"]);
    expect(parseLines(Array.from({ length: 60 }, (_, i) => `m${i}`).join("\n"))).toHaveLength(40);
  });

  it("shows only what is missing, at most three, then a count", () => {
    const i = withList(["Ví", "Chìa khoá", "Sạc", "Thuốc", "Thẻ xe"]);
    expect(remainingLabel(i)).toBe("Còn: Ví · Chìa khoá · Sạc · +2");
    expect(chipLabel(i)).toBe("0/5");
    const t = toggleEntry(toggleEntry(i, i.checklist![0].id), i.checklist![3].id);
    expect(remainingLabel(t)).toBe("Còn: Chìa khoá · Sạc · Thẻ xe");
    expect(chipLabel(t)).toBe("2/5");
    expect(progress(t)).toMatchObject({ done: 2, total: 5, complete: false });
  });

  it("ticking everything says so in words and leaves nothing to show", () => {
    const i = tickAll(withList(["Ví", "Sạc"]));
    expect(progress(i).complete).toBe(true);
    expect(chipLabel(i)).toBe("Đủ 2/2");
    expect(remainingLabel(i)).toBe("");
  });

  it("toggle is reversible and an unknown id changes nothing", () => {
    const i = withList(["Ví"]);
    const id = i.checklist![0].id;
    expect(toggleEntry(toggleEntry(i, id), id)).toEqual(i);
    expect(toggleEntry(i, "nope")).toBe(i);
  });

  it("only tasks and appointments have a checklist", () => {
    const n = base({ type: "NOTE", checklist: entriesFrom(["Ví"]) });
    expect(progress(n).total).toBe(0);
  });

  it("append skips lines already there and stops at the limit", () => {
    const l = entriesFrom(["Ví"]);
    expect(appendLines(l, ["ví", "Sạc"]).map((e) => e.text)).toEqual(["Ví", "Sạc"]);
    expect(appendLines(l, ["VÍ"])).toBe(l);
    expect(appendLines([], Array.from({ length: 50 }, (_, i) => `m${i}`))).toHaveLength(40);
  });

  it("a quick-add line with a colon and two or more lines carries a checklist; times and short lines do not", () => {
    expect(splitChecklistLine("ra khỏi nhà 7h30 hằng ngày: ví, chìa khoá, sạc")).toEqual({ head: "ra khỏi nhà 7h30 hằng ngày", lines: ["ví", "chìa khoá", "sạc"] });
    expect(splitChecklistLine("họp 9:30, mang laptop, báo giá")).toBeNull();
    expect(splitChecklistLine("gọi anh Nam: mai 9h")).toBeNull();
    expect(splitChecklistLine("cà phê 45k")).toBeNull();
    expect(splitChecklistLine(": ví, sạc")).toBeNull();
  });
});

describe("a repeating item starts each occurrence unticked", () => {
  it("a finished daily task comes back with every line unticked", () => {
    const done = tickAll(withList(["Ví", "Sạc"], { recurrence: "DAILY", whenAt: ms(14, 7, 30) }));
    const next = nextInstance({ ...done, status: "DONE" }, "n", ms(14, 7, 40))!;
    expect(next.whenAt).toBe(ms(15, 7, 30));
    expect(progress(next)).toMatchObject({ done: 0, total: 2 });
    expect(progress(done).complete).toBe(true);
  });

  it("a repeating appointment that is finished or passes moves on unticked", () => {
    const ev = tickAll(withList(["Báo giá"], { type: "EVENT", recurrence: "WEEKLY", whenAt: ms(14, 9) }));
    expect(progress(finishEvent(ev, ms(14, 9, 30))).done).toBe(0);
    const passed = withList(["Báo giá"], { type: "EVENT", recurrence: "DAILY", whenAt: ms(14, 9) });
    const moved = rollForward(tickAll(passed), ms(14, 11))!;
    expect(moved.whenAt).toBe(ms(15, 9));
    expect(progress(moved).done).toBe(0);
  });

  it("an item with no checklist is untouched by the reset", () => {
    const plain = base({ recurrence: "DAILY", whenAt: ms(14, 9) });
    expect(nextInstance({ ...plain, status: "DONE" }, "n", ms(14, 10))!.checklist).toBeUndefined();
    expect(reset(plain).checklist).toBeUndefined();
  });
});

describe("checklist templates", () => {
  const draft = { name: "Đi công tác", type: "TASK" as const, lines: "Vé\nSạc\nSạc" };

  it("validates name, lines and time, and refuses a duplicate name", () => {
    expect(validate(DEFAULTS, { ...draft, name: " " })).toBe("Cần nhập tên mẫu");
    expect(validate(DEFAULTS, { ...draft, name: "ra khoi nha" })).toBe("Đã có mẫu trùng tên");
    expect(validate(DEFAULTS, { ...draft, lines: " \n " })).toMatch(/ít nhất một mục/);
    expect(validate(DEFAULTS, { ...draft, time: "25:00" })).toBe("Giờ chưa đúng");
    expect(validate(DEFAULTS, { ...draft, type: "EVENT", recurrence: "DAILY" })).toBe("Lịch hẹn lặp cần có giờ");
    expect(validate(DEFAULTS, draft)).toEqual({ name: "Đi công tác", items: ["Vé", "Sạc"], type: "TASK" });
  });

  it("an edit may keep its own name", () => {
    expect(typeof validate(DEFAULTS, { ...draft, name: "Ra khỏi nhà" }, "default-leave-home")).toBe("object");
  });

  it("add, update, remove and restore the built-in ones", () => {
    const r = validate(DEFAULTS, draft);
    if (typeof r === "string") throw new Error(r);
    const added = addTemplate(DEFAULTS, r);
    expect(added).toHaveLength(4);
    const id = added[3].id;
    expect(updateTemplate(added, id, { ...r, name: "Công tác" })[3]).toMatchObject({ id, name: "Công tác" });
    const gone = removeTemplate(added, "default-delivery");
    expect(gone.map((t) => t.name)).not.toContain("Giao hàng cho khách");
    expect(restoreDefaults(gone).map((t) => t.name)).toContain("Giao hàng cho khách");
    expect(restoreDefaults(DEFAULTS)).toEqual(DEFAULTS);
  });

  it("offers the most used first and finds by words without accents", () => {
    const used = usedTemplate(DEFAULTS, "default-meeting", 5);
    expect(byUse(used)[0].name).toBe("Chuẩn bị họp");
    expect(matching(DEFAULTS, "giao").map((t) => t.name)).toEqual(["Giao hàng cho khách"]);
    expect(matching(DEFAULTS, "chuan bi").map((t) => t.name)).toEqual(["Chuẩn bị họp"]);
    expect(matching(DEFAULTS, "")).toEqual([]);
  });

  it("a daily template at 07:30 starts today when it is still ahead, else tomorrow, never overdue", () => {
    const leave = DEFAULTS[0];
    const early = instantiate(leave, "x", ms(21, 6));
    expect(early).toMatchObject({ type: "TASK", title: "Ra khỏi nhà", recurrence: "DAILY", whenAt: ms(21, 7, 30), allDay: false });
    expect(instantiate(leave, "x", ms(21, 10)).whenAt).toBe(ms(22, 7, 30));
    expect(progress(early)).toMatchObject({ total: 5, done: 0 });
    expect(scheduleLabel(leave)).toBe("Việc · Hằng ngày · 07:30");
  });

  it("a template without a time or repeat is just the base for the editor", () => {
    const i = instantiate(DEFAULTS[1], "x", ms(21, 10));
    expect(i.whenAt).toBeUndefined();
    expect(i.recurrence).toBeUndefined();
    expect(i.checklist).toHaveLength(4);
  });
});
