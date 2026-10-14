import { describe, expect, it } from "vitest";
import { applyAutoTag, foldText, matchTag, MAX_RULES, normalizeRules, ruleMatches, type TagRule } from "../src/lib/autotag";
import type { Item } from "../src/lib/model";

const task = (over: Partial<Item> = {}): Item => ({ id: "a", type: "TASK", status: "OPEN", title: "Gọi khách", details: "", allDay: true, createdAt: 1, ...over });
const rule = (field: TagRule["field"], text: string, tag: TagRule["tag"] = "red"): TagRule => ({ field, text, tag });

describe("foldText", () => {
  it("ignores case, accents and đ", () => {
    expect(foldText("ĐẶNG Văn Nam")).toBe("dang van nam");
    expect(foldText("Đà Nẵng")).toBe("da nang");
    expect(foldText("đường")).toBe("duong");
  });
  it("treats composed and decomposed accents alike", () => {
    expect(foldText("é")).toBe(foldText("é"));
  });
});

describe("ruleMatches", () => {
  it("person matches item.person only, as a contains test", () => {
    expect(ruleMatches(rule("person", "nam"), task({ person: "Anh Nam" }))).toBe(true);
    expect(ruleMatches(rule("person", "nam"), task({ title: "Gọi anh Nam" }))).toBe(false);
    expect(ruleMatches(rule("person", "nam"), task())).toBe(false);
  });
  it("place matches item.place only", () => {
    expect(ruleMatches(rule("place", "bệnh viện"), task({ place: "BENH VIEN Chợ Rẫy" }))).toBe(true);
    expect(ruleMatches(rule("place", "bệnh viện"), task({ person: "bệnh viện" }))).toBe(false);
  });
  it("keyword looks in the title and the details", () => {
    expect(ruleMatches(rule("keyword", "đóng học phí"), task({ title: "Dong hoc phi cho bé" }))).toBe(true);
    expect(ruleMatches(rule("keyword", "bảo hiểm"), task({ details: "nhớ nộp BẢO HIỂM xe" }))).toBe(true);
    expect(ruleMatches(rule("keyword", "xyz"), task({ place: "xyz", person: "xyz" }))).toBe(false);
  });
  it("an empty or blank rule text never matches", () => {
    expect(ruleMatches(rule("keyword", ""), task())).toBe(false);
    expect(ruleMatches(rule("keyword", "   "), task())).toBe(false);
  });
  it("trims the rule text", () => {
    expect(ruleMatches(rule("keyword", "  khách "), task())).toBe(true);
  });
});

describe("matchTag / applyAutoTag", () => {
  const rules = [rule("keyword", "họp", "blue"), rule("person", "mẹ", "purple"), rule("keyword", "gọi", "red")];
  it("the first matching rule wins", () => {
    expect(matchTag(rules, task({ title: "Gọi mẹ", person: "Mẹ" }))).toBe("purple");
    expect(matchTag(rules, task({ title: "Họp, gọi mẹ", person: "mẹ" }))).toBe("blue");
    expect(matchTag(rules, task({ title: "Gọi thợ" }))).toBe("red");
    expect(matchTag(rules, task({ title: "Mua sữa" }))).toBeUndefined();
  });
  it("tags a new task or appointment without a tag", () => {
    expect(applyAutoTag(rules, task({ title: "Gọi thợ" })).tag).toBe("red");
    expect(applyAutoTag(rules, task({ type: "EVENT", title: "Họp tuần" })).tag).toBe("blue");
  });
  it("never replaces a tag the item already has", () => {
    expect(applyAutoTag(rules, task({ title: "Gọi thợ", tag: "yellow" })).tag).toBe("yellow");
  });
  it("leaves expenses and notes alone", () => {
    const e = task({ type: "EXPENSE", title: "Gọi xe" });
    expect(applyAutoTag(rules, e)).toBe(e);
    const n = task({ type: "NOTE", title: "Gọi xe" });
    expect(applyAutoTag(rules, n)).toBe(n);
  });
  it("returns the same object when nothing matches", () => {
    const t = task({ title: "Mua sữa" });
    expect(applyAutoTag(rules, t)).toBe(t);
    expect(applyAutoTag([], t)).toBe(t);
  });
});

describe("normalizeRules", () => {
  it("keeps valid rules and drops the rest", () => {
    const out = normalizeRules([
      { field: "person", text: "  anh   Nam ", tag: "red" },
      { field: "color", text: "x", tag: "red" },
      { field: "place", text: "x", tag: "green" },
      { field: "place", text: "   ", tag: "red" },
      null,
      "x",
    ]);
    expect(out).toEqual([{ field: "person", text: "anh Nam", tag: "red" }]);
  });
  it("keeps at most 20 rules and caps the text", () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ field: "keyword", text: `k${i}`, tag: "blue" }));
    expect(normalizeRules(many)).toHaveLength(MAX_RULES);
    expect(normalizeRules([{ field: "keyword", text: "x".repeat(200), tag: "blue" }])[0].text).toHaveLength(60);
  });
  it("is empty for anything that is not a list", () => {
    expect(normalizeRules(undefined)).toEqual([]);
    expect(normalizeRules({})).toEqual([]);
  });
});
