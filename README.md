# My Recap

App Android dùng để ghi âm phỏng vấn và cuộc họp, sau đó chuyển âm thanh thành văn bản (STT) và dùng AI tóm tắt. Ngôn ngữ hỗ trợ: tiếng Việt, tiếng Anh, tiếng Nhật.

**Phạm vi MVP:** chế độ ghi âm nền, cho phép tắt màn hình. STT chạy theo lô (batch) để tiết kiệm chi phí. Chưa có tính năng dịch realtime.

## Cài đặt (Android 10 trở lên)

1. Mở link sau trên điện thoại: **https://github.com/hoangvh2/my-recap/releases/latest/download/MyRecap.apk**
2. Mở file vừa tải và cho phép "Cài ứng dụng không rõ nguồn gốc".
3. Mỗi bản mới được cài đè lên bản cũ, dữ liệu vẫn giữ nguyên.

APK do GitHub Actions build tự động mỗi khi có push (xem `.github/workflows/android.yml`).

## Thiết lập lần đầu (app có checklist hướng dẫn trên màn hình chính)

| Bước | Lý do |
|---|---|
| Cấp quyền Micro và Thông báo | Để ghi âm và hiển thị nút điều khiển trên màn hình khoá |
| Cho phép "Không giới hạn pin" | Tránh Android dừng ghi âm khi tắt màn hình |
| Tecno/HiOS: Phone Master → Auto-start → bật My Recap; Cài đặt → Pin → tắt tiết kiệm pin cho app; khoá app trong đa nhiệm | HiOS tự kill app chạy nền rất mạnh tay |
| Nhập Gemini API key (miễn phí tại https://aistudio.google.com) hoặc Groq key | Cần cho bước chuyển giọng nói thành văn bản và tóm tắt |

## Cách dùng

- **1 chạm GHI ÂM** → tạo 1 *folder* cho buổi phỏng vấn/họp. Có thể tắt màn hình; bấm nút nguồn để thấy 3 nút lớn (Tạm dừng / ⭐ Đánh dấu / Dừng) ngay trên màn hình khoá. Rung 1 lần = đã đánh dấu.
- **Tự nhận biết hội thoại (VAD trên máy, miễn phí):** im lặng ≥ 6 giây (chỉnh được 4–15 s) → đóng đoạn hiện tại, chờ đoạn mới. Im lặng dài trong đoạn bị cắt bỏ, tiếng động ngắn (< 1,5 s) bị bỏ qua → chỉ gửi phần có tiếng nói.
- Mỗi đoạn được chuyển văn bản riêng, AI tự đặt **tiêu đề** (thường là câu hỏi đang được trả lời).
- **Tóm tắt do bạn chủ động:** trong folder chọn các đoạn cần → Tóm tắt → chọn mẫu (Phỏng vấn / Cuộc họp / Tự do). Mỗi folder giữ mọi bản tóm tắt.
- **Ghi tiếp vào folder này** sau giờ nghỉ; **đổi tên folder** bằng nút ✏️.
- **Lời người phỏng vấn:** Giữ nguyên / Rút gọn thành câu hỏi / Bỏ hẳn (Cài đặt → mục 3, chỉ với Gemini).

## Bản web cho iPhone (chỉ Thư ký)

Không cần tài khoản Apple Developer: PWA + Firebase, dữ liệu và khoá Gemini lưu trên Google, chỉ email được cấp quyền mới dùng được. Xem [docs/web-secretary.md](docs/web-secretary.md) (kiến trúc, bảo mật, thiết lập, phần chưa kiểm chứng).

## Kiến trúc

```
core/  (Kotlin thuần, chạy unit test trên JVM)
  Providers.kt   GeminiClient (STT + tóm tắt), OpenAiTranscriber (Whisper), OpenAiChat
  Prompts.kt     prompt transcribe/tóm tắt (VI/EN/JA, nhãn người nói, mốc đánh dấu)
  Adts.kt        định dạng AAC chịu được crash (file bị cắt ngang vẫn đọc được)
  Vad.kt         EnergyVad (ngưỡng ồn nền tự thích nghi) + ClipSegmenter (tách lượt hội thoại, cắt lặng)
app/   (Android, Jetpack Compose)
  recorder/      RecordingService (foreground service loại microphone, wakelock)
                 ClipRecorder (AudioRecord → VAD → AAC 32 kbps, mỗi lượt hội thoại 1 file)
  work/          ProcessWorker (WorkManager: STT tuần tự từng đoạn; tóm tắt khi người dùng yêu cầu; tự retry khi lỗi mạng/429)
  data/          SessionStore (mỗi folder một thư mục: session.json, seg_NNN.aac/.txt, summary_*.md)
  settings/      API key được mã hoá bằng Android Keystore (AES-GCM)
  ui/            Home, màn hình ghi âm (hiện trên màn hình khoá), Chi tiết, Cài đặt
```

Một số quyết định thiết kế:

- **Chia đoạn và xử lý dần:** mỗi đoạn được gửi STT ngay khi ghi xong, nên khi họp kết thúc thì kết quả đã gần như sẵn sàng. Nếu app bị kill giữa chừng, chỉ mất tối đa phần đang ghi dở của đoạn cuối, và phần đó cũng được khôi phục khi mở lại app.
- **Gemini mặc định:** một key dùng cho cả STT, nhận diện người nói và tóm tắt. Có gói miễn phí; gói trả phí khoảng $0.04/giờ âm thanh với Flash-Lite.
- **Groq Whisper:** khoảng $0.04/giờ, có gói miễn phí, nhưng không tách được người nói.

## Build từ source

```bash
./gradlew :core:test                 # unit test, không cần Android SDK
./gradlew :app:assembleRelease       # cần Android SDK (ANDROID_HOME)
./gradlew :app:connectedDebugAndroidTest   # cần thiết bị hoặc emulator
```

### Ký APK

Bản release được ký bằng `keystore/sideload.jks`. **Key này công khai** và chỉ dùng để cài cá nhân, giúp các bản build cài đè được lên nhau.

Nếu muốn dùng key riêng: thêm `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` vào env của job build. Lưu ý: đổi key thì phải gỡ bản cũ trước khi cài bản mới.
