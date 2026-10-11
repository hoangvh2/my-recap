import { DateTime } from "luxon";
import { describe, expect, it } from "vitest";
import { DEFAULT_PREFS, type Item, type License } from "../../shared/model";
import { digest, digestDue, escapeHtml, group, occurrences, reminders, sampleMessage, type Message } from "../src/notify/plan";
import { PushoverClient, type SendResult, type Sender } from "../src/notify/pushover";
import { DAILY_CAP, manageNotify, NotifyError, tick, type NotifyStore } from "../src/notify/run";
import { DEFAULT_NOTIFY, inQuietHours, parseSettingsRequest, readSettings, SettingsError, type NotifySettings } from "../src/notify/settings";

const zone = "Asia/Ho_Chi_Minh";
const at = (iso: string) => DateTime.fromISO(iso, { zone }).toMillis();
const KEY = "u".repeat(30);
const S: NotifySettings = { ...DEFAULT_NOTIFY, pushoverUser: KEY, zone, updatedAt: 0 };
let n = 0;
const item = (over: Partial<Item>): Item => ({ id: `i${n++}`, type: "TASK", status: "OPEN", title: "Việc", details: "", allDay: false, createdAt: 0, ...over });
const names = new Map([["c1", "Công ty ABC"]]);
const plan = (items: Item[], now: string, s: NotifySettings = S) => reminders({ items, names, settings: s, nowMs: at(now), appUrl: "https://recap.example.com" });

describe("settings", () => {
  const ok = { pushoverUser: KEY, zone, morning: 420, evening: 1200, quietFrom: 1320, quietTo: 390, lead: 15, calendarAlarms: false };
  it("accepts a well-formed save, a test and off", () => {
    expect(parseSettingsRequest({ action: "save", settings: ok })).toEqual({ action: "save", settings: ok });
    expect(parseSettingsRequest({ action: "test" })).toEqual({ action: "test" });
    expect(parseSettingsRequest({ action: "off" })).toEqual({ action: "off" });
  });
  it("refuses bad keys, times, leads and unknown fields", () => {
    for (const bad of [
      { ...ok, pushoverUser: "short" }, { ...ok, morning: 1440 }, { ...ok, quietFrom: -1 }, { ...ok, lead: 7 },
      { ...ok, zone: "Mars/Base" }, { ...ok, device: "bad device!" }, { ...ok, extra: 1 },
    ]) expect(() => parseSettingsRequest({ action: "save", settings: bad })).toThrow(SettingsError);
    expect(() => parseSettingsRequest({ action: "test", x: 1 })).toThrow(SettingsError);
  });
  it("reads a stored document defensively", () => {
    expect(readSettings({ enabled: false, pushoverUser: KEY, zone })).toBeNull();
    expect(readSettings({ enabled: true, pushoverUser: "x", zone })).toBeNull();
    expect(readSettings({ enabled: true, pushoverUser: KEY, zone })).toMatchObject({ morning: 420, lead: 15, calendarAlarms: false });
  });
  it("knows quiet hours across midnight", () => {
    expect(inQuietHours(23 * 60, S)).toBe(true);
    expect(inQuietHours(6 * 60, S)).toBe(true);
    expect(inQuietHours(6 * 60 + 30, S)).toBe(false);
    expect(inQuietHours(12 * 60, S)).toBe(false);
  });
});

