import { describe, expect, it } from "vitest";
import { formatVnd, parseVnd } from "../src/lib/money";

describe("money", () => {
  it("parses what people type", () => {
    expect(parseVnd("85k")).toBe(85_000);
    expect(parseVnd("1tr5")).toBe(1_500_000);
    expect(parseVnd("1.200.000đ")).toBe(1_200_000);
    expect(parseVnd("abc")).toBeNull();
    expect(parseVnd("")).toBeNull();
  });
  it("formats with dots", () => {
    expect(formatVnd(1_250_000)).toBe("1.250.000 đ");
    expect(formatVnd(500)).toBe("500 đ");
    expect(formatVnd(0)).toBe("0 đ");
  });
});
