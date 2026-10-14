/** What a row swipe means. Pure, so the thresholds are testable; the gesture itself lives in ItemRow. */
export type SwipeAction = "delete" | "done";

/** Distance the finger must travel (px) before a horizontal move counts as a swipe rather than a tap or a scroll. */
export const SWIPE_LOCK = 10;
/** Release past this far and the action runs; less and the row snaps back. */
export const SWIPE_COMMIT = 88;
/** The row never follows the finger further than this (a little rubber band past the commit point). */
export const SWIPE_MAX = 132;

/**
 * How far the row is shifted for a finger that moved [moved] px sideways (negative = left). Moving right is
 * only possible when the row can be ticked off; beyond SWIPE_MAX the row resists.
 */
export function swipeOffset(moved: number, canRight: boolean): number {
  if (moved > 0 && !canRight) return 0;
  const a = Math.abs(moved);
  const shown = a <= SWIPE_MAX ? a : SWIPE_MAX + (a - SWIPE_MAX) * 0.15;
  return Math.sign(moved) * Math.min(shown, SWIPE_MAX + 24);
}

/** The action a release at [offset] triggers, or null when the row should snap back. */
export function swipeCommit(offset: number): SwipeAction | null {
  if (offset <= -SWIPE_COMMIT) return "delete";
  if (offset >= SWIPE_COMMIT) return "done";
  return null;
}

/** A horizontal swipe, not a vertical scroll: far enough sideways and clearly more sideways than down. */
export function isHorizontalSwipe(dx: number, dy: number): boolean {
  return Math.abs(dx) > SWIPE_LOCK && Math.abs(dx) > Math.abs(dy) * 1.5;
}