describe("reminders", () => {
  const meeting = item({ id: "m1", type: "EVENT", title: "Họp anh Nam", whenAt: at("2026-10-12T15:00"), place: "VP ABC", customerId: "c1", details: "Mang hợp đồng" });

  it("reminds an appointment once, `lead` minutes before, loud, with where/who/what", () => {
    expect(plan([meeting], "2026-10-12T14:44")).toHaveLength(0);
    const [m] = plan([meeting], "2026-10-12T14:45");
    expect(m).toMatchObject({ key: `r-m1-${at("2026-10-12T15:00")}-lead`, priority: 1, url: "https://recap.example.com/#/" });
    expect(m.title).toBe("🟠 15 phút nữa · Họp anh Nam");
    expect(m.body).toContain("<b>15:00 – 16:00 · Hôm nay, Thứ Hai 12/10</b>");
    expect(m.body).toContain("📍 VP ABC");
    expect(m.body).toContain("Công ty ABC");
    expect(m.body).toContain("Mang hợp đồng");
    expect(m.ttlSec).toBe(30 * 60);
    // A missed scheduler run is caught up, but not after the meeting has started.
    expect(plan([meeting], "2026-10-12T14:54")).toHaveLength(1);
    expect(plan([meeting], "2026-10-12T15:10")).toHaveLength(0);
  });

  it("makes an important appointment an emergency, with a heads-up an hour before", () => {
    const vip = { ...meeting, important: true };
    const [hour] = plan([vip], "2026-10-12T14:00");
    expect(hour).toMatchObject({ priority: 0, key: expect.stringContaining("-hour") });
    const [lead] = plan([vip], "2026-10-12T14:45");
    expect(lead.priority).toBe(2);
    expect(lead.title).toContain("🔴 KHẨN");
    expect(lead.body).toContain("Xác nhận");
    expect(lead.ttlSec).toBeUndefined();
  });

  it("reminds a task at its time, and once more two hours later if still open", () => {
    const call = item({ id: "t1", title: "Gọi chị Vân", whenAt: at("2026-10-12T10:00") });
    expect(plan([call], "2026-10-12T10:00")[0]).toMatchObject({ priority: 1, title: "🟠 Đến giờ · Gọi chị Vân" });
    expect(plan([call], "2026-10-12T12:00")[0]).toMatchObject({ priority: 0, title: "🔵 Chưa xong · Gọi chị Vân" });
    expect(plan([{ ...call, status: "DONE" }], "2026-10-12T12:00")).toHaveLength(0);
    expect(plan([{ ...call, status: "DRAFT" }], "2026-10-12T10:00")).toHaveLength(0);
  });

  it("does not send a follow-up in the quiet hours", () => {
    const late = item({ title: "Nộp hồ sơ", whenAt: at("2026-10-12T21:00") });
    expect(plan([late], "2026-10-12T21:00")).toHaveLength(1);
    expect(plan([late], "2026-10-12T23:00")).toHaveLength(0);
  });

  it("leaves all-day entries to the morning summary, or reminds them at 08:00 when there is none", () => {
    const day = item({ title: "Đóng tiền học", whenAt: at("2026-10-12T00:00"), allDay: true });
    expect(plan([day], "2026-10-12T08:00")).toHaveLength(0);
    const [m] = plan([day], "2026-10-12T08:00", { ...S, morning: -1 });
    expect(m).toMatchObject({ priority: 0, title: "🔵 Việc hôm nay · Đóng tiền học" });
  });

  it("reminds every occurrence of a repeating item, even when an earlier one was never ticked", () => {
    const pill = item({ id: "p", title: "Uống thuốc", whenAt: at("2026-10-01T08:00"), recurrence: "DAILY" });
    const [m] = plan([pill], "2026-10-12T08:00");
    expect(m.key).toBe(`r-p-${at("2026-10-12T08:00")}-due`);
    expect(occurrences(pill, at("2026-10-10T00:00"), at("2026-10-12T23:00"), zone)).toHaveLength(3);
    const weekdays = item({ whenAt: at("2026-10-09T09:00"), recurrence: "WEEKDAYS" }); // a Friday
    expect(occurrences(weekdays, at("2026-10-10T00:00"), at("2026-10-13T23:00"), zone)).toEqual([at("2026-10-12T09:00"), at("2026-10-13T09:00")]);
  });

  it("escapes what the owner typed, so a title cannot inject markup", () => {
    const [m] = plan([item({ title: "<b>x</b> & y", whenAt: at("2026-10-12T10:00") })], "2026-10-12T10:00");
    expect(m.body).not.toContain("<b>x</b>");
    expect(escapeHtml("<a & b>")).toBe("&lt;a &amp; b&gt;");
  });
});

