/** A valid 16 kHz, 16-bit, mono WAV of silence lasting `seconds`. */
export function makeWav(seconds: number, opts: { rate?: number; channels?: number; bits?: number; format?: number } = {}): Buffer {
  const rate = opts.rate ?? 16_000;
  const channels = opts.channels ?? 1;
  const bits = opts.bits ?? 16;
  const dataBytes = Math.round(seconds * 16_000 * 2);
  const buf = Buffer.alloc(44 + dataBytes);
  buf.write("RIFF", 0, "ascii");
  buf.writeUInt32LE(36 + dataBytes, 4);
  buf.write("WAVE", 8, "ascii");
  buf.write("fmt ", 12, "ascii");
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(opts.format ?? 1, 20);
  buf.writeUInt16LE(channels, 22);
  buf.writeUInt32LE(rate, 24);
  buf.writeUInt32LE((rate * channels * bits) / 8, 28);
  buf.writeUInt16LE((channels * bits) / 8, 32);
  buf.writeUInt16LE(bits, 34);
  buf.write("data", 36, "ascii");
  buf.writeUInt32LE(dataBytes, 40);
  return buf;
}
