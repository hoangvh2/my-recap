import { createStore } from "./store";

/** "Tự động" follows the phone's light/dark setting; "Sáng" and "Tối" fix it. */
export type ThemeMode = "system" | "light" | "dark";
export const THEME_MODES: ThemeMode[] = ["system", "light", "dark"];
export const THEME_LABEL: Record<ThemeMode, string> = { system: "Tự động", light: "Sáng", dark: "Tối" };

const KEY = "vhub.theme";
/** Background of each theme, for the browser / status bar colour (same values as --bg in styles.css). */
const BG = { light: "#f6f7f9", dark: "#0b0d10" } as const;

/** A per-device convenience: kept in this browser only, and the app works the same without it. */
function stored(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch {
    return "system";
  }
}

export const themeMode = createStore<ThemeMode>(typeof document === "undefined" ? "system" : stored());

/** Puts a mode on the page: the data-theme attribute the CSS reads, and the status bar colour. */
export function applyTheme(mode: ThemeMode, doc: Document = document): void {
  const root = doc.documentElement;
  if (mode === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", mode);
  for (const meta of doc.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')) {
    const scheme = meta.media.includes("dark") ? "dark" : "light";
    meta.content = BG[mode === "system" ? scheme : mode];
  }
}

export function setTheme(mode: ThemeMode): void {
  themeMode.set(mode);
  applyTheme(mode);
  try {
    if (mode === "system") localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, mode);
  } catch {
    /* private mode: the choice lasts until the app is closed */
  }
}
