import { describe, expect, it } from "vitest";
import type { Item } from "../src/lib/model";
import { parseQuick } from "../src/lib/quickadd";
import {
  DEFAULTS, addTemplate, editTemplate, featured, fill, hasBlank, matching, shortAmount, suggest, typeOf, usedTemplate, type QuickTemplate,
} from "../src/lib/templates";

const ms = (d: number, h = 12, mi = 0) => new Date(2026, 9, d, h, mi).getTime();
const now = ms(14, 10);
const t = (text: string, uses = 0): QuickTemplate => ({ text, uses, lastUsed: 0 });
const expense = (title: string, amount: number, day: number): Item => ({ id: `${title}${day}`, type: "EXPENSE", status: "OPEN", title, details: "", allDay: true, amount, createdAt: ms(day) });
const task = (title: string, day: number, hour: number): Item => ({ id: `${title}${day}`, type: "TASK", status: "OPEN", title, details: "", allDay: false, whenAt: ms(day, hour), createdAt: ms(day) });

describe("templates", () => {
  it("fill puts the cursor on the blank", () => {
    expect(fill("ăn trưa …k")).toEqual({ text: "ăn trưa k", cursor: 8 });
    expect(fill("gọi ... mai 9h")).toEqual({ text: "gọi  mai 9h", cursor: 4 });
    expect(fill("cà phê 30k")).toEqual({ text: "cà phê 30k ", cursor: 11 });
  });

  it("blanks and duplicates", () => {
    expect(hasBlank("đổ xăng ...k")).toBe(true);
    expect(hasBlank("cà phê 30k")).toBe(false);
    const list = [t("Cà phê 30k")];
    expect(addTemplate(list, "ca phe   30k")).toBe(list);
    expect(addTemplate(list, "phở 50k")).toHaveLength(2);
    expect(addTemplate(list, "  ")).toBe(list);
  });

  it("short amounts parse back to the same amount", () => {
    for (const amount of [45_000, 5_000_000, 1_250_000, 1_200_000, 45_500, 120_000]) {
      const text = `x ${shortAmount(amount)}`;
      expect(parseQuick(text, now).amount, text).toBe(amount);
    }
    expect(shortAmount(1_200_000)).toBe("1.2tr");
    expect(shortAmount(45_000)).toBe("45k");
  });

  it("suggests repeated entries", () => {
    const history = [
      expense("cà phê", 45_000, 1), expense("Cà phê", 45_000, 2), expense("cà phê", 50_000, 3),
      expense("đổ xăng", 80_000, 4), expense("đổ xăng", 100_000, 5), expense("đổ xăng", 120_000, 6),
      task("họp team", 7, 9), task("họp team", 8, 9), task("họp team", 9, 9),
      expense("phở", 50_000, 1), expense("phở", 50_000, 2), // only twice
    ];
    expect(new Set(suggest(history, [], now))).toEqual(new Set(["cà phê 45k", "đổ xăng …k", "họp team 9h"]));
  });

  it("suggestions skip saved lines, old entries and drafts", () => {
    const history = [expense("cà phê", 45_000, 1), expense("cà phê", 45_000, 2), expense("cà phê", 45_000, 3)];
    expect(suggest(history, [t("cà phê …k")], now)).toEqual([]);
    expect(suggest(history, [], now + 90 * 86_400_000)).toEqual([]);
    expect(suggest(history.map((i) => ({ ...i, status: "DRAFT" as const })), [], now)).toEqual([]);
  });

  it("knows the kind of a line", () => {
    expect(typeOf("ăn trưa …k", now)).toBe("EXPENSE");
    expect(typeOf("cà phê 30k", now)).toBe("EXPENSE");
    expect(typeOf("gọi … mai 9h", now)).toBe("TASK");
    expect(typeOf("họp … thứ 2 9h", now)).toBe("EVENT");
  });

  it("features the most used line of each kind", () => {
    const list = [t("cà phê 30k", 2), t("gọi … mai 9h"), t("đổ xăng …k", 5), t("mua sữa", 1), t("họp … thứ 2 9h")];
    expect(featured(list, now).map((x) => x.text)).toEqual(["mua sữa", "họp … thứ 2 9h", "đổ xăng …k"]);
    expect(featured([t("cà phê 30k")], now).map((x) => x.text)).toEqual(["cà phê 30k"]);
  });

  it("using a line counts it", () => {
    const list = usedTemplate(DEFAULTS, t("ăn trưa …k"), now);
    expect(list.find((x) => x.text === "ăn trưa …k")?.uses).toBe(1);
    expect(featured(list, now).at(-1)?.text).toBe("ăn trưa …k");
  });

  it("matching searches every word, accents ignored", () => {
    const list = [t("cà phê 30k"), t("ăn trưa cá kho …k", 4), t("gọi … mai 9h"), t("cafe với khách …k")];
    expect(matching(list, "ca").map((x) => x.text)).toEqual(["cà phê 30k", "cafe với khách …k", "ăn trưa cá kho …k"]);
    expect(matching(list, "trua ca").map((x) => x.text)).toEqual(["ăn trưa cá kho …k"]);
    expect(matching(list, "goi mai").map((x) => x.text)).toEqual(["gọi … mai 9h"]);
    expect(matching(list, "  ")).toEqual([]);
    expect(matching(list, "xyz")).toEqual([]);
    expect(matching(list, "cà phê 30k")).toEqual([]);
  });

  it("editing keeps use counts and refuses duplicates", () => {
    const list = [t("cà phê 30k", 3), t("phở 50k")];
    expect(editTemplate(list, 0, "cà phê   35k")[0]).toEqual({ text: "cà phê 35k", uses: 3, lastUsed: 0 });
    expect(editTemplate(list, 0, "Phở 50k")).toBe(list);
    expect(editTemplate(list, 0, " ")).toBe(list);
    expect(editTemplate(list, 5, "x")).toBe(list);
  });
});