describe("group", () => {
  const msg = (key: string, priority: Message["priority"]): Message => ({ key, priority, title: key, body: "", at: 0, line: `• ${key}` });
  it("sends up to three separately, more as one list; emergencies always alone", () => {
    expect(group([msg("a", 1), msg("b", 0), msg("c", 1)], 0)).toHaveLength(3);
    const g = group([msg("a", 1), msg("b", 0), msg("c", 1), msg("d", 0), msg("e", 2)], 0);
    expect(g).toHaveLength(2);
    expect(g[0].key).toBe("e");
    expect(g[1]).toMatchObject({ priority: 1, parts: ["a", "b", "c", "d"] });
    expect(g[1].body).toContain("• d");
  });
});

describe("digest", () => {
  const lic: License = { id: "l1", customerId: "c1", status: "OPEN", product: "Kế toán", kind: "LICENSE", endDate: "2026-10-20", stage: "ACTIVE", stageAt: 0, createdAt: 0, updatedAt: 0 };
  const base = { names, settings: S, licenses: [] as License[], prefs: DEFAULT_PREFS, drafts: 0, appUrl: undefined };

  it("is due once at its time (with catch-up), not before, and can be switched off", () => {
    expect(digestDue("morning", S, at("2026-10-12T06:59"))).toBeNull();
    expect(digestDue("morning", S, at("2026-10-12T07:00"))).toBe("d-morning-2026-10-12");
    expect(digestDue("morning", S, at("2026-10-12T07:09"))).toBe("d-morning-2026-10-12");
    expect(digestDue("morning", S, at("2026-10-12T07:10"))).toBeNull();
    expect(digestDue("morning", { ...S, morning: -1 }, at("2026-10-12T07:00"))).toBeNull();
  });

  it("morning: overdue, today's appointments and tasks, renewals, drafts; louder when something is late", () => {
    const items = [
      item({ type: "EVENT", title: "Họp", whenAt: at("2026-10-12T09:00"), place: "VP" }),
      item({ title: "Gửi báo giá", whenAt: at("2026-10-12T00:00"), allDay: true, customerId: "c1" }),
      item({ title: "Nộp thuế", whenAt: at("2026-10-09T00:00"), allDay: true }),
      item({ title: "Mai làm", whenAt: at("2026-10-13T10:00") }),
    ];
    const m = digest("morning", "k", { ...base, items, licenses: [lic], drafts: 2, nowMs: at("2026-10-12T07:00") })!;
    expect(m.title).toBe("☀️ Hôm nay T2 12/10 · 1 lịch, 1 việc, ⚠️ 1 quá hạn, 1 gia hạn");
    expect(m.priority).toBe(1);
    expect(m.body).toContain("Quá hạn (1)");
    expect(m.body).toContain("Nộp thuế");
    expect(m.body).toContain("<b>09:00</b> Họp <i>(VP)</i>");
    expect(m.body).toContain("<b>Cả ngày</b> Gửi báo giá <i>(Công ty ABC)</i>");
    expect(m.body).toContain("🔴 Công ty ABC · Kế toán");
    expect(m.body).toContain("2 ghi nhanh");
    expect(m.body).not.toContain("Mai làm");
  });

  it("morning with nothing at all sends nothing; a calm day is not loud", () => {
    expect(digest("morning", "k", { ...base, items: [], nowMs: at("2026-10-12T07:00") })).toBeNull();
    const calm = digest("morning", "k", { ...base, items: [item({ title: "Đi chợ", whenAt: at("2026-10-12T17:00") })], nowMs: at("2026-10-12T07:00") })!;
    expect(calm.priority).toBe(0);
  });

  it("evening: tomorrow only, silent, with the earliest time first", () => {
    const items = [item({ type: "EVENT", title: "Khám răng", whenAt: at("2026-10-13T07:30") }), item({ title: "Hôm nay", whenAt: at("2026-10-12T21:00") })];
    const m = digest("evening", "k", { ...base, items, nowMs: at("2026-10-12T20:00") })!;
    expect(m.priority).toBe(-1);
    expect(m.title).toBe("🌙 Ngày mai T3 13/10 · 1 lịch");
    expect(m.body).toContain("Sớm nhất: 07:30");
    expect(m.body).not.toContain("Hôm nay");
    expect(digest("evening", "k", { ...base, items: [], nowMs: at("2026-10-12T20:00") })).toBeNull();
  });

  it("stays within Pushover's limits however much there is", () => {
    const items = Array.from({ length: 80 }, (_, i) => item({ title: `Việc rất dài số ${i} `.repeat(4), whenAt: at("2026-10-12T10:00") }));
    const m = digest("morning", "k", { ...base, items, nowMs: at("2026-10-12T07:00") })!;
    expect(m.body.length).toBeLessThanOrEqual(1024);
    expect(m.body).toContain("mở app để xem");
    expect(m.title.length).toBeLessThanOrEqual(250);
  });
});

