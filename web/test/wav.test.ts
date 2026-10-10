import { describe, expect, it } from "vitest";
import { encodeWav, peak, resample, toBase64 } from "../src/lib/wav";

describe("wav", () => {
  it("resamples 48 kHz to 16 kHz by averaging", () => {
    const src = new Float32Array(4800).fill(0.5);
    const out = resample(src, 48_000);
    expect(out.length).toBe(1600);
    expect(out[10]).toBeCloseTo(0.5, 5);
  });
  it("resamples 44.1 kHz with the right length and keeps a tone's level", () => {
    const n = 44_100;
    const src = new Float32Array(n).map((_, i) => Math.sin((2 * Math.PI * 440 * i) / 44_100) * 0.8);
    const out = resample(src, 44_100);
    expect(out.length).toBe(16_000);
    expect(peak(out)).toBeGreaterThan(0.7);
  });
  it("passes 16 kHz through untouched", () => {
    const src = new Float32Array(10);
    expect(resample(src, 16_000)).toBe(src);
  });
  it("writes a header the server accepts", () => {
    const wav = encodeWav(new Float32Array(16_000));
    const v = new DataView(wav.buffer);
    expect(String.fromCharCode(...wav.subarray(0, 4))).toBe("RIFF");
    expect(String.fromCharCode(...wav.subarray(8, 12))).toBe("WAVE");
    expect(v.getUint16(20, true)).toBe(1);
    expect(v.getUint16(22, true)).toBe(1);
    expect(v.getUint32(24, true)).toBe(16_000);
    expect(v.getUint16(34, true)).toBe(16);
    expect(v.getUint32(40, true)).toBe(32_000);
    expect(wav.length).toBe(44 + 32_000);
  });
  it("clips instead of wrapping", () => {
    const wav = encodeWav(new Float32Array([2, -2, 0.5]));
    const v = new DataView(wav.buffer);
    expect(v.getInt16(44, true)).toBe(32767);
    expect(v.getInt16(46, true)).toBe(-32768);
  });
  it("encodes large buffers to base64 without overflowing the stack", () => {
    const big = new Uint8Array(3_000_000).fill(65);
    expect(toBase64(big).length).toBe(4_000_000);
    expect(toBase64(new Uint8Array([104, 105]))).toBe("aGk=");
  });
});
