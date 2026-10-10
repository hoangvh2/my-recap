import { LIMITS } from "./config";

export interface WavInfo {
  seconds: number;
}

export class InvalidAudio extends Error {}

/**
 * Accepts only what the web app records: 16 kHz, 16-bit, mono PCM in a RIFF/WAVE container. Anything
 * else is rejected before it costs a Gemini call, and the duration is read from the header chunks
 * rather than trusted from the client.
 */
export function inspectWav(buf: Buffer): WavInfo {
  if (buf.length < 44) throw new InvalidAudio("Âm thanh quá ngắn");
  if (buf.length > LIMITS.maxAudioBytes) throw new InvalidAudio("Âm thanh quá dài");
  if (buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") {
    throw new InvalidAudio("Định dạng âm thanh không hợp lệ");
  }
  let fmtOk = false;
  let dataBytes = -1;
  let pos = 12;
  while (pos + 8 <= buf.length) {
    const id = buf.toString("ascii", pos, pos + 4);
    const size = buf.readUInt32LE(pos + 4);
    const body = pos + 8;
    if (id === "fmt ") {
      if (size < 16 || body + 16 > buf.length) throw new InvalidAudio("Định dạng âm thanh không hợp lệ");
      const format = buf.readUInt16LE(body);
      const channels = buf.readUInt16LE(body + 2);
      const rate = buf.readUInt32LE(body + 4);
      const bits = buf.readUInt16LE(body + 14);
      if (format !== 1 || channels !== 1 || rate !== 16_000 || bits !== 16) {
        throw new InvalidAudio("Chỉ nhận WAV 16 kHz, 16-bit, mono");
      }
      fmtOk = true;
    } else if (id === "data") {
      dataBytes = Math.min(size, buf.length - body);
      break;
    }
    pos = body + size + (size % 2);
  }
  if (!fmtOk || dataBytes < 0) throw new InvalidAudio("Định dạng âm thanh không hợp lệ");
  const seconds = dataBytes / (16_000 * 2);
  if (seconds < LIMITS.minAudioSeconds) throw new InvalidAudio("Âm thanh quá ngắn");
  if (seconds > LIMITS.maxAudioSeconds) throw new InvalidAudio("Âm thanh quá dài");
  return { seconds };
}
