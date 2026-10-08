# My Recap — Báo cáo nghiên cứu & đề xuất kỹ thuật

_Ngày: 2026-10-08 · Trạng thái: chờ duyệt_

## 1. Executive Summary

- **Hiện chưa có app FREE/open source nào đáp ứng đủ yêu cầu.** Các app hiện có chỉ đáp ứng được 1–2 nhu cầu: STT realtime, hoặc dịch, hoặc tóm tắt. Không app nào cho phép cấu hình nhà cung cấp STT/dịch/AI và bật/tắt từng chức năng.
- **Khuyến nghị:** tự build app Android native (Kotlin) với mô hình BYOK (Bring Your Own Key — người dùng tự nhập API key, không cần backend). STT mặc định dùng **Soniox** realtime (~$0.12/giờ; có dịch ~$0.18/giờ). Ước tính chi phí **< $5/tháng** cho khoảng 20 giờ họp.
- Trước khi code MVP, cần **benchmark bằng audio thật** (VI/EN/JA) để xác nhận chất lượng.

## 2. Evidence — Thị trường & open source

| App | Loại | STT realtime | Dịch realtime | Chạy nền / 2 người | Tóm tắt AI | Cấu hình provider | Kết luận |
|---|---|---|---|---|---|---|---|
| Samsung Galaxy AI (Interpreter + Transcript Assist) | Đóng, miễn phí | ✅ (Interpreter) | ✅ VI/JA/EN | Transcript Assist chỉ chạy trên file ghi âm của app Samsung | ✅ | ❌ | Gần nhất, nhưng chỉ chạy trên máy Samsung và không tùy biến được |
| Google Live Transcribe | Đóng, miễn phí | ✅ hơn 80 ngôn ngữ | ❌ | ❌ | ❌ | ❌ | Chỉ có STT |
| Google Translate / MS Translator (chế độ hội thoại) | Đóng, miễn phí | Theo lượt nói | ✅ | ❌ | ❌ | ❌ | Chỉ có dịch, ngắt câu theo khoảng lặng |
| RTranslator | OSS (Apache-2.0) | ✅ offline (Whisper) | ✅ offline (NLLB) | Cần 2 máy kết nối Bluetooth | ❌ | ❌ | Nặng (tải model khoảng 1.2GB, cần RAM ≥6GB), không phải app ghi chú |
| Meetily, OpenWhispr | OSS | ✅ | ❌/hạn chế | — | ✅ | Một phần | **Chỉ có bản desktop**, không có Android |
| Otter.ai | Đóng, freemium | ✅ | ❌ | ✅ | ✅ | ❌ | Free giới hạn số phút, tập trung tiếng Anh, chưa xác nhận hỗ trợ VI/JA |
| sherpa-onnx | Thư viện OSS | ✅ on-device | — | — | — | — | Là thư viện (building block), không phải app |

### Nhà cung cấp STT (giá do vendor/bên thứ 3 công bố, cần xác nhận lại trên trang chính thức)

| Provider | Giá | Điểm mạnh | Rủi ro |
|---|---|---|---|
| **Soniox RT** | ~$0.12/giờ; kèm dịch ~$0.18/giờ | Diarization (tách người nói) và nhận diện ngôn ngữ ở mức từng token, xử lý được trộn ngôn ngữ (code-switching), dịch tích hợp sẵn | Chưa benchmark tiếng Việt trên audio thật |
| Groq Whisper v3 turbo | $0.04/giờ; gói free khoảng 8 giờ audio/ngày | Rất rẻ, chất lượng Whisper | Không phải streaming thật (phải cắt chunk, độ trễ khoảng 3–10s) |
| Deepgram Nova-3 Multilingual | ~$0.0058/phút (~$0.35/giờ); tặng $200 credit | Độ trễ thấp | Chưa xác nhận hỗ trợ tiếng Việt |
| Gemini Live/Flash | ~$0.005/phút (nguồn bên thứ 3) | STT, dịch và tóm tắt trong 1 API | Giá chưa xác minh; tính tiền cả khoảng lặng |
| On-device (sherpa-onnx) | $0 | Chạy offline, riêng tư | Có model Zipformer tiếng Việt từ cộng đồng; tiếng Nhật chưa có model streaming chính thức |

## 3. Analysis — Top 3 phương án

| # | Phương án | Chi phí | Ưu | Nhược |
|---|---|---|---|---|
| **1 (khuyến nghị)** | **Tự build app Kotlin, BYOK, kiến trúc provider dạng plugin** | Khoảng 4–6 tuần dev; vận hành < $5/tháng | Đáp ứng 100% yêu cầu, không cần backend, sau này thêm provider rẻ hơn dễ dàng | Tốn công build và bảo trì |
| 2 | Fork RTranslator rồi thêm cloud STT, tóm tắt, chạy nền | Khoảng 4–5 tuần | Có sẵn pipeline dịch offline | Code C++/Bluetooth không phục vụ ghi chú cuộc họp; phải refactor nhiều, lợi ích thấp |
| 3 | Ghép các app có sẵn (Galaxy AI / Live Transcribe → copy sang Gemini để tóm tắt) | $0, không code | Dùng được ngay | Thao tác thủ công, không tự động, phụ thuộc thiết bị |

## 4. Recommendation — Thiết kế đề xuất (Phương án 1)

**Stack:** Kotlin + Jetpack Compose · Room (DB) · OkHttp WebSocket · Android Keystore/EncryptedPrefs để lưu key · không cần backend.

