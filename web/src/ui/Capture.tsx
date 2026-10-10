import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError, capture, type CaptureResult } from "../api";
import { toBase64 } from "../lib/wav";
import { MAX_SECONDS, MicError, micSupported, Recorder } from "../recorder";
import { Icon } from "./icons";
import { showToast } from "./toast";

type Phase =
  | { k: "idle" }
  | { k: "starting" }
  | { k: "recording"; sec: number; level: number }
  | { k: "sending" }
  | { k: "typing" };

const MAX_TEXT = 2_000;

export function Capture() {
  const [phase, setPhase] = useState<Phase>({ k: "idle" });
  const [error, setError] = useState<{ msg: string; retry?: () => void } | null>(null);
  const [text, setText] = useState("");
  const rec = useRef<Recorder | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const level = useRef(0);

  useEffect(() => () => {
    clearInterval(timer.current);
    rec.current?.cancel();
  }, []);

  const report = (r: CaptureResult) => {
    if (r.outcome === "no_speech") showToast("Không nghe thấy lời nói nào");
    else if (r.itemCount === 0) showToast("Không có gì cần lưu trong ghi chú này");
    else showToast(`Đã thêm ${r.itemCount} mục nháp. Xem lại bên dưới rồi bấm Lưu`);
  };

  const send = async (input: { text: string } | { audioBase64: string }) => {
    setPhase({ k: "sending" });
    setError(null);
    try {
      report(await capture(input));
      setText("");
      setPhase({ k: "idle" });
    } catch (e) {
      const err = e instanceof ApiError ? e : new ApiError("Có lỗi khi xử lý ghi nhanh", true);
      setError({ msg: err.message, retry: err.retryable ? () => void send(input) : undefined });
      setPhase("text" in input ? { k: "typing" } : { k: "idle" });
    }
  };

  const start = async () => {
    setError(null);
    const r = new Recorder();
    rec.current = r;
    level.current = 0;
    r.onLevel = (v) => (level.current = v);
    setPhase({ k: "starting" });
    try {
      await r.start();
    } catch (e) {
      rec.current = null;
      setError({ msg: e instanceof MicError ? e.message : "Không bật được micro" });
      setPhase({ k: "idle" });
      return;
    }
    const t0 = Date.now();
    setPhase({ k: "recording", sec: 0, level: 0 });
    timer.current = window.setInterval(() => {
      const sec = Math.floor((Date.now() - t0) / 1000);
      setPhase({ k: "recording", sec, level: level.current });
      if (sec >= MAX_SECONDS) void finish();
    }, 200);
  };

  const finish = async () => {
    clearInterval(timer.current);
    const r = rec.current;
    rec.current = null;
    if (!r) return;
    setPhase({ k: "sending" });
    const out = await r.stop();
    if (!out || out.seconds < 0.5) {
      setError({ msg: "Ghi âm quá ngắn, thử lại" });
      return setPhase({ k: "idle" });
    }
    if (out.peak < 0.01) {
      setError({ msg: "Không nghe thấy tiếng, kiểm tra micro rồi thử lại" });
      return setPhase({ k: "idle" });
    }
    await send({ audioBase64: toBase64(out.wav) });
  };

  const cancel = () => {
    clearInterval(timer.current);
    rec.current?.cancel();
    rec.current = null;
    setPhase({ k: "idle" });
  };

  const busy = phase.k === "starting" || phase.k === "sending";
  return (
    <section class="hero" aria-label="Ghi nhanh">
      {phase.k === "recording" ? (
        <div class="rec">
          <div class="rec-top">
            <span class="dot" />
            <span class="timer">{fmtSec(phase.sec)}</span>
            <span class="muted">/ {fmtSec(MAX_SECONDS)}</span>
          </div>
          <div class="meter" aria-hidden="true"><i style={{ width: `${Math.round(phase.level * 100)}%` }} /></div>
          <div class="row gap">
            <button class="btn ghost" onClick={cancel}>Huỷ</button>
            <button class="btn solid grow" onClick={() => void finish()}><Icon name="stop" size={18} /> Xong, phân tích</button>
          </div>
        </div>
      ) : phase.k === "typing" ? (
        <div class="typing">
          <textarea
            value={text}
            maxLength={MAX_TEXT}
            placeholder="VD: mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn"
            onInput={(e) => setText((e.target as HTMLTextAreaElement).value)}
            rows={4}
            autofocus
          />
          <div class="row gap">
            <button class="btn ghost" onClick={() => { setPhase({ k: "idle" }); setError(null); }}>Đóng</button>
            <button class="btn solid grow" disabled={!text.trim()} onClick={() => void send({ text })}>Gửi</button>
          </div>
        </div>
      ) : (
        <div class="idle">
          <button class="mic" disabled={busy || !micSupported()} onClick={() => void start()} aria-label="Ghi nhanh bằng giọng nói">
            {busy ? <span class="spinner" /> : <Icon name="mic" size={34} />}
          </button>
          <div class="grow">
            <h2>Ghi nhanh</h2>
            <p class="hint">
              {phase.k === "sending" ? "Đang nghe và phân loại…"
                : phase.k === "starting" ? "Đang bật micro…"
                : micSupported() ? "“Mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn”"
                : "Trình duyệt này không ghi âm được, hãy gõ ghi chú"}
            </p>
          </div>
        </div>
      )}
      {error && (
        <div class="alert" role="alert">
          <span>{error.msg}</span>
          {error.retry && <button class="link" onClick={error.retry}>Thử lại</button>}
        </div>
      )}
      {phase.k === "idle" && (
        <div class="row end">
          <button class="btn text" onClick={() => setPhase({ k: "typing" })}><Icon name="pencil" size={16} /> Gõ ghi chú</button>
        </div>
      )}
    </section>
  );
}

const fmtSec = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