// ---------------------------------------------------------------- the scheduler run

function memoryStore(items: Item[], settings: NotifySettings | null = S) {
  const logs = new Map<string, string>();
  const receipts = new Map<string, { receipt: string; itemId: string; until: number }>();
  let count = 0;
  let saved: NotifySettings | null = settings;
  const store: NotifyStore = {
    notifiers: async () => (saved ? ["u1"] : []),
    settings: async () => saved,
    items: async () => items,
    names: async () => names,
    licenses: async () => [],
    prefs: async () => DEFAULT_PREFS,
    drafts: async () => 0,
    sent: async (_u, keys) => new Set(keys.filter((k) => logs.has(k))),
    claim: async (_u, keys) => {
      if (keys.some((k) => logs.has(k))) return "taken";
      if (count >= DAILY_CAP) return "capped";
      count++;
      for (const k of keys) logs.set(k, "sending");
      return "ok";
    },
    finish: async (_u, keys, state, r) => { for (const k of keys) { logs.set(k, state); if (r) receipts.set(k, r); } },
    activeReceipts: async (_u, now) => [...receipts].filter(([, r]) => r.until > now).map(([key, r]) => ({ key, receipt: r.receipt, itemId: r.itemId })),
    clearReceipt: async (_u, key) => { receipts.delete(key); },
    itemsById: async (_u, ids) => new Map(items.filter((i) => ids.includes(i.id)).map((i) => [i.id, i])),
    release: async (_u, keys) => { for (const k of keys) logs.delete(k); count--; },
    saveSettings: async (_u, s) => { saved = s; },
    disable: async () => { saved = null; },
  };
  return { store, logs, receipts, saved: () => saved, setCount: (c: number) => { count = c; } };
}

function fakeSender(results: SendResult[] = []) {
  const sent: Message[] = [];
  const cancelled: string[] = [];
  const sender: Sender = {
    send: async (_u, _d, m) => { sent.push(m); return results.shift() ?? (m.priority === 2 ? { ok: true, receipt: `rc${sent.length}` } : { ok: true }); },
    cancel: async (r) => { cancelled.push(r); return { ok: true }; },
    validate: async (user) => (user === KEY ? { ok: true } : { ok: false, retry: false, status: 400 }),
  };
  return { sender, sent, cancelled };
}