**Kiến trúc lõi:**

- Định nghĩa các interface `SttProvider` (streaming), `Translator`, `Summarizer`, `Exporter`. Mỗi provider là một adapter riêng; màn Settings cho chọn provider, nhập key và bật/tắt từng chức năng.
- STT luôn bật. Dịch, tóm tắt, viết lại, diarization là các toggle.
- **Ghi audio song song** (Opus, lưu local). Sau buổi họp có thể chạy *pass 2* bằng async STT để có transcript chính xác hơn và tách người nói tốt hơn. Cách này cũng chống mất dữ liệu khi mạng rớt.

**Hai chế độ:**

1. **Meeting/Translate:** hiển thị song ngữ realtime (bản gốc + bản dịch), hỗ trợ chế độ chia đôi màn hình khi ngồi đối diện đối tác. Dịch dùng Soniox built-in, hoặc LLM theo từng câu đã chốt (final).
2. **Interview (chạy nền):** Foreground Service type `microphone`, có thông báo kèm nút Pause/Stop, diarization 2 người, khi kết thúc thì tự động tóm tắt theo template (Q&A, đánh giá ứng viên, action items).

**AI tổng hợp & viết lại:** dùng endpoint dạng OpenAI-compatible (Gemini, Claude, OpenAI, OpenRouter, Ollama tự host). Template prompt có thể tùy chỉnh.

**Share:** Android ShareSheet (Zalo, Slack, Gmail, Notion…), export Markdown/TXT, copy clipboard.

**Ràng buộc Android cần lưu ý:**

- Từ Android 14, **phải bắt đầu ghi âm khi app đang ở foreground**; không thể khởi động microphone service từ nền.
- Cần hướng dẫn người dùng tắt tối ưu pin (battery optimization), vì một số hãng (Xiaomi, Oppo…) tự kill app chạy nền.

## 5. Risks / Unknowns

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| Chất lượng STT tiếng Việt và câu trộn Việt–Anh chưa được kiểm chứng | Cao | Benchmark ở Phase 0 |
| Diarization 2 người khi chỉ dùng 1 mic điện thoại | Trung bình | Chạy pass 2 async; cho phép gán lại người nói thủ công |
| API key lưu trên máy | Thấp (app dùng cá nhân) | Keystore; không log key |
| Pháp lý ghi âm phỏng vấn | Trung bình | Màn hình nhắc xin đồng ý (consent) trước khi ghi |
| Giá Gemini/Deepgram trích từ nguồn bên thứ 3 | Thấp | Xác nhận lại trên trang pricing chính thức |

## 6. Next Actions & Acceptance Criteria

| Phase | Thời gian | Deliverable | Acceptance gate |
|---|---|---|---|
| 0. PoC benchmark | 1 tuần | Script so sánh Soniox / Groq / Deepgram / Gemini trên 3 file audio thật (VI, EN, JA, mỗi file 10 phút) | WER (tỷ lệ lỗi từ) và độ trễ đo bằng số liệu thật; chọn provider mặc định |
| 1. MVP Interview | 2 tuần | Ghi âm nền + STT + diarization + tóm tắt + share | Ghi liên tục 60 phút khi tắt màn hình, không mất đoạn nào; xuất được bản tóm tắt |
| 2. Translate mode | 1–2 tuần | Màn hình song ngữ realtime | Độ trễ hiển thị bản dịch ≤ 3s |
| 3. Mở rộng | Tùy chọn | Provider offline (sherpa-onnx), thêm template | Hoạt động được khi không có mạng |

**Cần anh/chị quyết định:**

1. Duyệt Phương án 1?
2. Có dùng máy Samsung không? Nếu có, nên thử Phương án 3 trong lúc chờ MVP.
3. Cung cấp 3 file audio họp thật (đã ẩn danh) để chạy benchmark.

## Sources

- [RTranslator](https://hellogithub.com/en/repository/niedev/RTranslator) · [Privacy Guides thread](https://discuss.privacyguides.net/t/rtranslator-translation-app-for-android/20429)
- [Meetily](https://meetily.ai/) · [OpenWhispr](https://openwhispr.com/meeting-notes-software)
- [Samsung Galaxy AI languages](https://news.samsung.com/us/samsung-galaxy-ai-now-supports-more-languages-latest-update) · [Galaxy AI 22 languages](https://news.samsung.com/ph/galaxy-ai-expands-support-to-22-languages)
- [So sánh Live Transcribe / Translator](https://blogs.qub.ac.uk/studentatguide/?p=651)
- [Soniox diarization](https://soniox.com/speech-to-text/features/diarization) · [Soniox language ID](https://soniox.com/docs/stt/concepts/language-identification) · [Soniox translate pricing](https://soniox.com/compare/translate/pricing)
- [Groq pricing](https://eesel.ai/blog/groq-pricing) · [Deepgram pricing 2026](https://diyai.io/ai-tools/speech-to-text/deepgram-pricing-2026/)
- [Gemini Live pricing thread](https://discuss.ai.google.dev/t/live-api-pricing-audio-tokens-second-silent-audio/92653)
- [sherpa-onnx](https://pub.dev/documentation/sherpa_onnx/latest/) · [Vietnamese Zipformer](https://app.alphaneural.io/assets/NghiMe/NghiASR)
- [Android 14 FGS types](https://developer.android.google.cn/about/versions/14/changes/fgs-types-required) · [Android 12 FGS restrictions](https://developer.android.com/about/versions/12/foreground-services)
