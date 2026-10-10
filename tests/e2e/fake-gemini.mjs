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
      if (/FAKE_SALES_NEW/.test(text)) {
        return reply(JSON.stringify({
          customers: [{ key: "n1", name: "Công ty Delta", contact: "chị Lan", phone: null, email: null }],
          items: [{ type: "task", title: "Gửi báo giá ERP", details: "", date: tomorrow, time: null, customer: "n1", license: null, quote: "gửi báo giá" }],
          licenses: [{ customer: "n1", product: "Phần mềm ERP", kind: "license", startDate: null, endDate: "2027-06-30", termMonths: null, value: "120 triệu", contractNo: null }],
          updates: [],
        }));
      }
      // Updates and links to the first customer / licence of the context the app sent (aliases c1 / l1).
      if (/FAKE_SALES_UPDATE/.test(text)) {
        return reply(JSON.stringify({
          customers: [],
          items: [{ type: "task", title: "Gọi lại khách", details: "", date: tomorrow, time: null, customer: "c1", license: "l1", quote: "gọi lại" }],
          licenses: [],
          updates: [{ license: "l1", stage: "quoted", value: 50000000, endDate: null, note: "Đã gửi báo giá qua email", lostReason: null }],
        }));
      }
      // The usual mistake of a model: an agreed renewal filed as a note. NEW declares the customer, KNOWN uses c1.
      if (/FAKE_RENEW_NOTE_NEW/.test(text)) {
        return reply(JSON.stringify({
          customers: [{ key: "n1", name: "Khánh", contact: null, phone: null, email: null }],
          items: [{ type: "note", title: "Anh Khánh đồng ý gia hạn license đến 12/2027", details: "", customer: "n1", license: null, quote: "đồng ý gia hạn" }],
          licenses: [], updates: [],
        }));
      }
      if (/FAKE_RENEW_NOTE_KNOWN/.test(text)) {
        return reply(JSON.stringify({
          customers: [],
          items: [{ type: "note", title: "Khánh đồng ý gia hạn license đến 2027", details: "", customer: "c1", license: null, quote: "đồng ý gia hạn" }],
          licenses: [], updates: [],
        }));
      }
      if (/FAKE_SALES_BAD/.test(text)) {
        return reply(JSON.stringify({
          customers: [],
          items: [{ type: "task", title: "Việc bịa mã", details: "", date: tomorrow, time: null, customer: "c99", license: "l99", quote: "x" }],
          licenses: [],
          updates: [{ license: "l99", stage: "renewed" }],
        }));
      }
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
