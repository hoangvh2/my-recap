# Bản web "Thư ký" cho iPhone (PWA + Firebase)

Mục đích: người dùng iPhone chỉ cần chức năng **Thư ký** (ghi nhanh việc, lịch hẹn, chi tiêu, ghi chú), không cần ghi âm phỏng vấn/họp. Bản này có thêm bộ công cụ cho người **làm kinh doanh**: quản lý **khách**, **license/bảo hành** và nhắc **gia hạn trước khi hết hạn** (xem [Bán hàng](#bán-hàng-khách-licensebảo-hành-và-gia-hạn)). Không cần tài khoản Apple Developer: cài bằng Safari → Chia sẻ → **Thêm vào Màn hình chính**.

Mức hoàn thành: **code, test tự động và Terraform đã xong; chưa deploy lần nào lên Google thật và chưa thử trên iPhone thật** (xem [Chưa kiểm chứng](#chưa-kiểm-chứng) và [Mức kiểm chứng của phần Terraform](#mức-kiểm-chứng-của-phần-terraform)).

## Kiến trúc

```
iPhone (PWA, Preact)
  │  Đăng nhập Google (Firebase Auth, redirect, authDomain = chính tên miền app)
  ├─ Firestore  ← đọc/ghi trực tiếp, bị Security Rules khoá theo uid + allowlist email
  ├─ Cloud Function `capture` (asia-southeast1, gen2 / Cloud Run)
  │     ├─ App Check (token dùng 1 lần) → đăng nhập → email nằm trong allowlist → validate → quota 60/ngày
  │     ├─ Gemini (khoá lấy từ Secret Manager, gửi bằng header, không bao giờ tới trình duyệt)
  │     └─ ghi bản nháp vào Firestore của chính người gọi
  ├─ Cloud Function `calendarlink` (cùng cổng kiểm tra): tạo / đổi / tắt liên kết lịch bí mật
  └─ Cloud Function `calendarfeed` (công khai, vì app Lịch không đăng nhập được): chỉ trả lịch khi đúng
     token bí mật trong địa chỉ `/calendar/<token>.ics` (Hosting chuyển tiếp `/calendar/**` sang đây)
```

- Dữ liệu: `users/{uid}/items`, `captures`, `customers`, `licenses`, `proposals`, `settings/prefs` trên Firestore. **Không có bản sao trong trình duyệt** (Firestore chạy cache bộ nhớ; E2E kiểm tra IndexedDB/localStorage chỉ còn token đăng nhập của Firebase).
- Audio ghi ở máy, đổi sang WAV 16 kHz, gửi cho Function rồi bỏ; không lưu.
- Dữ liệu của các tài khoản hoàn toàn tách nhau, và **tách khỏi app Android** (Android vẫn lưu cục bộ).
- Nhắc việc: **thông báo qua Pushover** (xem [Thông báo](#thông-báo-pushover)) là kênh chính. Lịch iPhone (đăng ký lịch tự cập nhật hoặc xuất `.ics`) vẫn dùng được; khi đã bật Pushover, lịch mặc định **không đổ chuông** để khỏi nhắc hai lần (bật lại được trong Cài đặt).

## Bán hàng: khách, license/bảo hành và gia hạn

**Vấn đề:** license/bảo hành kéo dài 6 tháng đến vài năm, số lượng nhiều, không thể nhớ hết. Người bán cần hỏi khách có gia hạn không **trước khi hết hạn** để kịp làm hợp đồng.

**Cách tổ chức (mọi thứ liên kết hai chiều, chạm vào tên để sang màn hình kia):**

| Màn hình | Có gì | Liên kết tới |
|---|---|---|
| **Hôm nay** | Ô Ghi nhanh, danh sách "Gia hạn cần xử lý" (mỗi dòng có nút bấm một chạm), việc quá hạn, việc/lịch hôm nay, mốc sắp tới, chờ xem lại | Khách, License |
| **Khách** | Danh sách (khách gấp nhất lên đầu), trang khách: số điện thoại bấm gọi được, license, việc, ghi chú | License, Việc |
| **Gia hạn** | Cần xử lý, đang theo dõi theo giai đoạn, **dự báo 6 tháng**, đã đóng, "khách lâu không liên lạc" | Khách, License |
| **License** | Hạn còn lại, thang tiến độ, **nút một chạm cho bước tiếp theo**, lịch nhắc phía trước, việc liên quan, kỳ trước/kỳ sau | Khách, Việc |
| **Việc** | Việc, lịch hẹn, chi tiêu, ghi chú; mỗi mục hiện chip khách và license | Khách, License |
| Nút micro giữa thanh dưới | Ghi nhanh ở **mọi** màn hình; đang xem khách/license nào thì ghi nhanh là về khách/license đó | — |

**Quy trình gia hạn (tính tự động, không ai phải nhớ tạo việc):** Chưa liên hệ → Đã hỏi khách → Đã gửi báo giá → Đang làm hợp đồng → Đã gia hạn (hoặc Không gia hạn + lý do). Các mốc nhắc **mặc định 90/60/30/14 ngày** trước hạn (license ≤ 6 tháng: 60/30/14/7), sửa được ở **Cài đặt**. Nhắc theo giai đoạn (đã báo giá mà khách im lặng 5 ngày thì nhắc hỏi lại), báo đỏ khi sắp hết hạn mà chưa chốt, "Để sau" để ẩn tạm. Bấm "Khách đã ký" thì app mở kỳ tiếp theo (ngày tính sẵn), nối với kỳ cũ.

**AI hiểu ngữ cảnh bán hàng.** Ghi nhanh được gửi kèm danh sách khách và license đang theo dõi (dưới dạng mã ngắn c1, l1; tối đa 300 khách / 500 license), nên "gọi anh Nam bên ABC, đã gửi báo giá" gắn đúng khách và đề xuất đổi giai đoạn. Mọi tham chiếu do model trả về đều được kiểm tra lại ở server: mã bịa bị bỏ, trùng tên khách thì dùng khách có sẵn, câu mô tả đề xuất do server tự viết. **Mọi thứ vào dạng nháp**; chỉ khi bấm **Lưu tất cả** mới thành thật (từng dòng sửa hoặc bỏ được).

**Bán/gia hạn luôn ra license (không phụ thuộc AI).** Model nhỏ hay xếp "chị Vân muốn mua license Windows Office ngày 12/11/2026" thành một lịch hẹn mà quên phần license. Server tự nhận ra mục nào nói về **mua** hoặc **gia hạn** license/bảo hành/bảo trì/thuê bao cho một khách (mọi loại mục trừ chi tiêu) và tạo **license nháp** cho khách đó, gắn với việc/lịch hẹn: tên sản phẩm lấy từ câu nói ("Windows Office"); ngày hết hạn lấy từ "đến / hết hạn …"; chỉ có ngày mua thì **tạm tính 12 tháng** và ghi rõ trên license để kiểm tra lại. Không có ngày nào thì không tạo (không bịa). Khách đã có license đó (cùng sản phẩm, cùng ngày hết hạn) hoặc AI đã đề xuất cập nhật license cũ thì chỉ gắn vào, không tạo trùng.

**Nhập từ Excel:** dán các ô từ Excel hoặc chọn file CSV; app xem trước, nêu rõ dòng lỗi bằng tiếng Việt, bỏ dòng trùng, rồi mới nhập. Tên cột mặc định (Khách hàng, Người liên hệ, Số điện thoại, Email, Sản phẩm, Loại, Ngày bắt đầu, Ngày hết hạn, Thời hạn (tháng), Giá trị, Số hợp đồng, Ghi chú) **sửa được trong Cài đặt**; app cũng tự nhận các tên thông dụng. Chưa đọc trực tiếp `.xlsx` (cần thêm thư viện); dán hoặc lưu CSV UTF-8.

**Lịch tự cập nhật:** địa chỉ `https://<host>/calendar/<token>.ics` (token ngẫu nhiên 256 bit; server chỉ lưu bản băm để tra cứu). Lịch gồm việc/lịch hẹn đang mở và mọi mốc gia hạn phía trước; **không có số điện thoại hay email**. Đổi liên kết thì liên kết cũ chết; tắt thì lịch ngừng cập nhật. Mỗi lần có người gọi, server kiểm tra liên kết (1 lần đọc nhỏ) nên đổi/tắt liên kết có hiệu lực **ngay**; chỉ phần dựng lịch (đọc toàn bộ việc/license) được cache 10 phút để giới hạn số lần đọc Firestore.

## Lịch: mục nào có thời gian thì lên lịch

- **Một câu nói = một mục**, không tạo cả "việc" lẫn "lịch hẹn" cho cùng một thứ (trùng lặp gây nhiễu, đánh dấu xong một bên thì bên kia vẫn nhắc).
- Tab **Việc → Lịch** hiện **mọi mục có ngày giờ** (việc có hạn + lịch hẹn) theo ngày, việc quá hạn ở trên cùng. Cùng một bản ghi hiện ở hai chỗ (danh sách Việc và Lịch), không phải hai bản sao.
- Server sửa hai lỗi hay gặp của AI: (1) việc/ghi chú có ngày mà là **cuộc hẹn** (họp, hẹn gặp, khám, đón con, sinh nhật, phỏng vấn, demo, chuyến bay…) → chuyển thành **lịch hẹn**; (2) việc AI quên điền ngày mà câu có ngày rõ ràng ("hạn 20/11", "ngày 5 tháng 3") → điền ngày. Việc phải làm (gọi, gửi, nộp…) **vẫn là việc** dù có giờ, để nếu chưa làm thì hiện **quá hạn** chứ không trôi qua như một lịch hẹn.

**Màn Hôm nay theo giờ:** cột giờ bên trái ("Cả ngày" ở đầu, rồi 09:00, 10:30…), vạch đỏ "bây giờ", lịch đã qua làm mờ.

**Khách cần/mua license → luôn ra license nháp trong Gia hạn:** "cần", "muốn", "lấy thêm", "báo giá", "chốt", "mua"… cùng chữ license/bản quyền/phần mềm/bảo hành → mỗi sản phẩm một license ("SIM và CheckPoint" = 2). Ngày tính từ ngày giao/hạn của việc, không có thì từ hôm nay, thời hạn tạm 12 tháng, ghi rõ "tạm tính" trên thẻ nháp để kiểm tra lại.

## Thông báo (Pushover)

**Vì sao Pushover chứ không phải Web Push:** Web Push trên iPhone chỉ chạy khi app đã thêm vào Màn hình chính (iOS 16.4+), không có mức ưu tiên, không nhắc lặp đến khi xác nhận. Pushover (app iOS, trả một lần ~5 USD sau 30 ngày dùng thử) có 4 mức ưu tiên, mức khẩn lặp lại đến khi bấm xác nhận, và vượt được giờ yên lặng.

| Mức | Khi nào | Trên điện thoại |
|---|---|---|
| 🔴 KHẨN (2) | Mục đánh dấu **Quan trọng**, đúng lúc nhắc | Âm "persistent", lặp mỗi 5 phút đến khi bấm Xác nhận (tối đa 1 giờ) |
| 🟠 Cao (1) | Trước lịch hẹn N phút (mặc định 15), đúng giờ của việc; tóm tắt sáng có việc quá hạn / gia hạn đỏ | Kêu cả khi Pushover đang yên lặng |
| 🔵 Thường (0) | Việc chưa xong sau 2 giờ; tóm tắt sáng; 1 giờ trước lịch hẹn quan trọng | Bình thường |
| ⚪ Nhẹ (-1) | Xem trước ngày mai (20:00) | Không chuông |

**Tần suất thông minh:** mỗi lịch hẹn nhắc một lần; mỗi việc nhắc đúng giờ + một lần nhắc lại; tin nhắc lại không gửi trong giờ yên lặng (mặc định 22:00–06:30) nhưng nhắc đúng giờ thì luôn gửi; mục cả ngày vào tóm tắt sáng (tắt tóm tắt thì nhắc riêng lúc 08:00); tóm tắt trống thì không gửi; nhiều nhắc cùng phút gộp thành một tin; tối đa 60 tin/người/ngày.

**Tắt nhắc mà không cần thao tác rắc rối:**
- Tin của **việc** có nút **✅ Đã xong**: chạm là app mở ra và tự đánh dấu xong (có Hoàn tác).
- Tin **KHẨN** tự dừng: lịch hẹn thì dừng lúc đến giờ hẹn (+5 phút), việc thì sau 30 phút. Bấm Xác nhận trong Pushover cũng dừng.
- Đánh dấu xong, bỏ "Quan trọng" hay xoá mục trong app: phút sau server **huỷ** tin khẩn đang lặp trên điện thoại (Pushover receipt).
- **Lịch hẹn** cũng có vòng tròn "Đã xong" (viền đứt nét), và màn sửa có nút **Đã xong**. Lịch lặp lại bấm Đã xong chỉ xong lần này, tự chuyển sang lần sau.

**Nội dung tin:** tiêu đề `🟠 15 phút nữa · Họp anh Nam` (mức + khi nào + việc gì, đọc được ngay trên màn hình khoá); thân tin: giờ bắt đầu–kết thúc và ngày, 📍 địa điểm, 👤 người/khách, 📝 chi tiết, và nút **Mở Thư ký**. Tin nhắc lịch hẹn tự biến mất sau khi lịch đã bắt đầu 15 phút.

**Cách chạy:** Cloud Scheduler gọi function riêng tư `notifytick` mỗi phút. Mỗi lần chỉ đọc các mục trong khoảng thời gian liên quan + các mục lặp lại; mỗi tin có khoá riêng ghi vào `users/{uid}/notifylog` (tự xoá sau 14 ngày) nên **không bao giờ gửi trùng**; lỡ một lần chạy thì lần sau gửi bù (trong 10 phút). Lỗi tạm thời của Pushover thì thử lại phút sau; bị từ chối (key sai) thì dừng. Cài đặt chỉ lưu qua function `notifysettings`, function này **hỏi Pushover xác nhận User Key trước** khi lưu.

**Bật:** chủ app tạo một Application trên pushover.net, đặt `pushover_app_token` trong `infra/terraform.tfvars`, deploy. Mỗi người: cài Pushover, chép **User Key** vào **Cài đặt → Thông báo trên điện thoại**, bấm **Lưu và bật**, rồi **Gửi thử**. Trong Cài đặt iPhone → Thông báo → Pushover, bật **Thông báo nhạy cảm thời gian** để tin quan trọng vẫn đến khi đang bật Tập trung.

**Chi phí ước tính:** 1 job Cloud Scheduler (3 job đầu miễn phí mỗi tài khoản billing); ~43.000 lần gọi function/tháng (trong hạn mức miễn phí 2 triệu); đọc Firestore ~ (số mục trong khoảng + số mục lặp lại + 3) mỗi phút mỗi người, thường < 15.000 lượt/ngày/người (miễn phí 50.000/ngày). Pushover: 10.000 tin/tháng miễn phí cho mỗi Application.

## Giao diện iPhone

- Bố cục chạy hết chiều ngang (E2E kiểm ở 428 × 926, iPhone 12 Pro Max): không màn nào cuộn ngang hay có phần tử tràn ra ngoài.
- **Không phóng to/thu nhỏ được**: viewport `maximum-scale=1, user-scalable=no`, CSS `touch-action: pan-x pan-y` (chặn chụm 2 ngón và chạm đúp), chặn sự kiện `gesturestart` của Safari, và mọi ô nhập ≥ 16px (iOS tự phóng to khi chạm vào ô chữ nhỏ hơn). Đánh đổi: người cần phóng chữ để đọc không phóng được; nếu cần, nên thêm nút cỡ chữ trong Cài đặt thay vì mở lại zoom.

## Các lớp bảo mật

| Lớp | Cách làm | Kiểm chứng |
|---|---|---|
| Chỉ email được cấp quyền | Allowlist **cố định lúc deploy** (`allowed_emails` trong `infra/terraform.tfvars`, không commit), áp ở 2 chỗ: Security Rules và Function. Rỗng = không ai vào được | Rules test (emulator) + E2E: người lạ nhận 403 trước khi gọi Gemini |
| Phải là Google đã xác minh | Rules/Function kiểm `email_verified` và `sign_in_provider == google.com`; so email không phân biệt hoa thường, không khớp một phần | Rules test |
| Cô lập dữ liệu | Chỉ `users/{uid}` của chính mình; mọi đường dẫn khác bị chặn; bộ đếm quota chỉ Function được ghi | Rules + E2E (tài khoản này không đọc được dữ liệu của tài khoản kia) |
| Schema chặt | Rules kiểm field, kiểu, độ dài, enum, số tiền, ngày, các mốc nhắc giảm dần; `createdAt` không sửa được; list buộc có `limit`; `proposals` chỉ Function được ghi, `feeds` và `meta` không client nào đọc/ghi được | Rules test (32 case + mutation test) |
| Liên kết lịch bí mật | Token 256 bit ngẫu nhiên, chỉ băm SHA-256 dùng để tra; sai token/định dạng → 404 rỗng; chỉ GET/HEAD; `Cache-Control: no-store`, `noindex`; lịch không chứa SĐT/email; đổi hoặc tắt bất cứ lúc nào | Unit + E2E (qua proxy mô phỏng Hosting) |
| Ngữ cảnh gửi cho AI | Server kiểm nghiêm `context` (khoá lạ, kiểu sai, quá 300/500 → 400); tên khách bị làm phẳng xuống một dòng trong prompt; model chỉ nhìn thấy mã c1/l1, không thấy id thật | Unit + E2E |
| Khoá Gemini | Terraform tạo khoá giới hạn riêng cho *Generative Language API* trong project có billing, ghi thẳng vào Secret Manager (không cần copy/dán); chỉ service account của Function đọc được; gửi bằng header; log không chứa transcript hay khoá | E2E: fake Gemini nhận khoá đúng 1 chỗ (header); bundle web không chứa khoá |
| Chống lạm dụng | App Check bắt buộc + token dùng 1 lần; quota 60 lần/người/ngày; `maxInstances: 3`; audio ≤ 100 s / 3,6 MB; text ≤ 2.000 ký tự; chỉ 2 thao tác cố định (không phải proxy Gemini tuỳ ý) | Unit + E2E (quota 429, payload sai 400). **App Check chưa test được trên emulator** |
| Prompt injection | Ghi chú được coi là dữ liệu; kết quả chỉ thành *bản nháp* để người dùng xác nhận; text từ model bị cắt độ dài, bỏ ký tự điều khiển, tối đa 20 mục; giao diện không dùng `innerHTML` | Unit test |
| Trình duyệt | CSP chặt (`default-src 'none'`, không inline script), HSTS, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` chỉ cho micro | E2E chạy dưới đúng header production, 0 vi phạm CSP. **CSP với dịch vụ Google thật chưa thử** |
| Supply chain & cấu hình | `npm ci` với lockfile; hạ tầng khai báo bằng Terraform chạy trên máy của người triển khai; `terraform.tfvars` (project, email) và `terraform.tfstate` (có khoá Gemini) bị `.gitignore`, không bao giờ lên repo | `git check-ignore` + CI `terraform validate` |

## Triển khai bằng Terraform (một lệnh)

> **Sổ tay đầy đủ từ máy trắng đến chạy được (cài công cụ, đăng nhập, bước làm tay, tên miền CNAME/TXT/A, lỗi thường gặp): [`infra/README.md`](../infra/README.md).** Phần dưới là bản tóm tắt.

Terraform chạy **trên máy của bạn**; cấu hình và state không commit (đã có trong `infra/.gitignore`).

**Cần cài:** [Terraform ≥ 1.10](https://developer.hashicorp.com/terraform/install), [gcloud CLI](https://cloud.google.com/sdk/docs/install), Node.js 22. Tài khoản Google đang chạy phải là Owner của project (project đã bật billing, chính là project đang tính tiền Gemini).

```bash
cp infra/terraform.tfvars.example infra/terraform.tfvars   # điền project_id và allowed_emails
node infra/deploy.mjs                                       # lần đầu: mở trình duyệt đăng nhập Google, hỏi xác nhận trước khi apply
```

Script làm theo thứ tự: kiểm tra công cụ → đăng nhập ADC (nếu chưa) → build + test function → `terraform apply` → build web bằng giá trị Terraform trả ra → publish Firebase Hosting. Chạy lại bất cứ lúc nào để cập nhật (`--yes` bỏ hỏi, `--plan` chỉ xem thay đổi, `--skip-web` chỉ phần Terraform).

**Hai lần đăng nhập khác nhau.** Terraform dùng đăng nhập của gcloud (`gcloud auth application-default login`), còn bước publish Hosting dùng Firebase CLI, có đăng nhập **riêng** (`npx firebase-tools@15.33.0 login`). Đăng nhập gcloud không dùng được cho Firebase CLI. Trên Windows, chạy lệnh `login` trong PowerShell hoặc CMD: Git Bash không phải terminal thật với Node nên Firebase CLI chuyển sang luồng dán mã thủ công, rất dễ sai. Kiểm tra bằng `npx firebase-tools@15.33.0 login:list`; tài khoản hiện ra phải là tài khoản sở hữu project. Nếu Terraform đã chạy xong và chỉ Hosting lỗi, chạy lại `node infra/deploy.mjs --skip-terraform` để bỏ qua phần Terraform.

**Một bước thủ công duy nhất (≈ 1 phút, lần đầu):** Firebase Console → Authentication → *Get started* → **Google** → Enable → chọn email hỗ trợ → Save. Giữ mọi phương thức khác ở trạng thái tắt. (Terraform không tạo được OAuth client của Google Sign-In; làm bằng nút này thì Google tự đăng ký redirect URI đúng cho `https://<project>.firebaseapp.com`.)

Rồi trên iPhone: Safari mở `https://<project>.firebaseapp.com` → Chia sẻ → *Thêm vào Màn hình chính* → đăng nhập. (Dùng đúng địa chỉ `.firebaseapp.com`; mở bằng `.web.app` sẽ tự chuyển sang địa chỉ này.)

### Terraform tạo gì

| Nhóm | Tài nguyên |
|---|---|
| API | Bật các API cần dùng (Firebase, Firestore, Secret Manager, Cloud Run/Functions, Cloud Build, reCAPTCHA Enterprise, Generative Language…) |
| Firebase | Firebase project, Web app (lấy `apiKey`, `appId` cho bản build) |
| Dữ liệu | Firestore `(default)` ở `asia-southeast1`, **chống xoá**, point-in-time recovery 7 ngày; Security Rules sinh từ `firestore.rules.tmpl` với allowlist của bạn |
| Khoá Gemini | `google_apikeys_key` giới hạn *Generative Language API* → Secret Manager `GEMINI_API_KEY` (hoặc dùng khoá có sẵn qua `gemini_api_key`) |
| Function | Service account riêng (chỉ Firestore + đọc 1 secret + verify App Check + ghi log), service account build riêng, bucket mã nguồn (không công khai), Cloud Function `capture` (tối đa 3 instance, 512 MiB), `calendarlink` (≤ 2 instance) và `calendarfeed` (≤ 2 instance, công khai có chủ đích; hai cái này không nạp khoá Gemini vào môi trường, nhưng dùng chung service account với `capture`) |
| App Check | Khoá reCAPTCHA Enterprise cho 2 tên miền của app, cấu hình App Check, **enforce cho Firestore** (`enforce_app_check`) |
| Chi phí | (tuỳ chọn) Budget báo mail ở 50/90/100% và dự báo |

Hosting không có provider Terraform dùng được cho file tĩnh, nên bước cuối do Firebase CLI (đã nằm trong script).

### Vận hành

| Việc | Cách làm |
|---|---|
| Thêm/bớt email | Sửa `allowed_emails` trong `terraform.tfvars` → `node infra/deploy.mjs --yes --skip-web` (rules và Function cùng cập nhật) |
| Xoay khoá Gemini | `cd infra && terraform apply -replace='google_apikeys_key.gemini[0]'` (chưa thử thật) |
| Đổi model Gemini | `gemini_model = "..."` trong tfvars → deploy lại |
| Cập nhật code | `git pull` → `node infra/deploy.mjs --yes` |
| Gỡ cài đặt | Firestore bị khoá xoá có chủ đích; muốn xoá dữ liệu phải tắt delete protection trước rồi mới `terraform destroy` |

### Dùng tên miền riêng (tuỳ chọn)

App đăng nhập theo **một host duy nhất** (trên Safari/iOS, host đăng nhập phải trùng host của app). Vì vậy khi thêm tên miền riêng, phải đổi host này, không chỉ trỏ DNS. Giả sử bạn có `recap.example.com`:

1. **Kết nối với Hosting.** Firebase Console → Hosting → *Add custom domain* → nhập `recap.example.com`. Console hiện bản ghi **TXT** (xác minh, phải giữ vĩnh viễn) và bản ghi **A** (trỏ về Firebase). Thêm hai bản ghi đó ở nơi quản lý DNS của tên miền (host là `recap`), chờ trạng thái *Connected* (từ vài phút tới vài giờ, SSL tự cấp).
2. **Cho phép đăng nhập từ host mới (làm tay, Terraform không làm được).**
   - Firebase Console → Authentication → Settings → *Authorized domains* → thêm `recap.example.com`.
   - Google Cloud Console → APIs & Services → Credentials → OAuth client *Web client (auto created by Google Service)*: thêm `https://recap.example.com` vào *Authorized JavaScript origins* và `https://recap.example.com/__/auth/handler` vào *Authorized redirect URIs*.
3. **Cập nhật app.** Trong `infra/terraform.tfvars` thêm `custom_domain = "recap.example.com"`, rồi chạy `node infra/deploy.mjs --yes`. Terraform đổi host đăng nhập của app, thêm tên miền vào danh sách CORS của Function và vào khoá reCAPTCHA.
4. **Cài lại trên iPhone.** App đã thêm vào Màn hình chính từ địa chỉ cũ nên xoá đi và thêm lại từ `https://recap.example.com`. Địa chỉ cũ (`*.firebaseapp.com`) vẫn mở được và tự chuyển sang tên miền mới.

Dùng được thêm nhiều subdomain cho các web khác (`blog.example.com`...) miễn mỗi cái có bản ghi DNS riêng; chỉ `recap` gắn với app này.

### Giữ an toàn file Terraform trên PC

- `terraform.tfstate` chứa **khoá Gemini dạng chữ thường** (và email). Đừng commit, đừng để trong thư mục đồng bộ Dropbox/Drive không mã hoá; nên bật mã hoá ổ đĩa. Nếu mất file này Terraform sẽ không còn biết tài nguyên đã tạo (phải `terraform import` lại), nên hãy sao lưu nó ở nơi riêng tư.
- `terraform.tfvars` chứa email: đã bị `.gitignore`. `infra/.terraform.lock.hcl` cũng không commit (tạo lại khi `terraform init`).
- Khoá Gemini chỉ gọi được API Generative Language và không có hạn chế IP (Cloud Functions không có IP cố định), nên đừng làm lộ file state.

## Chạy test cục bộ

```bash
npm ci --prefix functions && npm ci --prefix web && npm ci --prefix tests
npm test --prefix functions && npm test --prefix web          # unit
echo "owner@example.com,member@example.com" > config/allowed-emails.local   # email giả để test
node scripts/configure.mjs
cd tests && npx firebase emulators:exec --config ../firebase.json --only firestore --project demo-myrecap-rules "npx vitest run rules"
./run-e2e.sh                                                  # Auth+Firestore+Functions emulator, Gemini giả, Chromium cỡ màn hình iPhone
```
Cần Java 21 (emulator Firestore) và Chromium (`CHROMIUM=/đường/dẫn/chrome` nếu dùng sẵn).

## Chưa kiểm chứng

| Hạng mục | Vì sao chưa | Cách kiểm |
|---|---|---|
| Đăng nhập Google **trong PWA đã thêm vào Màn hình chính** trên iOS | Emulator không có Google thật; web chỉ test được ở Chromium | Thử trên iPhone thật sau deploy. Cấu hình đã theo [khuyến nghị của Firebase](https://firebase.google.com/docs/auth/web/redirect-best-practices) (redirect + authDomain cùng tên miền). Nếu lỗi: báo lại để chuyển sang phương án khác |
| Ghi âm bằng micro thật trên Safari/iOS (AudioWorklet), có bị hỏi lại quyền micro mỗi lần không | Chỉ test với micro giả của Chromium | Thử 3 lần ghi liên tiếp trên iPhone. Luôn có nút **Gõ ghi chú** làm đường dự phòng |
| App Check (reCAPTCHA Enterprise) với Function và Firestore | Emulator không xác minh App Check; Terraform bật enforce cho Firestore theo mặc định | Nếu app không đọc được dữ liệu sau deploy: đặt `enforce_app_check = false` trong tfvars, deploy lại, kiểm tra reCAPTCHA/App Check rồi bật lại |
| CSP với script/iframe Google thật (apis.google.com, reCAPTCHA) | Chỉ test CSP với emulator | Mở web đã deploy, xem Console có dòng "Refused to…" không. Nếu có: bổ sung nguồn tương ứng trong `firebase.json` |
| Chia sẻ `.ics` / sao lưu JSON từ PWA (share sheet iOS) | Không có thiết bị iOS | Thử "Thêm vào Lịch" và "Sao lưu" trên iPhone |
| Đăng ký lịch `webcal://` trên iPhone và tần suất tự cập nhật | Không có thiết bị iOS; iPhone tự quyết thời gian làm mới (thường vài giờ, bỏ qua `REFRESH-INTERVAL`) | Bấm "Thêm vào Lịch iPhone", sửa một license, xem lịch cập nhật sau bao lâu. Nếu không cập nhật đủ nhanh: dùng nút xuất `.ics` |
| Hosting chuyển `/calendar/**` sang Cloud Run `calendarfeed` | E2E dùng proxy mô phỏng quy tắc `rewrites`, không phải Hosting thật | Sau deploy, mở địa chỉ lịch trong trình duyệt: phải thấy file `.ics`. Nếu 404 từ Hosting: kiểm tra `serviceId`/`region` trong `firebase.json` và đã `deploy` lại Hosting |
| Chất lượng AI trên ghi chú bán hàng thật (tên khách, tiếng Việt, số tiền, "đã gửi báo giá") | Gemini thật chưa được gọi; E2E dùng Gemini giả đã biết câu trả lời | Thử 10–20 câu thật; mọi kết quả đều là nháp nên sai thì sửa/bỏ, không mất dữ liệu |
| Quy mô lớn (hàng nghìn license) | Chỉ test vài chục bản ghi | Giới hạn hiện tại: 1.000 việc, 2.000 khách, 3.000 license mỗi tài khoản |
| Tên model Gemini `gemini-3.5-flash-lite` (lấy từ app Android) và chi phí thực tế | Gemini thật chưa được gọi | Đổi bằng param `GEMINI_MODEL` nếu cần; theo dõi chi phí tuần đầu |
| Gửi tin qua Pushover thật, độ trễ, Time Sensitive / Tập trung trên iOS | Máy chủ Pushover bị chặn trong môi trường build; E2E dùng Pushover giả. Tham số API theo tài liệu Pushover (priority −2…2, `retry`/`expire` cho mức 2, `html=1`, `ttl`, `url_title`) | Sau deploy: **Gửi thử**; tạo một lịch hẹn 20 phút nữa và một việc **Quan trọng** 2 phút nữa, xem tin đến đúng giờ, mức khẩn lặp lại đến khi bấm Xác nhận |
| Cloud Scheduler gọi `notifytick` bằng OIDC | Chưa `apply` thật | `gcloud scheduler jobs describe myrecap-notify-every-minute --location asia-southeast1`; xem log `notifytick` mỗi phút |
| Chặn zoom trên Safari iOS thật | Chỉ kiểm được thuộc tính/sự kiện trong Chromium | Thử chụm 2 ngón và chạm đúp trên iPhone (cả trong Safari và app đã thêm vào Màn hình chính) |
| Quota chạy lại ở 00:00 UTC (07:00 giờ VN) | Thiết kế, không phải lỗi | — |

## Mức kiểm chứng của phần Terraform

| Đã kiểm | Chưa kiểm (cần chạy thật lần đầu) |
|---|---|
| `terraform validate` với schema thật của provider google/google-beta 8.6; `terraform plan` offline sinh đủ 45 tài nguyên, không vòng phụ thuộc; rules sinh ra có allowlist đã chuẩn hoá chữ thường; env/secret của Function đúng; `deploy.mjs` dựng đúng gói function và gọi lệnh đúng thứ tự (bằng shim); CI chạy `fmt`/`validate` | `terraform apply` trên project thật: tên vai trò `roles/firebaseappcheck.tokenVerifier`, quyền của service account build (`cloudbuild.builds.builder`…), khoá API do Terraform tạo có gọi được Gemini không, URL callable `https://<region>-<project>.cloudfunctions.net/capture` của gen2, tài nguyên App Check qua Terraform, site Hosting mặc định có sẵn chưa, Firebase CLI dùng ADC của gcloud. Lỗi nếu có sẽ hiện rõ lúc apply và apply có thể chạy lại an toàn |

## Giới hạn đã biết

- Cần mạng để dùng (không offline vì không lưu dữ liệu trong trình duyệt).
- Thông báo cần app Pushover (trả một lần sau 30 ngày dùng thử) và `pushover_app_token`; chưa bật thì nhắc việc dựa vào Lịch iPhone.
- Chưa đọc trực tiếp file `.xlsx`; dán từ Excel hoặc dùng CSV.
- Chưa có phân quyền theo vai trò hay chia sẻ khách giữa nhiều người: mỗi tài khoản có dữ liệu riêng.
- Xoá khách xoá luôn license và việc liên quan (có xác nhận và nút Hoàn tác; sau khi tắt toast không khôi phục được ngoài sao lưu JSON).
- Không nhập được bản sao lưu của app Android.
- iOS có thể dọn dữ liệu đăng nhập của web app lâu không dùng → phải đăng nhập lại (dữ liệu vẫn an toàn trên Firestore).
- `npm audit` của `web/` báo 4 lỗi mức cao trong `@grpc/grpc-js` (phụ thuộc gián tiếp của SDK Firebase). Thư viện này **không có trong bundle trình duyệt** (đã kiểm), nên không khai thác được ở web; `functions/` và `tests/` báo 0 lỗi.
