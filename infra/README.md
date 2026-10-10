# Triển khai "Thư ký" lên Google (Terraform + một lệnh)

Tài liệu này là **sổ tay từ máy trắng đến app chạy trên iPhone**: cài công cụ, đăng nhập, cấu hình, deploy, nối tên miền riêng, cập nhật về sau, và các lỗi thường gặp. Kiến trúc và bảo mật xem [`docs/web-secretary.md`](../docs/web-secretary.md).

**Cách đọc:** làm lần lượt từ Phần 1. Mọi bước **bắt buộc làm bằng tay** (Terraform không làm thay được) được đóng khung như sau:

> ### ⚠️ LÀM BẰNG TAY
> (nội dung)

Mọi thứ chạy **trên PC của bạn**. Cấu hình (`terraform.tfvars`) và trạng thái (`terraform.tfstate`, có chứa khoá Gemini) **không bao giờ được commit** (repo là public; đã có trong `.gitignore`).

---

## Mục lục

1. [Cần chuẩn bị](#1-cần-chuẩn-bị)
2. [Cài công cụ](#2-cài-công-cụ)
3. [Đăng nhập: có HAI loại đăng nhập khác nhau](#3-đăng-nhập-có-hai-loại-đăng-nhập-khác-nhau)
4. [Cấu hình `terraform.tfvars`](#4-cấu-hình-terraformtfvars)
5. [Chạy deploy](#5-chạy-deploy)
6. [Bước tay bắt buộc: bật đăng nhập Google](#6-bước-tay-bắt-buộc-bật-đăng-nhập-google)
7. [Cài lên iPhone](#7-cài-lên-iphone)
8. [Nối tên miền riêng (CNAME / TXT / A)](#8-nối-tên-miền-riêng-cname--txt--a)
9. [Việc thường làm sau khi đã chạy](#9-việc-thường-làm-sau-khi-đã-chạy)
10. [Kiểm tra sau deploy](#10-kiểm-tra-sau-deploy)
11. [Lỗi thường gặp](#11-lỗi-thường-gặp)
12. [An toàn file trên PC, gỡ cài đặt](#12-an-toàn-file-trên-pc-gỡ-cài-đặt)
13. [Điều chưa được kiểm chứng](#13-điều-chưa-được-kiểm-chứng)

---

## 1. Cần chuẩn bị

| Cần | Chi tiết |
|---|---|
| Một project Google Cloud **đã bật billing** | Chính là project đang tính tiền Gemini của bạn. Ghi lại **Project ID** (không phải tên hiển thị), ví dụ `gen-lang-client-0422114781` |
| Tài khoản Google **Owner** của project đó | Terraform và Firebase CLI chạy bằng tài khoản này |
| Danh sách email Gmail được phép dùng app | Ví dụ email của bạn và của vợ. Chỉ các email này vào được (cứng trong Firestore Rules và Function) |
| (Tuỳ chọn) Một tên miền | Ví dụ `vhub.id.vn`, dùng subdomain `recap.vhub.id.vn` |
| Máy Windows / macOS / Linux, có mạng | Windows: dùng **PowerShell hoặc CMD**, xem [lưu ý Git Bash](#đăng-nhập-firebase-cli-lần-riêng) |

Không cần tài khoản Apple Developer.

---

## 2. Cài công cụ

| Công cụ | Phiên bản | Kiểm tra bằng |
|---|---|---|
| Git | bất kỳ | `git --version` |
| Node.js | **22** | `node --version` (phải hiện `v22.x`) |
| Terraform | **≥ 1.10** | `terraform version` |
| Google Cloud CLI (`gcloud`) | bản mới | `gcloud --version` |

Cài đặt:

- **Terraform:** <https://developer.hashicorp.com/terraform/install>. Windows: tải bản zip, giải nén `terraform.exe` vào một thư mục rồi **thêm thư mục đó vào PATH** (Cài đặt → Environment Variables → Path). Hoặc `winget install Hashicorp.Terraform`.
- **gcloud:** <https://cloud.google.com/sdk/docs/install>. Cài xong **mở terminal mới** để nhận lệnh `gcloud`.
- **Node 22:** <https://nodejs.org> (bản LTS 22) hoặc `nvm`.

Lấy mã nguồn (nhánh đã merge vào `main`; nếu chưa merge thì dùng nhánh của PR):

```bash
git clone https://github.com/hoangvh2/my-recap.git
cd my-recap
```

> **Lưu ý:** sau khi đóng terminal, lệnh `terraform` / `gcloud` "không tìm thấy" gần như luôn là do chưa thêm vào PATH hoặc chưa mở terminal mới.

---

## 3. Đăng nhập: có HAI loại đăng nhập khác nhau

Đây là chỗ hay nhầm nhất: **đăng nhập `gcloud` không dùng được cho Firebase CLI** và ngược lại.

| Công cụ | Đăng nhập bằng | Dùng cho |
|---|---|---|
| Terraform | `gcloud auth application-default login` (ADC) | Tạo hạ tầng (API, Firestore, Function, khoá Gemini…) |
| Firebase CLI | `npx firebase-tools@15.33.0 login` | Publish Firebase Hosting (bước cuối của deploy) |

Cả hai phải dùng **tài khoản Google là Owner của project**.

### Đăng nhập gcloud (cho Terraform)

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project YOUR_PROJECT_ID
gcloud auth application-default set-quota-project YOUR_PROJECT_ID
```

- Lệnh thứ hai mở trình duyệt; đăng nhập đúng tài khoản Owner.
- `set-quota-project` giúp tránh lỗi *"API requires a quota project"* khi Terraform gọi một số API (xem [Lỗi thường gặp](#11-lỗi-thường-gặp)). Chạy thêm cũng không hại.
- `deploy.mjs` tự mở đăng nhập ADC nếu chưa có, nhưng làm trước ở đây sẽ dễ thấy lỗi hơn.

### Đăng nhập Firebase CLI (lần riêng)

> ### ⚠️ LÀM BẰNG TAY
> Mở **PowerShell hoặc CMD** (không dùng Git Bash) và chạy:
> ```bash
> npx firebase-tools@15.33.0 login
> npx firebase-tools@15.33.0 login:list
> ```
> `login:list` phải hiện đúng email Owner của project.
>
> **Vì sao không dùng Git Bash:** Git Bash không phải terminal thật với Node, nên Firebase CLI chuyển sang luồng "dán mã thủ công" rất dễ sai. Script `deploy.mjs` sẽ từ chối chạy bước này trong Git Bash và nhắc bạn.

Nếu `login` mở trình duyệt nhưng báo lỗi/không về terminal: chạy `npx firebase-tools@15.33.0 login --no-localhost`, làm theo hướng dẫn trên màn hình.

---

## 4. Cấu hình `terraform.tfvars`

```bash
cp infra/terraform.tfvars.example infra/terraform.tfvars      # Windows CMD: copy infra\terraform.tfvars.example infra\terraform.tfvars
```

Mở `infra/terraform.tfvars` và điền:

```hcl
project_id = "gen-lang-client-0422114781"      # Project ID, KHÔNG phải tên hiển thị

allowed_emails = [
  "ban@gmail.com",
  "vo@gmail.com",
]

# Điền SAU khi đã làm Phần 8 (nối tên miền). Lần deploy đầu để trống.
# custom_domain = "recap.vhub.id.vn"
```

| Biến | Ý nghĩa | Lưu ý |
|---|---|---|
| `project_id` | Project có billing | Sai ID là nguồn lỗi phổ biến nhất |
| `allowed_emails` | Ai được dùng | Chỉ chữ thường, **không có khoảng trắng thừa**, không để trống. Email Google Workspace cũng được. Đổi danh sách = deploy lại |
| `custom_domain` | Tên miền riêng | Chỉ hostname (`recap.vhub.id.vn`), **không** có `https://`, không có `/` |
| `gemini_api_key` | Dùng khoá có sẵn | Để trống (khuyến nghị): Terraform tạo khoá giới hạn riêng cho Generative Language API và đưa thẳng vào Secret Manager |
| `gemini_model` | Model | Mặc định `gemini-3.5-flash-lite` |
| `enforce_app_check` | Bắt buộc App Check cho Firestore | Mặc định `true`. Nếu app không đọc được dữ liệu sau deploy, đặt `false`, deploy lại, kiểm tra rồi bật lại |
| `billing_account_id`, `monthly_budget`, `budget_currency` | Cảnh báo ngân sách (tuỳ chọn) | `budget_currency` phải đúng đơn vị tiền của billing account |
| `region` | Vùng của Function | **Chỉ hỗ trợ `asia-southeast1`** (cố định trong code và `firebase.json`) |

---

## 5. Chạy deploy

```bash
node infra/deploy.mjs
```

Lần đầu script sẽ: kiểm tra công cụ và `tfvars` → (mở đăng nhập Google nếu chưa có) → cài thư viện, chạy test và build Functions → `terraform init` + `apply` (hỏi xác nhận) → build web bằng giá trị Terraform trả ra → kiểm tra đăng nhập Firebase CLI → publish Hosting.

| Lệnh | Tác dụng |
|---|---|
| `node infra/deploy.mjs` | Chạy đủ, hỏi trước khi Terraform thay đổi |
| `node infra/deploy.mjs --yes` | Không hỏi (dùng cho các lần cập nhật) |
| `node infra/deploy.mjs --plan` | Chỉ xem Terraform sẽ đổi gì, không đổi gì |
| `node infra/deploy.mjs --skip-web` | Chỉ phần Terraform (Function, rules, allowlist) |
| `node infra/deploy.mjs --skip-terraform` | Chỉ build và publish web (dùng khi Terraform đã xong mà Hosting lỗi) |

Lần đầu mất khoảng **10–15 phút** (bật API, tạo Firestore, build Function). Terraform **chạy lại an toàn**: lỗi giữa chừng thì sửa rồi chạy lại cùng lệnh.

> **Nếu lần đầu báo lỗi về API "chưa bật" hoặc quyền service account (403):** đợi 2–3 phút rồi chạy lại. Việc bật API và gán quyền cần thời gian lan truyền.

Khi xong, script in địa chỉ app và nhắc bước tay ở Phần 6.

---

## 6. Bước tay bắt buộc: bật đăng nhập Google

> ### ⚠️ LÀM BẰNG TAY (chỉ lần đầu, ≈ 1 phút)
> 1. Mở **Firebase Console → Authentication**: `https://console.firebase.google.com/project/YOUR_PROJECT_ID/authentication/providers`
> 2. **Get started** → chọn **Google** → **Enable** → chọn email hỗ trợ → **Save**.
> 3. Giữ **mọi phương thức khác ở trạng thái tắt**.
>
> Terraform không tạo được OAuth client của Google Sign-In. Làm bằng nút này thì Google tự đăng ký đúng địa chỉ chuyển hướng (`https://<project>.firebaseapp.com/__/auth/handler`).

Chưa làm bước này thì nút "Đăng nhập bằng Google" sẽ báo lỗi.

---

## 7. Cài lên iPhone

1. Mở **Safari** (phải là Safari, không phải Chrome) → vào `https://<project>.firebaseapp.com` (hoặc tên miền riêng nếu đã làm Phần 8).
2. Nút **Chia sẻ** → **Thêm vào Màn hình chính**.
3. Mở app từ biểu tượng trên màn hình chính → **Đăng nhập bằng Google** bằng email nằm trong `allowed_emails`.
4. Cho phép **micro** khi được hỏi.

Email không có trong danh sách sẽ thấy màn hình "Chưa được cấp quyền" và không đọc được gì.

> **Quan trọng:** nếu sau này đổi sang tên miền riêng, phải **xoá app khỏi màn hình chính và thêm lại** từ địa chỉ mới (xem Phần 8).

---

## 8. Nối tên miền riêng (CNAME / TXT / A)

Ví dụ dưới đây dùng subdomain `recap.vhub.id.vn`. App đăng nhập theo **một host duy nhất** (Safari/iOS yêu cầu host đăng nhập trùng host của app), nên phải làm **đủ 4 bước theo đúng thứ tự**; chỉ trỏ DNS là chưa đủ (đây là lý do `recap.vhub.id.vn` vẫn bị chuyển về `*.firebaseapp.com` nếu thiếu Bước D).

### Hiểu nhanh các loại bản ghi DNS

| Loại | Dùng làm gì | Ví dụ |
|---|---|---|
| **TXT** | **Xác minh bạn sở hữu tên miền.** Chỉ là một dòng chữ, không ảnh hưởng truy cập. Giữ vĩnh viễn, đừng xoá | Name `recap` (hoặc tên Firebase chỉ định) → giá trị `firebase=...` |
| **A** | Trỏ tên về **địa chỉ IP** của Firebase Hosting | Name `recap` → `199.36.158.100` |
| **CNAME** | Trỏ tên về **một tên khác** (bí danh) | Name `recap` → `<project>.web.app` |

Quy tắc quan trọng:

- **Làm đúng những gì Firebase Console hiện ra.** Nó quyết định bạn cần TXT, A hay CNAME cho tên miền của bạn; giá trị (IP, chuỗi `firebase=…`) lấy từ Console, đừng tự nghĩ ra.
- **Một tên không được vừa có CNAME vừa có bản ghi khác** (A, TXT…) cùng tên. Nếu nhà cung cấp DNS từ chối lưu, đó thường là lý do: dùng **hoặc** CNAME **hoặc** (A + TXT) cho `recap`, theo đúng cách Console hướng dẫn.
- **Dấu chấm cuối (`.`):** có panel DNS yêu cầu viết tên đầy đủ kết thúc bằng dấu chấm (`recap.vhub.id.vn.`), panel khác chỉ cần phần đầu (`recap`). Nếu bản ghi lưu xong mà tra cứu thấy `recap.vhub.id.vn.vhub.id.vn`, nghĩa là bạn đã viết thừa tên miền; sửa lại.
- **Không dùng proxy** (ví dụ "đám mây cam" của Cloudflare) cho bản ghi này: để chế độ **DNS only**, nếu không Firebase không cấp được chứng chỉ SSL.
- **Chờ lan truyền:** từ vài phút đến vài giờ (tối đa 24–48 giờ tuỳ nhà cung cấp). SSL tự cấp sau khi bản ghi đúng. Kiểm tra bằng `nslookup recap.vhub.id.vn` (Windows/macOS/Linux đều có) hoặc <https://dnschecker.org>.
- Có thể có nhiều subdomain cho nhiều web khác (`blog.vhub.id.vn`…), mỗi cái một bản ghi riêng; chỉ `recap` gắn với app này.

### Bước A. Kết nối tên miền với Firebase Hosting

> ### ⚠️ LÀM BẰNG TAY
> 1. **Firebase Console → Hosting → Add custom domain** → nhập `recap.vhub.id.vn` → Continue.
> 2. Console hiện các bản ghi cần thêm (TXT xác minh và A hoặc CNAME). Mở trang quản lý DNS của nơi bạn mua tên miền (với `.id.vn` là nhà đăng ký bạn mua) và **thêm đúng các bản ghi đó**, trường *Name/Host* chỉ là `recap` (hoặc theo cách panel yêu cầu, xem mục dấu chấm cuối).
> 3. Quay lại Firebase, bấm **Verify**. Trạng thái chuyển **Pending → Connected** (vài phút đến vài giờ).

Xong bước này mở `https://recap.vhub.id.vn` sẽ thấy app nhưng **vẫn bị chuyển về `*.firebaseapp.com`** cho tới khi làm xong Bước C và D.

### Bước B. Cho phép đăng nhập Google từ host mới

> ### ⚠️ LÀM BẰNG TAY (2 chỗ; thiếu một trong hai là lỗi đăng nhập)
> **B1. Firebase Console → Authentication → Settings → Authorized domains → Add domain** → `recap.vhub.id.vn`.
>
> **B2. Google Cloud Console → APIs & Services → Credentials** → mở OAuth client **"Web client (auto created by Google Service)"** rồi thêm:
> - *Authorized JavaScript origins*: `https://recap.vhub.id.vn`
> - *Authorized redirect URIs*: `https://recap.vhub.id.vn/__/auth/handler`
>
> → **Save**. Thay đổi OAuth có thể mất vài phút mới có hiệu lực.
>
> Giữ nguyên các mục cũ của `*.firebaseapp.com` (để địa chỉ cũ vẫn dùng được). Mục `localhost` có sẵn thì để yên.

### Bước C. Khai báo trong `terraform.tfvars`

```hcl
custom_domain = "recap.vhub.id.vn"
```

### Bước D. Deploy lại để app đổi host đăng nhập

```bash
node infra/deploy.mjs --yes
```

Lệnh này: đổi host đăng nhập của app sang tên miền riêng, thêm tên miền vào danh sách CORS của các Function, thêm vào khoá reCAPTCHA (App Check), rồi publish lại Hosting.

> **Đây là bước hay bị bỏ sót.** App có một đoạn kiểm tra: nếu bạn mở app ở host khác với host đăng nhập đã build, nó chuyển về host đăng nhập (để tránh lỗi `redirect_uri` của Google). Chưa deploy với `custom_domain` thì host đăng nhập vẫn là `*.firebaseapp.com`, nên mọi host khác đều bị chuyển về đó.

### Bước E. Cài lại trên iPhone

Xoá biểu tượng app cũ khỏi Màn hình chính, mở Safari vào `https://recap.vhub.id.vn`, **Thêm vào Màn hình chính** lại và đăng nhập. Địa chỉ cũ (`*.firebaseapp.com`) vẫn mở được và tự chuyển sang tên miền mới.

---

## 9. Việc thường làm sau khi đã chạy

| Việc | Cách làm |
|---|---|
| Cập nhật code (có tính năng mới) | `git pull` → `node infra/deploy.mjs --yes` |
| Thêm / bớt email được dùng | Sửa `allowed_emails` trong `terraform.tfvars` → `node infra/deploy.mjs --yes --skip-web` (rules và Function cùng cập nhật). Người bị bỏ ra sẽ mất quyền ngay |
| Đổi model Gemini | `gemini_model = "..."` trong tfvars → deploy lại |
| Xoay khoá Gemini | `cd infra && terraform apply -replace='google_apikeys_key.gemini[0]'` (chưa thử thật) |
| Chỉ xem thay đổi, không áp dụng | `node infra/deploy.mjs --plan` |
| Hosting lỗi nhưng Terraform đã xong | Sửa đăng nhập Firebase CLI rồi `node infra/deploy.mjs --skip-terraform` |
| Lịch iPhone tự cập nhật | Trong app: **Hôm nay → ⚙ Cài đặt → Lịch trên điện thoại → Bật** (cần Hosting đã deploy, xem Phần 10) |

---

## 10. Kiểm tra sau deploy

Làm lần lượt; mục nào lỗi thì xem [Phần 11](#11-lỗi-thường-gặp).

- [ ] Mở địa chỉ app trên máy tính: thấy màn hình đăng nhập, **không** có lỗi trong Console của trình duyệt (F12 → Console, không dòng nào "Refused to…").
- [ ] Đăng nhập bằng email trong danh sách: vào được màn hình "Hôm nay".
- [ ] Đăng nhập bằng một email **không** có trong danh sách: thấy "Chưa được cấp quyền".
- [ ] Gõ ghi chú "mai 3 giờ chiều họp anh Nam": ra bản nháp, bấm **Lưu tất cả**.
- [ ] Thu âm một câu bằng micro (trên iPhone thật).
- [ ] Thêm một khách và một license sắp hết hạn: xuất hiện ở **Hôm nay → Gia hạn cần xử lý**.
- [ ] Settings → Lịch trên điện thoại → Bật → mở địa chỉ liên kết trong trình duyệt: tải được file `.ics` (nếu `404` xem mục lỗi `calendar`).
- [ ] Với tên miền riêng: `https://recap.vhub.id.vn` **ở nguyên** địa chỉ đó (không bị chuyển) và đăng nhập được.

---

## 11. Lỗi thường gặp

### Công cụ và đăng nhập

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `"terraform" not found` / `"gcloud" not found` | Chưa cài hoặc chưa vào PATH / chưa mở terminal mới | Cài lại, thêm PATH, mở terminal mới |
| `Not signed in to the Firebase CLI` (hoặc *Failed to authenticate*) dù đã `gcloud` login nhiều lần | **Hai đăng nhập khác nhau** (Phần 3) | `npx firebase-tools@15.33.0 login` rồi `login:list` |
| Firebase `login` treo / dán mã không ăn trong Git Bash | Git Bash không phải terminal thật | Dùng PowerShell hoặc CMD |
| Firebase CLI báo không liệt kê được Hosting sites (403 / permission) | Tài khoản đăng nhập Firebase CLI không phải Owner project, hoặc Hosting API chưa bật | `login:list` xem đúng tài khoản chưa; Terraform bật API này, chạy lại Terraform nếu cần |
| Terraform: `could not find default credentials` | Chưa có đăng nhập ADC | `gcloud auth application-default login` |
| Terraform: `API ... requires a quota project` / `Cloud Resource Manager API has not been used` | Thiếu project quota hoặc API chưa bật lần đầu | `gcloud auth application-default set-quota-project YOUR_PROJECT_ID`, đợi 2–3 phút, chạy lại |

### Terraform

| Triệu chứng | Cách xử lý |
|---|---|
| `allowed_emails` bị báo không hợp lệ | Có khoảng trắng thừa hoặc ký tự lạ trong email; chỉ chữ thường, không để trống |
| `Only asia-southeast1 is supported` | Xoá dòng `region` trong tfvars (mặc định đúng) |
| `Error 403 ... PERMISSION_DENIED` ngay lần chạy đầu | Quyền / API chưa lan truyền: đợi vài phút, chạy lại cùng lệnh |
| `Error 409: already exists` | Đã tạo ở lần chạy trước bị ngắt: chạy lại; nếu vẫn lỗi gửi nguyên văn lỗi cho người hỗ trợ (đừng xoá tay tài nguyên) |
| `Error acquiring the state lock` | Một lần chạy trước bị ngắt: đảm bảo không còn tiến trình Terraform nào đang chạy rồi `terraform force-unlock <ID>` trong thư mục `infra` |
| Mất file `terraform.tfstate` | Terraform không còn biết tài nguyên đã tạo (phải `terraform import` lại). **Hãy sao lưu file này** (Phần 12) |
| Muốn xoá hết nhưng Firestore không xoá được | Firestore bật chống xoá có chủ đích; xem Phần 12 |

### Đăng nhập trong app

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Bấm đăng nhập báo lỗi ngay, hoặc "Tên miền này chưa được khai báo trong Firebase Authentication" (`auth/unauthorized-domain`) | Chưa thêm host vào Authorized domains | Phần 8, Bước B1 |
| Google báo `redirect_uri_mismatch` | Chưa thêm origin / redirect URI vào OAuth client, hoặc chưa lưu / chưa lan truyền | Phần 8, Bước B2; đợi vài phút |
| Đăng nhập báo lỗi vì chưa bật Google | Chưa làm Phần 6 | Phần 6 |
| Mở `https://recap.vhub.id.vn` bị **chuyển về `*.firebaseapp.com`** | Chưa đặt `custom_domain` và chưa deploy lại | Phần 8, Bước C + D |
| Đăng nhập xong báo **"Chưa được cấp quyền"** | Email không nằm trong `allowed_emails`, hoặc bạn thêm email nhưng chưa deploy lại | Sửa tfvars, `node infra/deploy.mjs --yes --skip-web` |
| Đăng nhập được nhưng dữ liệu không tải ("Không tải được dữ liệu") | App Check chặn Firestore (chưa thử trên Google thật) | Đặt `enforce_app_check = false`, deploy lại, kiểm tra reCAPTCHA / App Check, rồi bật lại |
| Trên iPhone bị đăng xuất sau một thời gian | iOS dọn dữ liệu đăng nhập của web app lâu không dùng | Đăng nhập lại; dữ liệu vẫn an toàn trên Firestore |

### Tên miền riêng

| Triệu chứng | Cách xử lý |
|---|---|
| Firebase mãi ở trạng thái *Pending* | Chờ DNS lan truyền (tới 24–48 giờ); kiểm tra bằng `nslookup`; xem bản ghi có đúng giá trị Console hiện không; tắt proxy (DNS only) |
| DNS panel không cho lưu CNAME | Cùng tên đang có A / TXT: chỉ dùng một kiểu theo hướng dẫn của Console |
| Trình duyệt báo chứng chỉ SSL không hợp lệ | Firebase chưa cấp xong chứng chỉ (đợi) hoặc DNS đang qua proxy |
| Địa chỉ `recap.vhub.id.vn.vhub.id.vn` | Viết thừa tên miền ở ô Name: chỉ cần `recap` (hoặc đầy đủ kèm dấu chấm cuối tuỳ panel) |

### Ghi nhanh, lịch, nhập Excel

| Triệu chứng | Cách xử lý |
|---|---|
| Ghi nhanh báo "Ứng dụng chưa được xác thực (App Check)" | Tải lại trang rồi thử lại; nếu vẫn lỗi kiểm tra khoá reCAPTCHA đã có tên miền hiện tại (deploy lại sau khi đổi `custom_domain`) |
| Ghi nhanh báo lỗi CORS / không gọi được | Host hiện tại chưa nằm trong CORS của Function: đặt đúng `custom_domain` và deploy lại |
| "Hôm nay đã dùng hết lượt ghi nhanh" | Giới hạn 60 lần/ngày/người, đặt lại lúc 00:00 UTC (07:00 giờ VN) |
| Liên kết lịch (`/calendar/...`) trả **404** | (1) Link đã bị đổi hoặc tắt; (2) Hosting chưa deploy lại sau khi cập nhật code: chạy `node infra/deploy.mjs --yes` (cần cả Terraform để tạo hai Function `calendarlink`, `calendarfeed`); (3) mở bằng host khác với host trong liên kết |
| Lịch iPhone cập nhật chậm | iPhone tự quyết thời gian làm mới (thường vài giờ). Cần ngay: Cài đặt → xuất `.ics` một lần |
| Nhập Excel báo "Không tìm thấy cột" | Tên cột trong file khác tên cột trong app: sửa ở **Cài đặt → Tên cột khi nhập từ Excel** hoặc tải **File Excel mẫu** |
| Chọn file `.xlsx` bị từ chối | App chưa đọc trực tiếp `.xlsx`: trong Excel chọn tất cả ô → Sao chép → Dán vào ô trong app; hoặc "Lưu thành CSV UTF-8" |
| Ghi âm không được trên iPhone | Cho phép micro cho Safari / app; luôn có nút **Gõ ghi chú** làm dự phòng |

---

## 12. An toàn file trên PC, gỡ cài đặt

- `terraform.tfstate` chứa **khoá Gemini dạng chữ thường** và email. **Không commit**, không để trong thư mục đồng bộ Dropbox / Drive không mã hoá, nên bật mã hoá ổ đĩa, và **sao lưu ở nơi riêng tư** (mất file là mất khả năng quản lý tài nguyên qua Terraform).
- `terraform.tfvars` chứa email: đã bị `.gitignore`. `infra/.terraform.lock.hcl` cũng không commit.
- Khoá Gemini chỉ gọi được Generative Language API nhưng không giới hạn theo IP (Cloud Functions không có IP cố định): đừng để lộ file state.
- Liên kết lịch bí mật cũng là một "chìa khoá": ai có địa chỉ đó xem được việc và mốc gia hạn (không có SĐT/email). Lộ thì **Đổi liên kết** trong Cài đặt.

**Gỡ cài đặt:** Firestore bị khoá xoá có chủ đích (để không mất dữ liệu do nhầm). Muốn xoá hết dữ liệu phải tắt chống xoá trước, rồi mới `terraform destroy`. Đừng làm nếu chưa sao lưu (app có nút **Sao lưu dữ liệu (JSON)** ở Cài đặt).

---

## 13. Điều chưa được kiểm chứng

Đã kiểm: `terraform validate`, `plan` offline, chạy `apply` và Hosting thật cho bản thư ký ban đầu. **Chưa kiểm trên Google thật** (cần chạy lần đầu để biết):

- Hai Function mới `calendarlink`, `calendarfeed` và quy tắc chuyển tiếp `/calendar/**` của Hosting sang Cloud Run.
- Đăng ký lịch `webcal://` trên iPhone thật và tần suất cập nhật.
- Chất lượng Gemini thật với ghi chú bán hàng.
- Đăng nhập Google trong app đã thêm vào Màn hình chính của iOS với tên miền riêng.

Nếu gặp lỗi ở những mục này, ghi lại nguyên văn thông báo lỗi (che email) để xử lý; Terraform và deploy đều chạy lại an toàn.
