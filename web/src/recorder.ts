import { encodeWav, peak, resample, TARGET_RATE } from "./lib/wav";

export const MAX_SECONDS = 90;

export interface Recording {
  wav: Uint8Array;
  seconds: number;
  /** 0..1 loudest sample; near 0 means the microphone heard nothing. */
  peak: number;
}

export class MicError extends Error {}

export function micSupported(): boolean {
  return !!navigator.mediaDevices?.getUserMedia && typeof AudioWorkletNode !== "undefined";
}

/**
 * Records the microphone as raw samples (AudioWorklet) and returns a 16 kHz mono WAV, the one
 * format the capture function accepts. Raw PCM avoids relying on MediaRecorder codecs, which differ
 * between Safari and Chrome. Foreground use only: iOS suspends web audio when the screen locks.
 */
export class Recorder {
  private ctx?: AudioContext;
  private stream?: MediaStream;
  private node?: AudioWorkletNode;
  private chunks: Float32Array[] = [];
  private total = 0;
  private cancelled = false;
  onLevel?: (level: number) => void;

  async start(): Promise<void> {
    // Created before the permission prompt: iOS only lets an AudioContext start inside the tap.
    const ctx = new AudioContext();
    this.ctx = ctx;
    void ctx.resume();
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      await ctx.audioWorklet.addModule("/pcm-worklet.js");
    } catch (e) {
      this.release();
      const name = (e as { name?: string } | null)?.name;
      if (name === "NotAllowedError" || name === "SecurityError") throw new MicError("Chưa được cấp quyền micro. Vào Cài đặt > Safari (hoặc ứng dụng) để cho phép");
      if (name === "NotFoundError") throw new MicError("Không tìm thấy micro");
      throw new MicError("Không bật được micro");
    }
    if (this.cancelled) return this.release();
    const source = ctx.createMediaStreamSource(this.stream);
    const node = new AudioWorkletNode(ctx, "pcm-capture");
    node.port.onmessage = (ev: MessageEvent<Float32Array>) => {
      this.chunks.push(ev.data);
      this.total += ev.data.length;
      if (this.onLevel) {
        let sum = 0;
        for (let i = 0; i < ev.data.length; i++) sum += ev.data[i] * ev.data[i];
        this.onLevel(Math.min(1, Math.sqrt(sum / ev.data.length) * 4));
      }
    };
    // Some engines only run a worklet that feeds the output; a muted gain keeps it alive silently.
    const mute = ctx.createGain();
    mute.gain.value = 0;
    source.connect(node);
    node.connect(mute).connect(ctx.destination);
    this.node = node;
  }

  /** Stops and returns the recording, or null if nothing was captured. */
  async stop(): Promise<Recording | null> {
    const rate = this.ctx?.sampleRate ?? TARGET_RATE;
    this.release();
    if (this.total === 0) return null;
    const all = new Float32Array(this.total);
    let off = 0;
    for (const c of this.chunks) {
      all.set(c, off);
      off += c.length;
    }
    this.chunks = [];
    const samples = resample(all, rate);
    return { wav: encodeWav(samples), seconds: samples.length / TARGET_RATE, peak: peak(samples) };
  }

  cancel(): void {
    this.cancelled = true;
    this.chunks = [];
    this.total = 0;
    this.release();
  }

  private release(): void {
    this.node?.disconnect();
    this.node = undefined;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = undefined;
    void this.ctx?.close().catch(() => undefined);
    this.ctx = undefined;
  }
}
