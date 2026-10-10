# Bản web "Thư ký" cho iPhone (PWA + Firebase)

Mục đích: vợ dùng iPhone 12 chỉ cần chức năng **Thư ký** (ghi nhanh việc, lịch hẹn, chi tiêu, ghi chú), không cần ghi âm phỏng vấn/họp. Không cần tài khoản Apple Developer: cài bằng Safari → Chia sẻ → **Thêm vào Màn hình chính**.

Mức hoàn thành: **code + test tự động xong; chưa deploy và chưa thử trên iPhone thật** (xem mục [Chưa kiểm chứng](#chưa-kiểm-chứng)).

## Kiến trúc

```
iPhone (PWA, Preact)
  │  Đăng nhập Google (Firebase Auth, redirect, authDomain = chính tên miền app)
  ├─ Firestore  ← đọc/ghi trực tiếp, bị Security Rules khoá theo uid + allowlist email
  └─ Cloud Function `capture` (asia-southeast1)
        ├─ App Check (token dùng 1 lần) → đăng nhập → email nằm trong allowlist → validate → quota 60/ngày
        ├─ Gemini (khoá lấy từ Secret Manager, gửi bằng header, không bao giờ tới trình duyệt)
        └─ ghi bản nháp vào Firestore của chính người gọi
```

- Dữ liệu: `users/{uid}/items`, `users/{uid}/captures` trên Firestore. **Không có bản sao trong trình duyệt** (Firestore chạy cache bộ nhớ; E2E kiểm tra IndexedDB/localStorage chỉ còn token đăng nhập của Firebase).
- Audio ghi ở máy, đổi sang WAV 16 kHz, gửi cho Function rồi bỏ; không lưu.
- Dữ liệu của hai người hoàn toàn tách nhau, và **tách khỏi app Android** (Android vẫn lưu cục bộ).
- Nhắc việc: web không hẹn giờ thông báo được như Android. Dùng **Menu → Xuất lịch (.ics)** hoặc nút "Thêm vào Lịch" trong từng mục; Lịch iPhone sẽ báo (15 phút trước lịch hẹn, đúng giờ với việc, 8:00 với mục cả ngày).

## Các lớp bảo mật

| Lớp | Cách làm | Kiểm chứng |
|---|---|---|
| Chỉ email được cấp quyền | Allowlist **cố định lúc deploy** (secret `ALLOWED_EMAILS`), áp ở 3 chỗ: Security Rules, Function, và (tuỳ chọn) chặn tạo tài khoản. Rỗng = không ai vào được | Rules test (emulator) + E2E: người lạ nhận 403 trước khi gọi Gemini |
| Phải là Google đã xác minh | Rules/Function kiểm `email_verified` và `sign_in_provider == google.com`; so email không phân biệt hoa thường, không khớp một phần | Rules test |
| Cô lập dữ liệu | Chỉ `users/{uid}` của chính mình; mọi đường dẫn khác bị chặn; bộ đếm quota chỉ Function được ghi | Rules + E2E (owner không đọc được dữ liệu của vợ) |
| Schema chặt | Rules kiểm field, kiểu, độ dài, enum, số tiền; `createdAt` không sửa được; list buộc có `limit` | Rules test (18 case + mutation test) |
| Khoá Gemini | Secret Manager, chỉ service account của Function đọc được; gửi bằng header; log không chứa transcript hay khoá | E2E: fake Gemini nhận khoá đúng 1 chỗ (header); bundle web không chứa khoá |
| Chống lạm dụng | App Check bắt buộc + token dùng 1 lần; quota 60 lần/người/ngày; `maxInstances: 3`; audio ≤ 100 s / 3,6 MB; text ≤ 2.000 ký tự; chỉ 2 thao tác cố định (không phải proxy Gemini tuỳ ý) | Unit + E2E (quota 429, payload sai 400). **App Check chưa test được trên emulator** |
| Prompt injection | Ghi chú được coi là dữ liệu; kết quả chỉ thành *bản nháp* để người dùng xác nhận; text từ model bị cắt độ dài, bỏ ký tự điều khiển, tối đa 20 mục; giao diện không dùng `innerHTML` | Unit test |
| Trình duyệt | CSP chặt (`default-src 'none'`, không inline script), HSTS, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` chỉ cho micro | E2E chạy dưới đúng header production, 0 vi phạm CSP. **CSP với dịch vụ Google thật chưa thử** |
| Supply chain | `npm ci` với lockfile; deploy bằng Workload Identity Federation (không lưu JSON key); môi trường `production` có thể bật duyệt tay | — |

## Thiết lập một lần (≈ 45–60 phút)

> Dùng chính project Google Cloud đã bật billing cho Gemini. Làm bằng Firebase Console + terminal.

1. **Firebase**: tạo/gắn project (Add Firebase to existing Google Cloud project). Gói **Blaze** (đã có billing).
2. **Firestore**: tạo database, location `asia-southeast1` (Native mode).
3. **Authentication → Sign-in method**: bật **Google** *và tắt mọi phương thức khác*. Settings → Authorized domains: chỉ giữ `<project>.web.app` (và `.firebaseapp.com`).
4. **OAuth redirect**: Google Cloud Console → APIs & Services → Credentials → Web client của Firebase → thêm vào *Authorized redirect URIs*: `https://<project>.web.app/__/auth/handler`.
5. **Web app**: Project settings → Add app (Web). Lấy `apiKey`, `appId` (đây là định danh, không phải bí mật).
6. **App Check**: tạo khoá **reCAPTCHA Enterprise** (loại web, domain `<project>.web.app`); App Check → Apps → Register với provider reCAPTCHA Enterprise. Lấy *site key*. **Sau khi app chạy được ≥ 1 lần**, bật **Enforce** cho *Cloud Firestore* (Functions đã tự enforce trong code).
7. **Khoá Gemini** (dùng key thuộc project có billing → không bị dùng để huấn luyện):
   ```bash
   cd tests && npx firebase functions:secrets:set GEMINI_API_KEY --project <project>
   ```
   Dán key khi được hỏi (không nằm trong lịch sử shell, repo hay CI). Xoay khoá = chạy lại lệnh rồi deploy.
8. **Giới hạn khoá & chi phí**: Cloud Console → Credentials → giới hạn API key chỉ cho *Generative Language API*; Billing → Budgets & alerts đặt mức cảnh báo (VD 5 USD/tháng); đặt quota thấp cho Generative Language API.
9. **GitHub** (repo Settings → Secrets and variables → Actions):
   - Secret `ALLOWED_EMAILS` = `email.cua.anh@gmail.com,email.cua.vo@gmail.com` (repo public nên **không** commit email).
   - Variables: `FIREBASE_PROJECT_ID`, `FIREBASE_WEB_API_KEY`, `FIREBASE_WEB_APP_ID`, `APPCHECK_SITE_KEY`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`.
   - Workload Identity Federation cho GitHub (không dùng JSON key): [hướng dẫn](https://github.com/google-github-actions/auth#preferred-direct-workload-identity-federation). Service account cần *Firebase Admin* (hoặc tối thiểu quyền Hosting/Functions/Rules), *Service Account User*, và quyền Secret Manager để gán quyền đọc secret cho Function (có thể phải là *Secret Manager Admin*; nếu deploy báo thiếu quyền thì làm theo thông báo lỗi). Danh sách vai trò tối thiểu chưa được thử thật.
   - (Tuỳ chọn) Environment `production` → Required reviewers: deploy chỉ chạy khi anh bấm duyệt.
10. **Deploy**: merge vào `main` → workflow *Web secretary* chạy test rồi deploy. Lần đầu có thể phải bật API (Cloud Functions, Cloud Build, Artifact Registry, Secret Manager) theo thông báo lỗi.
11. **Cài lên iPhone**: Safari mở `https://<project>.web.app` → Chia sẻ → *Thêm vào Màn hình chính* → mở từ biểu tượng → đăng nhập.

### Tuỳ chọn: chặn cả việc tạo tài khoản lạ
Nâng cấp Authentication lên **Identity Platform** (miễn phí tới 50.000 MAU), rồi đặt Variable `BLOCK_UNLISTED_SIGNUPS=true`. Khi đó email ngoài danh sách không tạo được cả bản ghi đăng nhập. Mặc định tắt vì nếu cấu hình sai có thể khoá cả hai người; Rules + Function vẫn chặn đủ khi tắt.

### Thêm / bớt người dùng
Sửa secret `ALLOWED_EMAILS` rồi deploy lại (rules và Function đều lấy từ đó).

## Chạy test cục bộ

```bash
npm ci --prefix functions && npm ci --prefix web && npm ci --prefix tests
npm test --prefix functions && npm test --prefix web          # unit
echo "owner@example.com,wife@example.com" > config/allowed-emails.local   # email giả để test
node scripts/configure.mjs
cd tests && npx firebase emulators:exec --config ../firebase.json --only firestore --project demo-myrecap-rules "npx vitest run rules"
./run-e2e.sh                                                  # Auth+Firestore+Functions emulator, Gemini giả, Chromium cỡ iPhone 12
```
Cần Java 21 (emulator Firestore) và Chromium (`CHROMIUM=/đường/dẫn/chrome` nếu dùng sẵn).

## Chưa kiểm chứng

| Hạng mục | Vì sao chưa | Cách kiểm |
|---|---|---|
| Đăng nhập Google **trong PWA đã thêm vào Màn hình chính** trên iOS | Emulator không có Google thật; web chỉ test được ở Chromium | Thử trên iPhone thật sau deploy. Cấu hình đã theo [khuyến nghị của Firebase](https://firebase.google.com/docs/auth/web/redirect-best-practices) (redirect + authDomain cùng tên miền). Nếu lỗi: báo lại để chuyển sang phương án khác |
| Ghi âm bằng micro thật trên Safari/iOS (AudioWorklet), có bị hỏi lại quyền micro mỗi lần không | Chỉ test với micro giả của Chromium | Thử 3 lần ghi liên tiếp trên iPhone. Luôn có nút **Gõ ghi chú** làm đường dự phòng |
| App Check (reCAPTCHA Enterprise) với Function và Firestore | Emulator không xác minh App Check | Sau deploy: gọi thử khi chưa bật Enforce, rồi bật và gọi lại |
| CSP với script/iframe Google thật (apis.google.com, reCAPTCHA) | Chỉ test CSP với emulator | Mở web đã deploy, xem Console có dòng "Refused to…" không. Nếu có: bổ sung nguồn tương ứng trong `firebase.json` |
| Chia sẻ `.ics` / sao lưu JSON từ PWA (share sheet iOS) | Không có thiết bị iOS | Thử "Thêm vào Lịch" và "Sao lưu" trên iPhone |
| Tên model Gemini `gemini-3.5-flash-lite` (lấy từ app Android) và chi phí thực tế | Gemini thật chưa được gọi | Đổi bằng param `GEMINI_MODEL` nếu cần; theo dõi chi phí tuần đầu |
| Quota chạy lại ở 00:00 UTC (07:00 giờ VN) | Thiết kế, không phải lỗi | — |

## Giới hạn đã biết

- Cần mạng để dùng (không offline vì không lưu dữ liệu trong trình duyệt).
- Không có thông báo đẩy: nhắc việc dựa vào Lịch iPhone (`.ics`). Web Push trên iOS cần server hẹn giờ và có báo cáo không ổn định; chưa làm.
- Không nhập được bản sao lưu của app Android.
- iOS có thể dọn dữ liệu đăng nhập của web app lâu không dùng → phải đăng nhập lại (dữ liệu vẫn an toàn trên Firestore).
- `npm audit` của `web/` báo 4 lỗi mức cao trong `@grpc/grpc-js` (phụ thuộc gián tiếp của SDK Firebase). Thư viện này **không có trong bundle trình duyệt** (đã kiểm), nên không khai thác được ở web; `functions/` và `tests/` báo 0 lỗi.
