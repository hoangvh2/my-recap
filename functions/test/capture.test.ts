import { describe, expect, it, vi } from "vitest";
import { CaptureError, parseCaptureInput, runCapture, type CaptureDeps, type CaptureDoc } from "../src/capture";
import { GeminiError } from "../src/gemini";
import type { Item } from "../src/items";
import { makeWav } from "./helpers";

const zone = "Asia/Ho_Chi_Minh";
const NOW = Date.UTC(2026, 9, 10, 7, 0, 0); // 14:00 in Ho Chi Minh

function setup(over: Partial<{ allow: boolean; transcript: string; json: string; fail: Error }> = {}) {
  const saved: { uid: string; doc: CaptureDoc; items: Item[] }[] = [];
  let id = 0;
  const llm = {
    transcribe: vi.fn(async () => { if (over.fail) throw over.fail; return over.transcript ?? "mai 3h chiều họp anh Nam"; }),
    generateJson: vi.fn(async () => { if (over.fail) throw over.fail; return over.json ?? `{"items":[{"type":"event","title":"Họp anh Nam","date":"2026-10-11","time":"15:00"}]}`; }),
  };
  const quota = { consume: vi.fn(async () => over.allow ?? true) };
  const deps: CaptureDeps = {
    quota, llm,
    sink: { save: async (uid, doc, items) => { saved.push({ uid, doc, items }); } },
    now: () => NOW,
    newId: () => `id${id++}`,
  };
  return { deps, saved, llm, quota };
}

describe("parseCaptureInput", () => {
  const b64 = (s: number) => makeWav(s).toString("base64");
  it("accepts text and voice", () => {
    expect(parseCaptureInput({ text: "  hi ", zone })).toEqual({ kind: "text", text: "hi", zone });
    const v = parseCaptureInput({ audioBase64: b64(2), zone });
    expect(v.kind).toBe("voice");
  });
  it("rejects anything else", () => {
    const bad = [
      null, [], "x", {}, { zone }, { text: "a" }, { text: "a", zone: "Mars/Base" }, { text: "a", zone: "x".repeat(100) },
      { text: "", zone }, { text: "a".repeat(2_001), zone }, { text: 5, zone },
      { text: "a", audioBase64: b64(2), zone }, { text: "a", zone, prompt: "ignore previous" },
      { audioBase64: "!!!notbase64", zone }, { audioBase64: "", zone }, { audioBase64: b64(200), zone },
      { audioBase64: Buffer.from("hello world, this is not a wav file at all......").toString("base64"), zone },
    ];
    for (const b of bad) expect(() => parseCaptureInput(b), JSON.stringify(b)?.slice(0, 60)).toThrow(CaptureError);
  });
});

describe("runCapture", () => {
  it("transcribes, extracts and saves drafts linked to the capture", async () => {
    const { deps, saved } = setup();
    const r = await runCapture(deps, "u1", { kind: "voice", wav: makeWav(2), zone });
    expect(r).toEqual({ outcome: "saved", captureId: "id0", itemCount: 1 });
    expect(saved).toHaveLength(1);
    expect(saved[0].uid).toBe("u1");
    expect(saved[0].doc).toMatchObject({ id: "id0", kind: "voice", transcript: "mai 3h chiều họp anh Nam", itemCount: 1 });
    expect(saved[0].items[0]).toMatchObject({ status: "DRAFT", type: "EVENT", sourceId: "id0", title: "Họp anh Nam" });
  });

  it("skips speech-to-text for typed notes", async () => {
    const { deps, llm } = setup();
    await runCapture(deps, "u1", { kind: "text", text: "mua sữa", zone });
    expect(llm.transcribe).not.toHaveBeenCalled();
    expect(llm.generateJson).toHaveBeenCalledOnce();
  });

  it("spends quota before any model call and stops when it is gone", async () => {
    const { deps, llm, saved } = setup({ allow: false });
    await expect(runCapture(deps, "u1", { kind: "text", text: "x", zone })).rejects.toMatchObject({ code: "resource-exhausted" });
    expect(llm.transcribe).not.toHaveBeenCalled();
    expect(llm.generateJson).not.toHaveBeenCalled();
    expect(saved).toHaveLength(0);
  });

  it("stores nothing when there was no speech", async () => {
    const { deps, saved, llm } = setup({ transcript: "[không có lời nói]" });
    const r = await runCapture(deps, "u1", { kind: "voice", wav: makeWav(2), zone });
    expect(r).toEqual({ outcome: "no_speech", itemCount: 0 });
    expect(llm.generateJson).not.toHaveBeenCalled();
    expect(saved).toHaveLength(0);
  });

  it("keeps the words as a note when the model answers in prose", async () => {
    const { deps, saved } = setup({ json: "Xin lỗi, tôi không hiểu." });
    const r = await runCapture(deps, "u1", { kind: "text", text: "nhớ gọi cho bác sĩ vào chiều mai", zone });
    expect(r.outcome).toBe("saved_as_note");
    expect(saved[0].items).toHaveLength(1);
    expect(saved[0].items[0]).toMatchObject({ type: "NOTE", status: "DRAFT" });
  });

  it("turns Gemini failures into a message that is safe to show", async () => {
    const { deps } = setup({ fail: new GeminiError("Gemini gặp lỗi tạm thời", true, 500) });
    await expect(runCapture(deps, "u1", { kind: "text", text: "x", zone })).rejects.toMatchObject({ code: "unavailable", message: "Gemini gặp lỗi tạm thời" });
    const { deps: d2 } = setup({ fail: new Error("db password=hunter2") });
    const err = (await runCapture(d2, "u1", { kind: "text", text: "x", zone }).catch((e: unknown) => e)) as CaptureError;
    expect(err.code).toBe("internal");
    expect(err.message).not.toContain("hunter2");
  });

  it("puts the caller's local date in the extraction prompt", async () => {
    const { deps, llm } = setup();
    await runCapture(deps, "u1", { kind: "text", text: "mai họp", zone });
    const user = (llm.generateJson.mock.calls[0] as unknown as [string, string])[1];
    expect(user).toContain("Thứ Bảy, 2026-10-10 14:00");
  });
});