describe("tick", () => {
  const call = item({ id: "t9", title: "Gọi khách", whenAt: at("2026-10-12T10:00") });

  it("sends a due reminder once, even when runs overlap or repeat", async () => {
    const m = memoryStore([call]);
    const f = fakeSender();
    expect(await tick(m.store, f.sender, at("2026-10-12T10:00"))).toMatchObject({ users: 1, sent: 1 });
    expect(await tick(m.store, f.sender, at("2026-10-12T10:01"))).toMatchObject({ sent: 0 });
    expect(f.sent).toHaveLength(1);
    expect(m.logs.get(`r-t9-${at("2026-10-12T10:00")}-due`)).toBe("sent");
  });

  it("retries after a temporary failure, gives up on a refusal", async () => {
    const m = memoryStore([call]);
    const f = fakeSender([{ ok: false, retry: true }, { ok: true }]);
    expect(await tick(m.store, f.sender, at("2026-10-12T10:00"))).toMatchObject({ sent: 0, failed: 1 });
    expect(await tick(m.store, f.sender, at("2026-10-12T10:01"))).toMatchObject({ sent: 1 });

    const r = memoryStore([call]);
    const g = fakeSender([{ ok: false, retry: false, status: 400 }]);
    await tick(r.store, g.sender, at("2026-10-12T10:00"));
    expect(await tick(r.store, g.sender, at("2026-10-12T10:01"))).toMatchObject({ sent: 0, failed: 0 });
    expect(g.sent).toHaveLength(1);
  });

  it("never exceeds the daily cap", async () => {
    const m = memoryStore([call]);
    m.setCount(DAILY_CAP);
    const f = fakeSender();
    expect(await tick(m.store, f.sender, at("2026-10-12T10:00"))).toMatchObject({ sent: 0, capped: 1 });
    expect(f.sent).toHaveLength(0);
  });

  it("sends the morning summary once at its time", async () => {
    const m = memoryStore([item({ type: "EVENT", title: "Họp", whenAt: at("2026-10-12T09:00") })]);
    const f = fakeSender();
    await tick(m.store, f.sender, at("2026-10-12T07:00"));
    await tick(m.store, f.sender, at("2026-10-12T07:01"));
    expect(f.sent.map((x) => x.key)).toEqual(["d-morning-2026-10-12"]);
  });

  it("does nothing for someone who switched off", async () => {
    const m = memoryStore([call], null);
    const f = fakeSender();
    expect(await tick(m.store, f.sender, at("2026-10-12T10:00"))).toMatchObject({ users: 0, sent: 0 });
  });
});

describe("emergencies stop by themselves", () => {
  it("an important appointment stops repeating at its start; an important task after 30 minutes, with a one-tap Đã xong", () => {
    const ev = item({ id: "ev", type: "EVENT", title: "Đón con", whenAt: at("2026-10-12T17:00"), important: true });
    const [m] = plan([ev], "2026-10-12T16:45");
    expect(m).toMatchObject({ priority: 2, itemId: "ev", expireSec: 20 * 60, url: "https://recap.example.com/#/" });
    const task = item({ id: "tk", title: "Đóng tiền điện", whenAt: at("2026-10-12T10:00"), important: true });
    const [t] = plan([task], "2026-10-12T10:00");
    expect(t).toMatchObject({ priority: 2, expireSec: 30 * 60, url: "https://recap.example.com/#/xong/tk", urlTitle: "✅ Đã xong" });
    // An ordinary task gets the same one-tap link.
    const [n] = plan([{ ...task, important: false }], "2026-10-12T10:00");
    expect(n.url).toBe("https://recap.example.com/#/xong/tk");
  });

  it("cancels a repeating emergency as soon as its item is done, unmarked or deleted", async () => {
    const task = item({ id: "tk", title: "Đóng tiền điện", whenAt: at("2026-10-12T10:00"), important: true });
    const items = [task];
    const m = memoryStore(items);
    const f = fakeSender();
    await tick(m.store, f.sender, at("2026-10-12T10:00"));
    expect(m.receipts.size).toBe(1);
    // Still open and important: keeps ringing.
    expect(await tick(m.store, f.sender, at("2026-10-12T10:01"))).toMatchObject({ cancelled: 0 });
    items[0] = { ...task, status: "DONE" };
    expect(await tick(m.store, f.sender, at("2026-10-12T10:02"))).toMatchObject({ cancelled: 1 });
    expect(f.cancelled).toEqual(["rc1"]);
    expect(m.receipts.size).toBe(0);
  });

  it("sends Pushover the shorter expiry and can cancel a receipt", async () => {
    const calls: { url: string; form: URLSearchParams }[] = [];
    const fetchImpl = (async (url: string, init: RequestInit) => {
      calls.push({ url, form: new URLSearchParams(String(init.body)) });
      return new Response(JSON.stringify({ status: 1, receipt: "abc123" }), { status: 200 });
    }) as unknown as typeof fetch;
    const c = new PushoverClient("app-token", "https://po.test", fetchImpl);
    expect(await c.send(KEY, undefined, { key: "k", priority: 2, title: "t", body: "b", at: 0, expireSec: 900 })).toEqual({ ok: true, receipt: "abc123" });
    expect(calls[0].form.get("expire")).toBe("900");
    await c.cancel("abc123");
    expect(calls[1].url).toBe("https://po.test/1/receipts/abc123/cancel.json");
    expect(await c.cancel("../x")).toEqual({ ok: false, retry: false });
  });
});

