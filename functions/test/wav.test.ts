import { describe, expect, it } from "vitest";
import { inspectWav } from "../src/wav";
import { makeWav } from "./helpers";

describe("inspectWav", () => {
  it("reads the duration from the header", () => {
    expect(inspectWav(makeWav(5)).seconds).toBeCloseTo(5, 3);
  });
  it("rejects wrong formats", () => {
    expect(() => inspectWav(makeWav(2, { rate: 44_100 }))).toThrow(/16 kHz/);
    expect(() => inspectWav(makeWav(2, { channels: 2 }))).toThrow(/mono/);
    expect(() => inspectWav(makeWav(2, { bits: 8 }))).toThrow();
    expect(() => inspectWav(makeWav(2, { format: 3 }))).toThrow();
    expect(() => inspectWav(Buffer.from("ID3".padEnd(64, "x")))).toThrow();
  });
  it("rejects too short and too long", () => {
    expect(() => inspectWav(makeWav(0.1))).toThrow(/ngắn/);
    expect(() => inspectWav(makeWav(101))).toThrow(/dài/);
  });
  it("does not trust a data size larger than the file", () => {
    const wav = makeWav(2);
    wav.writeUInt32LE(0xffffffff, 40);
    expect(inspectWav(wav).seconds).toBeCloseTo(2, 3);
  });
  it("rejects a header without a data chunk", () => {
    const wav = makeWav(2);
    wav.write("junk", 36, "ascii");
    expect(() => inspectWav(wav)).toThrow();
  });
});
