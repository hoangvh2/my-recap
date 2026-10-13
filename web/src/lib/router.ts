import { createStore } from "./store";

export type Route =
  | { name: "today" }
  | { name: "customers" }
  | { name: "customer"; id: string }
  | { name: "renewals" }
  | { name: "license"; id: string }
  | { name: "work" }
  | { name: "settings" }
  | { name: "import" }
  /** Opened from a notification's "Đã xong" button: tick that item off, then show Today. */
  | { name: "done"; id: string }
  /** People I share with, and one of them. */
  | { name: "contacts" }
  | { name: "contact"; uid: string }
  /** Something another person shared with me (read-only), opened from a push. */
  | { name: "shared"; id: string };

const ID = /^[A-Za-z0-9_-]{1,64}$/;
const LONG_ID = /^[A-Za-z0-9_-]{1,200}$/;

/** "#/khach/abc" → customer abc. Anything unknown is the Today screen. */
export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  const [a, b] = parts;
  if (parts.length > 2) return { name: "today" };
  switch (a) {
    case undefined:
      return { name: "today" };
    case "khach":
      return b === undefined ? { name: "customers" } : ID.test(b) ? { name: "customer", id: b } : { name: "customers" };
    case "gia-han":
      return b === undefined ? { name: "renewals" } : ID.test(b) ? { name: "license", id: b } : { name: "renewals" };
    case "viec":
      return { name: "work" };
    case "xong":
      return b !== undefined && ID.test(b) ? { name: "done", id: b } : { name: "today" };
    case "cai-dat":
      return { name: "settings" };
    case "nhap":
      return { name: "import" };
    case "lien-he":
      return b === undefined ? { name: "contacts" } : LONG_ID.test(b) ? { name: "contact", uid: b } : { name: "contacts" };
    case "chia-se":
      return b !== undefined && LONG_ID.test(b) ? { name: "shared", id: b } : { name: "today" };
    default:
      return { name: "today" };
  }
}

/** Addresses of every screen: the one place that knows the URL scheme. */
export const to = {
  today: "#/",
  customers: "#/khach",
  customer: (id: string) => `#/khach/${id}`,
  renewals: "#/gia-han",
  license: (id: string) => `#/gia-han/${id}`,
  work: "#/viec",
  settings: "#/cai-dat",
  import: "#/nhap",
  done: (id: string) => `#/xong/${id}`,
  contacts: "#/lien-he",
  contact: (uid: string) => `#/lien-he/${uid}`,
  shared: (id: string) => `#/chia-se/${id}`,
} as const;

/** Which bottom tab a route belongs to (a customer or licence keeps its own tab lit). */
export function tabOf(r: Route): "today" | "customers" | "renewals" | "work" | null {
  switch (r.name) {
    case "today": case "done": case "shared": return "today";
    case "customers": case "customer": return "customers";
    case "renewals": case "license": return "renewals";
    case "work": return "work";
    default: return null;
  }
}

export const route = createStore<Route>(typeof location === "undefined" ? { name: "today" } : parseRoute(location.hash));

let depth = 0;
let started = false;

/** Follows the address bar. Call once at startup. */
export function startRouter(): void {
  if (started) return;
  started = true;
  depth = Number((history.state as { depth?: number } | null)?.depth ?? 0);
  history.replaceState({ depth }, "");
  route.set(parseRoute(location.hash));
  const sync = () => {
    depth = Number((history.state as { depth?: number } | null)?.depth ?? 0);
    route.set(parseRoute(location.hash));
    window.scrollTo(0, 0);
  };
  window.addEventListener("popstate", sync);
  // An address typed or pasted into the bar changes only the hash.
  window.addEventListener("hashchange", sync);
}

/** Opens a screen. Stays in the app's own history so "Quay lại" returns to where the person came from. */
export function go(hash: string, opts: { replace?: boolean } = {}): void {
  if (hash === location.hash || (hash === "#/" && location.hash === "")) return;
  if (opts.replace) history.replaceState({ depth }, "", hash);
  else history.pushState({ depth: ++depth }, "", hash);
  route.set(parseRoute(hash));
  window.scrollTo(0, 0);
}

/** One step back inside the app, or to `fallback` when this is the first screen. */
export function back(fallback: string): void {
  if (depth > 0) history.back();
  else go(fallback, { replace: true });
}
