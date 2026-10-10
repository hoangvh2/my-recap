import { describe, expect, it } from "vitest";
import { DEFAULT_PREFS, type License } from "../../shared/model";
import { FeedHandler, FeedRequestError, hashToken, manageFeed, newToken, parseFeedRequest, tokenFromPath, type FeedData, type FeedRecord, type FeedStore } from "../src/feed";

const zone = "Asia/Ho_Chi_Minh";
const NOW = Date.UTC(2026, 9, 10, 7, 0, 0);

function memory(data?: Partial<FeedData>) {
  const metas = new Map<string, FeedRecord>();
  const lookup = new Map<string, { uid: string; zone: string }>();
  let loads = 0;
  const store: FeedStore = {
    get: async (uid) => metas.get(uid) ?? null,
    put: async (uid, r, prev) => {
      if (prev) lookup.delete(hashToken(prev.token));
      lookup.set(hashToken(r.token), { uid, zone: r.zone });
      metas.set(uid, r);
    },
    remove: async (uid, prev) => { lookup.delete(hashToken(prev.token)); metas.delete(uid); },
    uidForHash: async (h) => lookup.get(h) ?? null,
    load: async () => {
      loads++;
      return { items: [], customers: [{ id: "c1", name: "Công ty ABC" }], licenses: [], prefs: DEFAULT_PREFS, ...data };
    },
  };
  return { store, metas, lookup, loads: () => loads };
}

const lic: License = {
  id: "l1", customerId: "c1", status: "OPEN", product: "Kế toán", kind: "LICENSE", endDate: "2027-01-31",
  stage: "ACTIVE", stageAt: NOW, createdAt: NOW, updatedAt: NOW,
};

describe("parseFeedRequest", () => {
  it("accepts the three actions with a zone only", () => {
    expect(parseFeedRequest({ action: "get", zone })).toEqual({ action: "get", zone });
    for (const bad of [null, [], {}, { action: "get" }, { action: "delete", zone }, { action: "get", zone: "Mars/X" }, { action: "get", zone, uid: "x" }]) {
      expect(() => parseFeedRequest(bad)).toThrow(FeedRequestError);
    }
  });
});

describe("tokenFromPath", () => {
  it("accepts only 43 url-safe characters followed by .ics", () => {
    const t = newToken();
    expect(t).toHaveLength(43);
    expect(tokenFromPath(`/calendar/${t}.ics`)).toBe(t);
    for (const bad of ["/calendar/short.ics", `/calendar/${t}`, `/calendar/${t}.ics/x`, `/calendar/../${t}.ics`, `/x/${t}.ics`, `/calendar/${t}.ics?x=1`]) {
      expect(tokenFromPath(bad), bad).toBeNull();
    }
  });
});

describe("manageFeed", () => {
  it("has no link until one is made, keeps it, replaces it and switches it off", async () => {
    const m = memory();
    expect(await manageFeed(m.store, "u1", { action: "get", zone })).toEqual({ token: null });
    const a = (await manageFeed(m.store, "u1", { action: "rotate", zone })).token!;
    expect((await manageFeed(m.store, "u1", { action: "get", zone })).token).toBe(a);
    const b = (await manageFeed(m.store, "u1", { action: "rotate", zone })).token!;
    expect(b).not.toBe(a);
    expect(m.lookup.has(hashToken(a))).toBe(false);
    expect(m.lookup.has(hashToken(b))).toBe(true);
    expect(await manageFeed(m.store, "u1", { action: "revoke", zone })).toEqual({ token: null });
    expect(m.lookup.size).toBe(0);
    expect(m.metas.size).toBe(0);
  });
  it("keeps the same link when the phone moves to another time zone, but follows the new zone", async () => {
    const m = memory();
    const a = (await manageFeed(m.store, "u1", { action: "rotate", zone })).token!;
    const r = await manageFeed(m.store, "u1", { action: "get", zone: "Asia/Tokyo" });
    expect(r.token).toBe(a);
    expect(m.lookup.get(hashToken(a))?.zone).toBe("Asia/Tokyo");
  });
  it("stores only the hash in the lookup, never the raw token", async () => {
    const m = memory();
    const a = (await manageFeed(m.store, "u1", { action: "rotate", zone })).token!;
    expect([...m.lookup.keys()]).toEqual([hashToken(a)]);
    expect([...m.lookup.keys()][0]).not.toContain(a);
  });
});

