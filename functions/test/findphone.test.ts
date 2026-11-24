import { describe, expect, it } from "vitest";
import {
  FindPhoneError, MIN_GAP_MS, cleanName, handleFindPhone, parseFindRequest,
  type DeviceRecord, type FindSender, type FindStore,
} from "../src/findphone";

class MemStore implements FindStore {
  rings = new Map<string, number>();
  constructor(public all: Record<string, DeviceRecord[]>) {}
  async devices(uid: string) { return this.all[uid] ?? []; }
  async forget(uid: string, id: string) { this.all[uid] = (this.all[uid] ?? []).filter((d) => d.id !== id); }
  async lastRing(uid: string, id: string) { return this.rings.get(`${uid}/${id}`) ?? 0; }
  async markRing(uid: string, id: string, at: number) { this.rings.set(`${uid}/${id}`, at); }
}

class Sender implements FindSender {
  sent: { token: string; command: string }[] = [];
  result: "sent" | "gone" = "sent";
  async send(token: string, command: "find-ring" | "find-stop") {
    this.sent.push({ token, command });
    return this.result;
  }
}

const dev = (id: string, over: Partial<DeviceRecord> = {}): DeviceRecord => ({
  id, token: `token-${id}-`.padEnd(40, "x"), platform: "android", name: "Tecno Spark", updatedAt: 100, find: true, ...over,
});

function world() {
  const store = new MemStore({
    me: [dev("p1", { updatedAt: 100 }), dev("p2", { name: "Pixel", updatedAt: 200 }), dev("w1", { platform: "web" }), dev("off", { find: false })],
    other: [dev("theirs")],
  });
  return { store, sender: new Sender() };
}

describe("find my phone", () => {
  it("lists only my Android phones that allow it, newest first, never with their tokens", async () => {
    const { store, sender } = world();
    const r = await handleFindPhone(store, sender, "me", { action: "list" }, 1000);
    expect(r).toEqual({ devices: [{ id: "p2", name: "Pixel", updatedAt: 200 }, { id: "p1", name: "Tecno Spark", updatedAt: 100 }] });
    expect(JSON.stringify(r)).not.toContain("token");
    expect(sender.sent).toEqual([]);
  });

  it("rings the chosen phone and not any other", async () => {
    const { store, sender } = world();
    expect(await handleFindPhone(store, sender, "me", { action: "ring", id: "p1" }, 10_000)).toEqual({ sent: true });
    expect(sender.sent).toEqual([{ token: dev("p1").token, command: "find-ring" }]);
  });

  it("cannot reach someone else's phone, a web browser or a phone that switched it off", async () => {
    const { store, sender } = world();
    for (const id of ["theirs", "w1", "off", "nope"]) {
      await expect(handleFindPhone(store, sender, "me", { action: "ring", id }, 10_000)).rejects.toMatchObject({ code: "not-found" });
    }
    expect(sender.sent).toEqual([]);
  });

  it("holds a second ring back for a few seconds but always lets Stop through", async () => {
    const { store, sender } = world();
    await handleFindPhone(store, sender, "me", { action: "ring", id: "p1" }, 10_000);
    await expect(handleFindPhone(store, sender, "me", { action: "ring", id: "p1" }, 10_000 + MIN_GAP_MS - 1)).rejects.toMatchObject({ code: "resource-exhausted" });
    await handleFindPhone(store, sender, "me", { action: "stop", id: "p1" }, 10_001);
    await handleFindPhone(store, sender, "me", { action: "ring", id: "p1" }, 10_000 + MIN_GAP_MS);
    expect(sender.sent.map((s) => s.command)).toEqual(["find-ring", "find-stop", "find-ring"]);
  });

  it("forgets a phone whose push token is no longer valid", async () => {
    const { store, sender } = world();
    sender.result = "gone";
    await expect(handleFindPhone(store, sender, "me", { action: "ring", id: "p1" }, 10_000)).rejects.toBeInstanceOf(FindPhoneError);
    const left = await handleFindPhone(store, new Sender(), "me", { action: "list" }, 11_000);
    expect(left).toEqual({ devices: [{ id: "p2", name: "Pixel", updatedAt: 200 }] });
  });

  it("accepts only well-formed requests", () => {
    expect(parseFindRequest({ action: "list" })).toEqual({ action: "list" });
    expect(parseFindRequest({ action: "ring", id: "abc_123-X" })).toEqual({ action: "ring", id: "abc_123-X" });
    for (const bad of [null, [], "ring", {}, { action: "ring" }, { action: "ring", id: "a/b" }, { action: "ring", id: "" }, { action: "list", id: "x" }, { action: "ring", id: "ok", extra: 1 }, { action: "delete", id: "ok" }]) {
      expect(() => parseFindRequest(bad)).toThrow(FindPhoneError);
    }
  });

  it("tidies a phone name and falls back to a plain one", () => {
    expect(cleanName("  Tecno   Spark\n 10 ")).toBe("Tecno Spark 10");
    expect(cleanName("x".repeat(100))).toHaveLength(60);
    expect(cleanName(undefined)).toBe("Điện thoại Android");
    expect(cleanName("   ")).toBe("Điện thoại Android");
  });
});
