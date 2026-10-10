# My Recap trên iPhone (iOS 17) — báo cáo phương án

_Ngày: 10/10/2026. Phạm vi: đưa các chức năng hiện có (ghi âm phỏng vấn tắt màn hình, Ghi nhanh + Thư ký, nhắc việc, sao lưu) lên iPhone._

> **Cập nhật:** nếu chỉ cần chức năng Thư ký (không ghi âm nền), bản web PWA là đủ và không cần tài khoản Apple Developer. Xem [web-secretary.md](web-secretary.md). Báo cáo dưới đây vẫn đúng cho trường hợp cần đủ chức năng ghi âm nền trên iOS.

## Executive Summary
- **Không thể cài APK lên iPhone**. Muốn chạy trên iOS phải có bản build iOS riêng, được ký bằng tài khoản Apple.
- **Khuyến nghị:** Kotlin Multiplatform (KMP) + Compose Multiplatform. Dùng lại phần lõi Kotlin hiện có (prompt, phân tích, VAD, lịch lặp, sao lưu) và phần lớn UI. Viết riêng cho iOS các phần hệ thống: ghi âm nền, thông báo, widget, Shortcuts.
- **Chi phí bắt buộc thực tế:** Apple Developer Program **99 USD/năm**. Không có gói này, app hết hạn sau **7 ngày** và phải cài lại.
- **Ước lượng:** 6–9 tuần làm việc tập trung để đạt mức tương đương MVP Android. Đây là ước lượng, chưa có bằng chứng đo trên dự án này.
- **Rủi ro lớn nhất cần kiểm chứng trước:** ghi âm lâu khi khoá màn hình, và gửi STT khi app chạy nền trên iOS.

## Evidence (fact)
| Hạng mục | Thực tế |
|---|---|
| Compose Multiplatform cho iOS | Bản 1.8.0 (05/2025) được JetBrains công bố **Stable / production-ready** |
| Tài khoản Apple miễn phí | Hồ sơ cài đặt (provisioning profile) hết hạn sau 7 ngày; giới hạn 10 App ID và 3 thiết bị |
| Tài khoản trả phí 99 USD/năm | App cài từ Xcode dùng được khoảng 1 năm; có TestFlight (mỗi bản build dùng 90 ngày) |
| AltStore/SideStore (Apple ID miễn phí) | Vẫn hết hạn 7 ngày, tối đa 3 app; AltStore cần máy tính để làm mới định kỳ |
| Ghi âm nền trên iOS | Được phép nếu bật `UIBackgroundModes = audio` và cấu hình AVAudioSession đúng. Có báo cáo lỗi ghi ra file rỗng khi khoá màn hình lâu nếu cấu hình sai → **phải test trên máy thật** |
| Web app (PWA) | Theo các báo cáo lỗi WebKit, PWA đã "Thêm vào màn hình chính" **mất micro khi chuyển nền hoặc khoá màn hình** |
| Nút trong Trung tâm điều khiển cho app bên thứ ba | Chỉ có từ **iOS 18** (Controls API); iOS 17 không dùng được |
| Build trên đám mây không cần Mac | GitHub Actions có máy macOS: repo public miễn phí; repo private khoảng 0,062 USD/phút (theo trang giá, cần xác nhận lại) |

## Analysis — Top 3 phương án
| # | Phương án | Dùng lại code | Đáp ứng MVP (ghi âm khi khoá màn hình) | Công sức | Bảo trì lâu dài |
|---|---|---|---|---|---|
| **1** | **KMP + Compose Multiplatform** (khuyến nghị) | Lõi ~90%, UI ~70% | Có (ghi âm native iOS qua lớp riêng) | 6–9 tuần | Một codebase, hai nền tảng |
| 2 | Viết lại native Swift/SwiftUI | Chỉ prompt và logic (viết lại) | Có, phù hợp iOS nhất | 10–14 tuần | Hai codebase, gấp đôi công sửa lỗi |
| 3 | Web app (PWA) chạy trong Safari | Thấp (viết lại bằng web) | **Không**: mất micro khi khoá màn hình | 3–5 tuần | Rẻ, nhưng không đạt MVP |