describe("FeedHandler", () => {
  async function setup(data?: Partial<FeedData>) {
    const m = memory(data);
    const token = (await manageFeed(m.store, "u1", { action: "rotate", zone })).token!;
    let t = NOW;
    const handler = new FeedHandler(m.store, () => t);
    return { m, token, handler, advance: (ms: number) => { t += ms; } };
  }

  it("serves a calendar with reminders and dated items, and no contact details", async () => {
    const { handler, token } = await setup({
      licenses: [lic],
      items: [
        { id: "i1", type: "EVENT", status: "OPEN", title: "Demo cho ABC", details: "", allDay: false, whenAt: Date.UTC(2026, 9, 12, 8, 0), customerId: "c1", createdAt: NOW },
        { id: "i2", type: "TASK", status: "DONE", title: "Việc xong", details: "", allDay: true, whenAt: NOW, createdAt: NOW },
        { id: "i3", type: "EXPENSE", status: "OPEN", title: "Ăn trưa", details: "", allDay: true, whenAt: NOW, amount: 50000, createdAt: NOW },
        { id: "i4", type: "NOTE", status: "OPEN", title: "Không có ngày", details: "", allDay: false, createdAt: NOW },
      ],
    });
    const r = await handler.handle("GET", `/calendar/${token}.ics`);
    expect(r.status).toBe(200);
    const text = r.body.replace(/\r\n /g, "");
    expect(text).toContain("BEGIN:VCALENDAR");
    expect(text).toContain("SUMMARY:Demo cho ABC · Công ty ABC");
    expect(text).toContain("Hỏi khách có gia hạn không: Công ty ABC (Kế toán)");
    expect(text).toContain("UID:l1-END@myrecap");
    expect(text).not.toContain("Việc xong");
    expect(text).not.toContain("Ăn trưa");
    expect(text).not.toContain("Không có ngày");
    expect(text).toContain("REFRESH-INTERVAL");
  });

  it("answers 404 with no body for a wrong, malformed or revoked link, and 405 for other methods", async () => {
    const { handler, token, m } = await setup();
    expect((await handler.handle("GET", `/calendar/${newToken()}.ics`)).status).toBe(404);
    expect((await handler.handle("GET", "/calendar/x.ics")).status).toBe(404);
    expect((await handler.handle("POST", `/calendar/${token}.ics`)).status).toBe(405);
    await manageFeed(m.store, "u1", { action: "revoke", zone });
    const r = await handler.handle("GET", `/calendar/${token}.ics`);
    expect(r).toEqual({ status: 404, body: "" });
  });

  it("caches for ten minutes so polling does not cost a read each time", async () => {
    const { handler, token, m, advance } = await setup();
    await handler.handle("GET", `/calendar/${token}.ics`);
    await handler.handle("GET", `/calendar/${token}.ics`);
    expect(m.loads()).toBe(1);
    advance(11 * 60_000);
    await handler.handle("GET", `/calendar/${token}.ics`);
    expect(m.loads()).toBe(2);
  });

  it("stops serving a replaced link at once, even while its calendar is cached", async () => {
    const { handler, token, m } = await setup();
    await handler.handle("GET", `/calendar/${token}.ics`);
    await manageFeed(m.store, "u1", { action: "rotate", zone });
    expect((await handler.handle("GET", `/calendar/${token}.ics`)).status).toBe(404);
  });
});
