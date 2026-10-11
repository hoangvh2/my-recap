/**
 * Safari on iPhone ignores `user-scalable=no` for pinches, so the gesture is stopped here as well:
 * WebKit's own gesture events, and any touch move with two fingers or more. One-finger scrolling is untouched.
 */
export function blockZoom(target: Document = document): void {
  const stop = (e: Event) => e.preventDefault();
  for (const type of ["gesturestart", "gesturechange", "gestureend"]) target.addEventListener(type, stop, { passive: false });
  target.addEventListener("touchmove", (e) => { if ((e as TouchEvent).touches.length > 1) e.preventDefault(); }, { passive: false });
}
