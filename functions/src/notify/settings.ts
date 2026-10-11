import { isValidZone } from "../items";

/**
 * How one person wants to be reminded. Saved only through the `notifysettings` function, which checks
 * the Pushover key with Pushover first, so the scheduler never works from a key that cannot deliver.
 * Times of day are minutes after midnight in `zone`; -1 switches that summary off.
 */
export interface NotifySettings {
  enabled: boolean;
  /** Pushover user (or group) key: 30 letters and digits. */
  pushoverUser: string;
  /** Only this Pushover device, when set. */
  device?: string;
  zone: string;
  /** Morning summary of the day. */
  morning: number;
  /** Evening look at tomorrow, sent silently. */
  evening: number;
  /** Follow-ups and summaries wait outside these hours; reminders the person scheduled never wait. */
  quietFrom: number;
  quietTo: number;
  /** Minutes before an appointment. */
  lead: number;
  /** Keep the alarms of the subscribed iPhone calendar. Off avoids being told twice. */
  calendarAlarms: boolean;
  updatedAt: number;
}

export const DEFAULT_NOTIFY: Omit<NotifySettings, "pushoverUser" | "zone" | "updatedAt"> = {
  enabled: true,
  morning: 7 * 60,
  evening: 20 * 60,
  quietFrom: 22 * 60,
  quietTo: 6 * 60 + 30,
  lead: 15,
  calendarAlarms: false,
};

export const LEADS = [5, 10, 15, 30, 60] as const;
const USER_KEY = /^[A-Za-z0-9]{30}$/;
const DEVICE = /^[A-Za-z0-9_-]{1,25}$/;

export class SettingsError extends Error {}

const minuteOfDay = (v: unknown, allowOff: boolean): number => {
  if (typeof v !== "number" || !Number.isInteger(v) || v < (allowOff ? -1 : 0) || v > 1439) throw new SettingsError("Giờ không hợp lệ");
  return v;
};

export type SettingsRequest =
  | { action: "save"; settings: Omit<NotifySettings, "updatedAt" | "enabled"> }
  | { action: "test" }
  | { action: "off" };

/** The callable payload, checked strictly: unknown keys and wrong types are refused. */
export function parseSettingsRequest(data: unknown): SettingsRequest {
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new SettingsError("Yêu cầu không hợp lệ");
  const d = data as Record<string, unknown>;
  if (d.action === "test" || d.action === "off") {
    if (Object.keys(d).length !== 1) throw new SettingsError("Yêu cầu không hợp lệ");
    return { action: d.action };
  }
  if (d.action !== "save" || Object.keys(d).some((k) => k !== "action" && k !== "settings")) throw new SettingsError("Yêu cầu không hợp lệ");
  const s = d.settings;
  if (!s || typeof s !== "object" || Array.isArray(s)) throw new SettingsError("Yêu cầu không hợp lệ");
  const r = s as Record<string, unknown>;
  const keys = ["pushoverUser", "device", "zone", "morning", "evening", "quietFrom", "quietTo", "lead", "calendarAlarms"];
  if (Object.keys(r).some((k) => !keys.includes(k))) throw new SettingsError("Yêu cầu không hợp lệ");
  const user = typeof r.pushoverUser === "string" ? r.pushoverUser.trim() : "";
  if (!USER_KEY.test(user)) throw new SettingsError("User Key của Pushover gồm 30 chữ và số, xem trong app Pushover");
  const device = typeof r.device === "string" ? r.device.trim() : "";
  if (device && !DEVICE.test(device)) throw new SettingsError("Tên thiết bị Pushover không hợp lệ");
  if (!isValidZone(r.zone)) throw new SettingsError("Múi giờ không hợp lệ");
  if (typeof r.lead !== "number" || !(LEADS as readonly number[]).includes(r.lead)) throw new SettingsError("Thời gian nhắc trước không hợp lệ");
  if (typeof r.calendarAlarms !== "boolean") throw new SettingsError("Yêu cầu không hợp lệ");
  return {
    action: "save",
    settings: {
      pushoverUser: user,
      ...(device ? { device } : {}),
      zone: r.zone,
      morning: minuteOfDay(r.morning, true),
      evening: minuteOfDay(r.evening, true),
      quietFrom: minuteOfDay(r.quietFrom, false),
      quietTo: minuteOfDay(r.quietTo, false),
      lead: r.lead,
      calendarAlarms: r.calendarAlarms,
    },
  };
}

/** A stored document, defensively: anything missing or wrong means "do not send". */
export function readSettings(raw: unknown): NotifySettings | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (r.enabled !== true || typeof r.pushoverUser !== "string" || !USER_KEY.test(r.pushoverUser) || !isValidZone(r.zone)) return null;
  const num = (v: unknown, d: number, lo: number) => (typeof v === "number" && Number.isInteger(v) && v >= lo && v <= 1439 ? v : d);
  return {
    enabled: true,
    pushoverUser: r.pushoverUser,
    ...(typeof r.device === "string" && DEVICE.test(r.device) ? { device: r.device } : {}),
    zone: r.zone,
    morning: num(r.morning, DEFAULT_NOTIFY.morning, -1),
    evening: num(r.evening, DEFAULT_NOTIFY.evening, -1),
    quietFrom: num(r.quietFrom, DEFAULT_NOTIFY.quietFrom, 0),
    quietTo: num(r.quietTo, DEFAULT_NOTIFY.quietTo, 0),
    lead: typeof r.lead === "number" && (LEADS as readonly number[]).includes(r.lead) ? r.lead : DEFAULT_NOTIFY.lead,
    calendarAlarms: r.calendarAlarms === true,
    updatedAt: typeof r.updatedAt === "number" ? r.updatedAt : 0,
  };
}

/** Whether a minute of the day falls in the quiet hours (which may run past midnight). */
export function inQuietHours(minute: number, s: Pick<NotifySettings, "quietFrom" | "quietTo">): boolean {
  if (s.quietFrom === s.quietTo) return false;
  return s.quietFrom < s.quietTo ? minute >= s.quietFrom && minute < s.quietTo : minute >= s.quietFrom || minute < s.quietTo;
}
