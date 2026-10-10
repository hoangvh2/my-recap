import { describe, expect, it, vi } from "vitest";
import { GeminiClient, GeminiError, parseGenerateResponse } from "../src/gemini";

const ok = (text: string) => new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text }] }, finishReason: "STOP" }] }), { status: 200 });
const client = (fetchImpl: typeof fetch) => new GeminiClient({ apiKey: "KEY", model: "m", baseUrl: "https://g.test/v1", fetchImpl, retryDelayMs: 0 });

describe("parseGenerateResponse", () => {
  it("skips thought parts and joins text", () => {
    const json = JSON.stringify({ candidates: [{ content: { parts: [{ text: "hmm", thought: true }, { text: "a" }, { text: "b" }] } }] });
    expect(parseGenerateResponse(json)).toBe("ab");
  });
  it("reports blocks and empties without leaking detail", () => {
    expect(() => parseGenerateResponse(JSON.stringify({ promptFeedback: { blockReason: "SAFETY" } }))).toThrow(GeminiError);
    expect(() => parseGenerateResponse("not json")).toThrow(GeminiError);
    expect(() => parseGenerateResponse(JSON.stringify({ candidates: [{ content: { parts: [] }, finishReason: "STOP" }] }))).toThrow(/rỗng/);
  });
});

describe("GeminiClient", () => {
  it("sends the key in a header, never in the URL, and the audio inline", async () => {
    const f = vi.fn(async () => ok("xin chào"));
    const text = await client(f as unknown as typeof fetch).transcribe("instr", Buffer.from("WAVDATA"));
    expect(text).toBe("xin chào");
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("https://g.test/v1/models/m:generateContent");
    expect(url).not.toContain("KEY");
    expect((init.headers as Record<string, string>)["x-goog-api-key"]).toBe("KEY");
    const body = JSON.parse(init.body as string);
    expect(body.contents[0].parts[1].inline_data).toEqual({ mime_type: "audio/wav", data: Buffer.from("WAVDATA").toString("base64") });
  });

  it("asks for JSON on extraction and keeps the system prompt out of the user turn", async () => {
    const f = vi.fn(async () => ok("{}"));
    await client(f as unknown as typeof fetch).generateJson("SYS", "USER");
    const body = JSON.parse((f.mock.calls[0] as unknown as [string, RequestInit])[1].body as string);
    expect(body.system_instruction.parts[0].text).toBe("SYS");
    expect(body.generationConfig.responseMimeType).toBe("application/json");
  });

  it("retries once on 429 or 5xx", async () => {
    const f = vi.fn().mockResolvedValueOnce(new Response("", { status: 503 })).mockResolvedValueOnce(ok("fine"));
    expect(await client(f as unknown as typeof fetch).generateJson("s", "u")).toBe("fine");
    expect(f).toHaveBeenCalledTimes(2);
  });

  it("does not retry client errors and never forwards the upstream body", async () => {
    const f = vi.fn(async () => new Response("secret upstream detail KEY", { status: 403 }));
    const err = await client(f as unknown as typeof fetch).generateJson("s", "u").catch((e) => e as GeminiError);
    expect(f).toHaveBeenCalledTimes(1);
    expect(err).toBeInstanceOf(GeminiError);
    expect((err as GeminiError).message).not.toContain("secret");
    expect((err as GeminiError).message).not.toContain("KEY");
  });

  it("gives up after two failures", async () => {
    const f = vi.fn(async () => new Response("", { status: 500 }));
    await expect(client(f as unknown as typeof fetch).generateJson("s", "u")).rejects.toBeInstanceOf(GeminiError);
    expect(f).toHaveBeenCalledTimes(2);
  });

  it("maps network failures to a retryable error", async () => {
    const f = vi.fn(async () => { throw new TypeError("fetch failed"); });
    await expect(client(f as unknown as typeof fetch).generateJson("s", "u")).rejects.toMatchObject({ retryable: true });
    expect(f).toHaveBeenCalledTimes(2);
  });
});
