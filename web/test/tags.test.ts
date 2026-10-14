import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { filterByTag, hasTaggedOpen, tagOf, TAG_INFO, withTag } from "../src/lib/tags";

const it_ = (id: string, over: Partial<Item> = {}): Item => ({ id, type: "TASK", status: "OPEN", title: id, details: "", allDay: true, createdAt: 1, ...over });
const list = [
  it_("a", { tag: "red" }), it_("b", { tag: "blue" }), it_("c"), it_("d", { type: "EVENT", tag: "red" }),
  it_("e", { status: "DONE", tag: "purple" }), it_("f", { type: "NOTE", tag: "red" }),
];
const ids = (xs: Item[]) => xs.map((x) => x.id);

describe("tag labels", () => {
  it("has the five names and glyphs", () => {
    expect(Object.values(TAG_INFO).map((t) => `${t.glyph} ${t.label}`)).toEqual(["! Gấp", "★ Nổi bật", "▲ Lưu ý", "? Cần xác nhận", "♥ Gia đình"]);
  });
});

describe("tagOf", () => {
  it("only tasks and appointments carry a tag", () => {
    expect(tagOf(list[0])).toBe("red");
    expect(tagOf(list[5])).toBeUndefined();
    expect(tagOf({ type: "TASK", tag: "pink" as never })).toBeUndefined();
  });
});

describe("hasTaggedOpen", () => {
  it("needs an open task or appointment with a tag", () => {
    expect(hasTaggedOpen(list)).toBe(true);
    expect(hasTaggedOpen([it_("c"), it_("e", { status: "DONE", tag: "red" }), it_("f", { type: "NOTE", tag: "red" })])).toBe(false);
    expect(hasTaggedOpen([])).toBe(false);
  });
});

describe("filterByTag", () => {
  it("no filter keeps everything", () => expect(filterByTag(list, null)).toBe(list));
  it("'any' keeps what has a tag", () => expect(ids(filterByTag(list, "any"))).toEqual(["a", "b", "d", "e"]));
  it("one colour keeps that colour", () => {
    expect(ids(filterByTag(list, "red"))).toEqual(["a", "d"]);
    expect(ids(filterByTag(list, "orange"))).toEqual([]);
  });
});

describe("withTag", () => {
  it("sets and clears", () => {
    const t = withTag(it_("x"), "yellow");
    expect(t.tag).toBe("yellow");
    expect(Object.keys(withTag(t, undefined))).not.toContain("tag");
  });
  it("ignores notes and expenses", () => {
    const n = it_("n", { type: "NOTE" });
    expect(withTag(n, "red")).toBe(n);
  });
});
