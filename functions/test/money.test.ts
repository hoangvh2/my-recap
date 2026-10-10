import { describe, expect, it } from "vitest";
import { parseVnd } from "../src/money";

describe("parseVnd", () => {
  it("reads spoken Vietnamese amounts", () => {
    expect(parseVnd("85k")).toBe(85_000);
    expect(parseVnd("85 nghìn")).toBe(85_000);
    expect(parseVnd("1 triệu 2")).toBe(1_200_000);
    expect(parseVnd("1tr5")).toBe(1_500_000);
    expect(parseVnd("1,5 triệu")).toBe(1_500_000);
    expect(parseVnd("1 triệu 250")).toBe(1_250_000);
    expect(parseVnd("1.200.000đ")).toBe(1_200_000);
    expect(parseVnd("2 tỷ")).toBe(2_000_000_000);
  });
  it("returns null without an amount", () => {
    expect(parseVnd("không có")).toBeNull();
    expect(parseVnd("")).toBeNull();
    expect(parseVnd("0")).toBeNull();
    expect(parseVnd("9".repeat(16))).toBeNull();
  });
});
