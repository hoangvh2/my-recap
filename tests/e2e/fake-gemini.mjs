// A stand-in for the Gemini REST API, so the end-to-end run needs no key and no network.
// It answers the two calls the capture function makes and records what it was sent.
import { createServer } from "node:http";

export function startFakeGemini(port = 8787) {
  const calls = [];
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      let json = {};
      try { json = JSON.parse(body); } catch { /* ignore */ }
      const parts = json.contents?.[0]?.parts ?? [];
      const audio = parts.find((p) => p.inline_data);
      calls.push({
        url: req.url,
        key: req.headers["x-goog-api-key"],
        kind: audio ? "transcribe" : "extract",
        audioMime: audio?.inline_data?.mime_type,
        audioBytes: audio ? Buffer.from(audio.inline_data.data, "base64").length : 0,
        audioHead: audio ? Buffer.from(audio.inline_data.data, "base64").subarray(0, 12).toString("latin1") : "",
        userText: parts.map((p) => p.text).filter(Boolean).join("\n"),
      });
      const reply = (text) => {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ candidates: [{ content: { parts: [{ text }] }, finishReason: "STOP" }] }));
      };
      if (audio) return reply("mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn");
      const text = parts.map((p) => p.text).join("\n");
      if (/FAKE_BROKEN/.test(text)) return reply("Xin lỗi, tôi không hiểu.");
      if (/FAKE_UPSTREAM_DOWN/.test(text)) { res.writeHead(503); return res.end("down"); }
      // "Thời điểm hiện tại: Thứ X, 2026-10-10 14:00 (zone)" and the "(mai)" line give the dates.
      const tomorrow = /- [^\n]*? (\d{4}-\d{2}-\d{2}) \(mai\)/.exec(text)?.[1] ?? "2026-01-02";
      const today = /\(hôm nay\)/.test(text) ? /- [^\n]*? (\d{4}-\d{2}-\d{2}) \(hôm nay\)/.exec(text)?.[1] : null;
      reply(JSON.stringify({
        items: [
          { type: "event", title: "Họp anh Nam", details: "", date: tomorrow, time: "15:00", place: null, person: "anh Nam", repeat: null, quote: "mai 3 giờ chiều họp anh Nam" },
          { type: "expense", title: "Ăn phở", details: "", date: today ?? null, time: null, amount: "65 nghìn", category: "Ăn uống", quote: "trưa nay ăn phở 65 nghìn" },
        ],
      }));
    });
  });
  return new Promise((resolve) => server.listen(port, "127.0.0.1", () => resolve({ calls, close: () => new Promise((r) => server.close(r)) })));
}