describe("manageNotify", () => {
  const settings = { pushoverUser: KEY, zone, morning: 420, evening: 1200, quietFrom: 1320, quietTo: 390, lead: 15, calendarAlarms: false };
  it("saves only a key Pushover accepts, then tests and switches off", async () => {
    const m = memoryStore([], null);
    const f = fakeSender();
    await expect(manageNotify(m.store, f.sender, "u1", { action: "save", settings: { ...settings, pushoverUser: "x".repeat(30) } }, 1)).rejects.toBeInstanceOf(NotifyError);
    await expect(manageNotify(m.store, f.sender, "u1", { action: "test" }, 1)).rejects.toThrow("Chưa bật");
    expect(await manageNotify(m.store, f.sender, "u1", { action: "save", settings }, 5)).toEqual({ enabled: true });
    expect(m.saved()).toMatchObject({ enabled: true, updatedAt: 5, lead: 15 });
    await manageNotify(m.store, f.sender, "u1", { action: "test" }, at("2026-10-12T10:00"));
    expect(f.sent[0].title).toContain("Ví dụ");
    expect(await manageNotify(m.store, f.sender, "u1", { action: "off" }, 6)).toEqual({ enabled: false });
    expect(m.saved()).toBeNull();
  });
  it("the sample looks like a real reminder", () => {
    const s = sampleMessage(S, at("2026-10-12T10:00"));
    expect(s.priority).toBe(1);
    expect(s.body).toContain("10:15 – 11:15");
  });
});

describe("PushoverClient", () => {
  function client(status: number, body: object = { status: 1 }) {
    const calls: { url: string; form: URLSearchParams }[] = [];
    const fetchImpl = (async (url: string, init: RequestInit) => {
      calls.push({ url, form: new URLSearchParams(String(init.body)) });
      return new Response(JSON.stringify(body), { status });
    }) as unknown as typeof fetch;
    return { c: new PushoverClient("app-token", "https://po.test", fetchImpl), calls };
  }
  const m: Message = { key: "k", priority: 2, title: "T", body: "<b>B</b>", at: 1_700_000_000_000, url: "https://x/#/", urlTitle: "Mở" };

  it("posts the form Pushover expects, emergency with retry/expire", async () => {
    const { c, calls } = client(200);
    expect(await c.send(KEY, "iphone", m)).toEqual({ ok: true });
    const f = calls[0].form;
    expect(calls[0].url).toBe("https://po.test/1/messages.json");
    expect(Object.fromEntries(f)).toMatchObject({ token: "app-token", user: KEY, device: "iphone", html: "1", priority: "2", retry: "300", expire: "3600", timestamp: "1700000000", url_title: "Mở" });
    expect(f.has("ttl")).toBe(false);
    await c.send(KEY, undefined, { ...m, priority: 1, ttlSec: 600 });
    expect(calls[1].form.get("ttl")).toBe("600");
    expect(calls[1].form.has("retry")).toBe(false);
  });

  it("tells a refusal from a temporary failure", async () => {
    expect(await client(400, { status: 0 }).c.send(KEY, undefined, m)).toEqual({ ok: false, retry: false, status: 400 });
    expect(await client(429).c.send(KEY, undefined, m)).toMatchObject({ ok: false, retry: true });
    expect(await client(503).c.send(KEY, undefined, m)).toMatchObject({ ok: false, retry: true });
    const broken = new PushoverClient("t", "https://po.test", (async () => { throw new Error("net"); }) as unknown as typeof fetch);
    expect(await broken.send(KEY, undefined, m)).toEqual({ ok: false, retry: true });
  });
});
