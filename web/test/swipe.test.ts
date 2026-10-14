import { describe, expect, it } from "vitest";
import { isHorizontalSwipe, SWIPE_COMMIT, SWIPE_MAX, swipeCommit, swipeOffset } from "../src/lib/swipe";

describe("swipeOffset", () => {
  it("follows the finger up to the limit", () => {
    expect(swipeOffset(-40, true)).toBe(-40);
    expect(swipeOffset(60, true)).toBe(60);
  });
  it("resists beyond the limit and stops shortly after", () => {
    expect(swipeOffset(-SWIPE_MAX, true)).toBe(-SWIPE_MAX);
    expect(swipeOffset(-SWIPE_MAX - 100, true)).toBeLessThan(-SWIPE_MAX);
    expect(swipeOffset(-1000, true)).toBe(-(SWIPE_MAX + 24));
  });
  it("a row that cannot be ticked off does not move right", () => {
    expect(swipeOffset(80, false)).toBe(0);
    expect(swipeOffset(-80, false)).toBe(-80);
  });
});

describe("swipeCommit", () => {
  it("needs a deliberate drag", () => {
    expect(swipeCommit(-(SWIPE_COMMIT - 1))).toBeNull();
    expect(swipeCommit(SWIPE_COMMIT - 1)).toBeNull();
    expect(swipeCommit(0)).toBeNull();
  });
  it("left deletes, right finishes", () => {
    expect(swipeCommit(-SWIPE_COMMIT)).toBe("delete");
    expect(swipeCommit(SWIPE_COMMIT + 10)).toBe("done");
  });
});

describe("isHorizontalSwipe", () => {
  it("ignores small and mostly vertical moves", () => {
    expect(isHorizontalSwipe(6, 0)).toBe(false);
    expect(isHorizontalSwipe(30, 30)).toBe(false);
    expect(isHorizontalSwipe(-30, 8)).toBe(true);
  });
});
