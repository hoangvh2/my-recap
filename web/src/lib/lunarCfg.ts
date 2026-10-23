import bundled from "../../../shared/holidays-vn.json";
import { parseOverrides, type HolidayOverride } from "../../../shared/holidays";
import { createStore } from "./store";

/** What the lunar calendar and holiday features show. A per-device choice, kept in this browser like the theme. */
export interface LunarPrefs {
  /** The lunar date next to the solar one. */
  show: boolean;
  /** State holidays marked on the calendar. */
  holidays: boolean;
  /** "Sắp nghỉ" banner on Today. */
  banner: boolean;
  /** Days of notice before a break (Tết always at least 14). */
  lead: number;
  /** Mùng 1 and Rằm marked, with a reminder the day before to prepare offerings. Off until chosen. */
  monthly: boolean;
  /** Banners the person hid, as "id:year". */
  dismissed: string[];
}

export const LEAD_CHOICES = [3, 5, 7, 14] as const;
export const DEFAULT_LUNAR_PREFS: LunarPrefs = { show: true, holidays: true, banner: true, lead: 5, monthly: false, dismissed: [] };

const KEY = "vhub.lunar";
const DATA_KEY = "vhub.holidays";

export function readLunarPrefs(raw: unknown): LunarPrefs {
  const r = (raw && typeof raw === "object" ? raw : {}) as Partial<LunarPrefs>;
  const d = DEFAULT_LUNAR_PREFS;
  return {
    show: typeof r.show === "boolean" ? r.show : d.show,
    holidays: typeof r.holidays === "boolean" ? r.holidays : d.holidays,
    banner: typeof r.banner === "boolean" ? r.banner : d.banner,
    lead: (LEAD_CHOICES as readonly number[]).includes(r.lead as number) ? (r.lead as number) : d.lead,
    monthly: typeof r.monthly === "boolean" ? r.monthly : d.monthly,
    dismissed: Array.isArray(r.dismissed) ? r.dismissed.filter((x): x is string => typeof x === "string").slice(-40) : [],
  };
}

function load<T>(key: string): T | null {
  try {
    const raw = typeof localStorage === "undefined" ? null : localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}
function save(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* private mode: the choice lasts until the app is closed */
  }
}

export const lunarPrefs = createStore<LunarPrefs>(readLunarPrefs(load(KEY)));

export function setLunarPrefs(patch: Partial<LunarPrefs>): void {
  const next = readLunarPrefs({ ...lunarPrefs.get(), ...patch });
  lunarPrefs.set(next);
  save(KEY, next);
}

// ---------------------------------------------------------------- the official schedule file

export interface HolidayData {
  /** The date the file was last updated ("2026-10-23"), so the newer of the bundled and the downloaded copy wins. */
  updated: string;
  overrides: HolidayOverride[];
}

const read = (raw: unknown): HolidayData => ({
  updated: typeof (raw as { updated?: unknown } | null)?.updated === "string" ? (raw as { updated: string }).updated : "",
  overrides: parseOverrides(raw),
});

const newer = (a: HolidayData, b: HolidayData): HolidayData => (b.updated > a.updated ? b : a);

/** The bundled schedule, or a newer copy downloaded earlier. */
export const holidayData = createStore<HolidayData>(newer(read(bundled), read(load(DATA_KEY))));

/**
 * Looks for a newer schedule on the site (holidays-vn.json). Failing is fine: the bundled one stays.
 * Returns true when a newer schedule was found.
 */
export async function refreshHolidayData(fetcher: typeof fetch = fetch): Promise<boolean> {
  try {
    const res = await fetcher("/holidays-vn.json", { cache: "no-cache" });
    if (!res.ok) return false;
    const json: unknown = await res.json();
    const got = read(json);
    if (got.overrides.length === 0 || got.updated <= holidayData.get().updated) return false;
    holidayData.set(got);
    save(DATA_KEY, json);
    return true;
  } catch {
    return false;
  }
}