**Những việc phải viết riêng cho iOS (phương án 1):**
1. **Ghi âm:** AVAudioEngine thu PCM → dùng lại VAD ở phần lõi → mã hoá AAC. Cần bật background audio, xử lý khi có cuộc gọi chen ngang, và hiện Live Activity trên màn khoá thay cho thông báo có nút.
2. **Xử lý nền:** iOS không có WorkManager. Hướng xử lý:
   - Gửi STT bằng URLSession background (upload tiếp khi app ở nền).
   - Hoặc xử lý khi người dùng mở app.
   - BGTaskScheduler chỉ cho chạy ngắn, hệ thống tự quyết thời điểm.
3. **Nhắc việc:** UNUserNotificationCenter, tối đa 64 thông báo hẹn trước → đặt lại lịch mỗi lần mở app.
4. **Lối tắt Ghi nhanh:**
   - Widget màn hình chính và màn khoá: viết bằng WidgetKit (Swift).
   - Shortcuts / App Intents, kết hợp Back Tap (gõ hai lần vào lưng máy).
   - Không có ô trong Trung tâm điều khiển trên iOS 17.
5. **Hạ tầng dữ liệu:** Room có bản đa nền tảng, hoặc SQLDelight; Keychain lưu API key; Files app hoặc iCloud Drive cho sao lưu (định dạng `.zip` giữ nguyên, dùng chung giữa Android và iPhone).
6. **Port phần lõi:** thay `java.time` bằng kotlinx-datetime, `org.json` bằng kotlinx-serialization, `HttpURLConnection` bằng Ktor, `java.util.zip` bằng thư viện zip đa nền tảng.

## Recommendation
Chọn **phương án 1 (KMP)**, triển khai theo cổng quyết định để không tốn tiền/công trước khi rủi ro chính được kiểm chứng:

| Bước | Nội dung | Điều kiện qua cổng |
|---|---|---|
| 0 (2–4 ngày, không tốn phí) | Chuyển `:core` sang KMP; Android vẫn chạy như cũ, CI vẫn xanh | Toàn bộ test hiện có pass |
| 1 (1 tuần, mua tài khoản 99 USD) | PoC iOS: ghi âm có VAD, khoá màn hình 30 phút, gửi Gemini; build bằng GitHub Actions macOS → TestFlight | Ghi đủ 30 phút khoá màn hình trên iPhone, không mất đoạn |
| 2 (3–5 tuần) | UI dùng chung, Thư ký, nhắc việc, sao lưu/khôi phục chung định dạng với Android | Bộ test luồng chính pass trên iOS Simulator |
| 3 (1–2 tuần) | Widget, Shortcuts, Live Activity | Dùng thật 1 tuần |

**Chi phí:** 99 USD/năm + phút build macOS (0 nếu repo public; vài USD/tháng nếu private). **Không cần mua Mac** nếu build hoàn toàn trên CI; nhưng một Mac (kể cả mượn) giúp debug ghi âm nhanh hơn nhiều.

## Unknown / Assumption
- Chưa đo độ ổn định ghi âm nền dài trên iOS 17 → đó là lý do có Bước 1.
- Ước lượng tuần công dựa trên kích thước code hiện tại (~12,8k dòng Kotlin), chưa có số liệu thực.
- Giá runner macOS lấy từ trang tài liệu giá của GitHub; cần xác nhận trên trang billing của tài khoản.

## Next Actions
1. Quyết định: có mua Apple Developer Program 99 USD/năm không (Yes/No).
2. Nếu Yes → làm Bước 0 ngay (không phụ thuộc Apple), song song đăng ký tài khoản.

## Sources
- [JetBrains — Compose Multiplatform 1.8.0: iOS Stable](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)
- [Apple — Choosing a Membership](https://developer.apple.com/support/compare-memberships)
- [AltStore FAQ](https://faq.altstore.io/altstore-world/your-altstore)
- [Apple — AVAudioSession record category](https://developer.apple.com/tutorials/data/documentation/avfaudio/avaudiosession/category-swift.struct/record.md)
- [Titanium bug — blank background recording when locked](https://jira-archive.titaniumsdk.com:443/TIMOB-24973)
- [WebKit bug 239602 — audio killed in background for non-Safari/PWA](https://bugs.webkit.org/show_bug.cgi?id=239602)
- [MacStories — iOS 18 Control Center (Controls API)](https://www.macstories.net/stories/ios-and-ipados-18-the-macstories-review/4/)
- [GitHub — Actions runner pricing](https://docs.github.com/en/enterprise-server@3.17/billing/reference/actions-runner-pricing)
