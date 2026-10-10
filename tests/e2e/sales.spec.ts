import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { expectNoProblems, idTokenFor, isoIn, OWNER, seed, signIn, tab, textCapture, watchBrowser } from "./helpers";

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png` });
const NOW = Date.now();

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

const customer = (name: string, extra: Record<string, string> = {}) => ({ status: "OPEN", name, createdAt: NOW, updatedAt: NOW, ...extra });
const licence = (customerId: string, product: string, endInDays: number, extra: Record<string, string | number> = {}) => ({
  customerId, status: "OPEN", product, kind: "LICENSE", endDate: isoIn(endInDays), stage: "ACTIVE", stageAt: NOW, createdAt: NOW, updatedAt: NOW, ...extra,
});

async function seedAbc(request: import("@playwright/test").APIRequestContext) {
  const who = await idTokenFor(request, OWNER);
  await seed(request, who, "customers", "cabc", customer("Công ty ABC", { contact: "anh Nam", phone: "0901 234 567" }));
  await seed(request, who, "customers", "cxyz", customer("XYZ Logistics"));
  await seed(request, who, "licenses", "lacc", licence("cabc", "Phần mềm kế toán", 45));
  await seed(request, who, "licenses", "lsrv", licence("cabc", "Bảo hành server", 200, { kind: "WARRANTY" }));
  await seed(request, who, "licenses", "lxyz", licence("cxyz", "Cloud backup", 400));
  return who;
}

test("customer ↔ licence ↔ work are linked both ways, from every side", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);

  // Add a customer by hand: the app opens the new customer's page.
  await tab(page, "Khách");
  await expect(page.getByText("Chưa có khách nào")).toBeVisible();
  await page.getByRole("button", { name: "Thêm khách" }).click();
  await page.getByLabel(/Tên khách/).fill("Công ty ABC");
  await page.getByLabel("Người liên hệ").fill("anh Nam");
  await page.getByLabel("Số điện thoại").fill("0901 234 567");
  await shot(page, "10-customer-form");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Công ty ABC" })).toBeVisible();
  await expect(page.getByRole("link", { name: /0901 234 567/ })).toHaveAttribute("href", "tel:0901234567");

  // Add a licence for that customer: only the product and the term are needed, the end date is worked out.
  await page.getByRole("button", { name: "License", exact: true }).click();
  await page.getByLabel("Sản phẩm").fill("Phần mềm kế toán");
  await page.getByLabel("Ngày bắt đầu").fill(isoIn(-320));
  await page.getByRole("button", { name: "1 năm" }).click();
  await expect(page.getByText(/Ngày hết hạn tự tính/)).toBeVisible();
  await page.getByLabel(/Giá trị/).fill("50 triệu");
  await shot(page, "11-licence-form");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();

  // Licence page: the customer is a chip you can tap, and 45 days to go → the next step is on screen.
  await expect(page.getByRole("heading", { name: "Phần mềm kế toán" })).toBeVisible();
  await expect(page.locator(".hero-card")).toContainText("Còn 4");
  await shot(page, "12-licence");
  await page.locator(".big-links").getByRole("link", { name: "Công ty ABC" }).click();
  await expect(page.getByRole("heading", { name: "Công ty ABC" })).toBeVisible();
  await expect(page.locator(".lic-row", { hasText: "Phần mềm kế toán" })).toBeVisible();

  // A task about this licence, made from the licence page.
  await page.locator(".lic-row", { hasText: "Phần mềm kế toán" }).getByRole("link").first().click();
  await page.getByRole("button", { name: "Việc", exact: true }).click();
  await page.getByLabel("Tiêu đề").fill("Gọi anh Nam hỏi gia hạn");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  const task = page.locator(".row-item", { hasText: "Gọi anh Nam hỏi gia hạn" });
  await expect(task).toBeVisible();

  // … and it is reachable from the customer, from the Việc tab (with both chips), and from Today.
  await page.getByRole("link", { name: "Công ty ABC" }).first().click();
  await expect(page.locator(".row-item", { hasText: "Gọi anh Nam hỏi gia hạn" }).getByRole("link", { name: "Phần mềm kế toán" })).toBeVisible();
  await tab(page, "Việc");
  const inWork = page.locator(".row-item", { hasText: "Gọi anh Nam hỏi gia hạn" });
  await expect(inWork.getByRole("link", { name: "Công ty ABC" })).toBeVisible();
  await inWork.getByRole("link", { name: "Phần mềm kế toán" }).click();
  await expect(page.getByRole("heading", { name: "Phần mềm kế toán" })).toBeVisible();
  await shot(page, "13-licence-with-work");

  // The back button goes back to where we came from, not to a fixed screen.
  await page.getByRole("button", { name: "Quay lại" }).click();
  await expect(page.getByRole("heading", { name: "Việc", exact: true })).toBeVisible();
  expectNoProblems(problems);
});

test("Today lists what is due; one tap moves a renewal forward and can be undone", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedAbc(request);
  await signIn(page, OWNER);
  await expect(page.getByText("Gia hạn cần xử lý")).toBeVisible();
  const nudge = page.locator(".nudge", { hasText: "Phần mềm kế toán" });
  // 45 days to go: the 60-day "send a quote" step is due.
  await expect(nudge).toContainText("Gửi báo giá gia hạn");
  await expect(nudge.getByRole("link", { name: "Công ty ABC", exact: true })).toBeVisible();
  await expect(nudge.getByRole("link", { name: /Gọi/ })).toHaveAttribute("href", "tel:0901234567");
  await expect(page.locator(".nudge", { hasText: "Bảo hành server" })).toHaveCount(0); // 200 days: nothing yet
  await shot(page, "14-today-due");

  await nudge.getByRole("button", { name: "Đã gửi báo giá" }).click();
  await expect(page.getByText("Đã ghi: đã gửi báo giá")).toBeVisible();
  await expect(page.locator(".nudge", { hasText: "Phần mềm kế toán" })).toHaveCount(0); // quoted, nothing else is due yet
  await page.getByRole("button", { name: "Hoàn tác" }).click();
  await expect(page.locator(".nudge", { hasText: "Phần mềm kế toán" })).toBeVisible();

  // "Để sau" hides it.
  await page.locator(".nudge", { hasText: "Phần mềm kế toán" }).getByRole("button", { name: "Để sau" }).click();
  await page.getByRole("button", { name: "3 ngày nữa" }).click();
  await expect(page.locator(".nudge")).toHaveCount(0);
  expectNoProblems(problems);
});

test("finishing a renewal opens the next period; saying no records why", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedAbc(request);
  await signIn(page, OWNER);
  await tab(page, "Gia hạn");
  await expect(page.locator(".tile.hot")).toContainText("1");
  await shot(page, "15-renewals");
  await page.locator(".nudge", { hasText: "Phần mềm kế toán" }).getByRole("link", { name: "Phần mềm kế toán" }).click();

  // Walk the ladder: asked → quote → contract → signed.
  await page.getByRole("button", { name: "Đã gửi báo giá" }).click();
  await expect(page.locator(".big-links")).toContainText("Đã báo giá");
  await page.getByRole("button", { name: "Đã gửi hợp đồng" }).click();
  await expect(page.locator(".big-links")).toContainText("Đang làm hợp đồng");
  await page.getByRole("button", { name: "Khách đã ký, gia hạn xong" }).click();
  await expect(page.getByRole("heading", { name: /Gia hạn xong/ })).toBeVisible();
  await shot(page, "16-renew-sheet");
  await page.getByLabel("Số hợp đồng mới").fill("HD-2027-01");
  await page.getByRole("button", { name: "Lưu kỳ mới" }).click();
  await expect(page.getByText("Đã gia hạn. Kỳ mới đã được theo dõi")).toBeVisible();
  await expect(page.locator(".hero-card")).toContainText("Đã gia hạn");
  await page.getByRole("link", { name: /Xem kỳ mới/ }).click();
  await expect(page.locator(".big-links")).toContainText("Chưa liên hệ");
  await expect(page.locator(".facts")).toContainText("HD-2027-01");
  await expect(page.locator(".facts")).toContainText("Kỳ trước");
  // The old period links forward, the new one links back.
  await page.locator(".facts").getByRole("link").first().click();
  await expect(page.locator(".facts")).toContainText("Kỳ tiếp");

  // The other licence: the customer says no.
  await page.locator(".big-links").getByRole("link", { name: "Công ty ABC" }).click();
  await page.locator(".lic-row", { hasText: "Bảo hành server" }).getByRole("link").first().click();
  await page.getByRole("button", { name: "Không gia hạn" }).click();
  await page.getByRole("radio", { name: "Giá cao" }).click();
  await page.getByRole("button", { name: /Đóng lại/ }).click();
  await expect(page.locator(".hero-card")).toContainText("Lý do: Giá cao");
  await page.getByRole("button", { name: "Mở lại để theo dõi" }).click();
  await expect(page.locator(".big-links")).toContainText("Chưa liên hệ");
  expectNoProblems(problems);
});

test("AI capture: a new customer, licence and task arrive as linked drafts; made-up references are dropped", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedAbc(request);
  await signIn(page, OWNER);
  await textCapture(page, "FAKE_SALES_NEW Công ty Delta mua phần mềm ERP 120 triệu, hết hạn 30/06/2027, mai gửi báo giá");
  const card = page.locator(".draft-card");
  await expect(card).toBeVisible();
  await expect(card.locator("h4", { hasText: "Khách mới" })).toBeVisible();
  await expect(card.locator(".title", { hasText: "Công ty Delta" }).first()).toBeVisible();
  await expect(card.locator(".title", { hasText: "Công ty Delta · Phần mềm ERP" })).toBeVisible();
  await expect(card).toContainText("120.000.000 đ");
  await expect(card.locator(".row-item", { hasText: "Gửi báo giá ERP" }).getByRole("link", { name: "Công ty Delta" })).toBeVisible();
  await shot(page, "17-ai-drafts");
  // Nothing exists yet.
  await tab(page, "Khách");
  await expect(page.getByText("Công ty Delta")).toHaveCount(0);
  await tab(page, "Hôm nay");
  await card.getByRole("button", { name: "Lưu tất cả" }).click();
  await expect(page.locator(".draft-card")).toHaveCount(0);
  await tab(page, "Khách");
  await page.getByRole("link", { name: /Công ty Delta/ }).click();
  await expect(page.locator(".lic-row", { hasText: "Phần mềm ERP" })).toBeVisible();
  await expect(page.locator(".row-item", { hasText: "Gửi báo giá ERP" })).toBeVisible();

  // A reply that points at customers and licences that do not exist cannot attach to anything.
  await tab(page, "Hôm nay");
  await textCapture(page, "FAKE_SALES_BAD gọi cho ai đó");
  const bad = page.locator(".draft-card");
  await expect(bad.locator(".row-item", { hasText: "Việc bịa mã" })).toBeVisible();
  await expect(bad.locator(".row-item", { hasText: "Việc bịa mã" }).locator(".chip")).toHaveCount(0);
  await expect(bad.locator("h4", { hasText: "Cập nhật gia hạn" })).toHaveCount(0);
  expectNoProblems(problems);
});

test("Ghi nhanh from a licence page is about that licence, and its proposal applies in one tap", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedAbc(request);
  await signIn(page, OWNER);
  await tab(page, "Gia hạn");
  await page.getByRole("button", { name: /Đang theo dõi/ }).click();
  await page.locator(".lic-row", { hasText: "Phần mềm kế toán" }).getByRole("link").first().click();
  await page.getByRole("button", { name: "Mở ô ghi nhanh (micro)" }).click();
  await expect(page.locator(".focus-pill")).toContainText("Phần mềm kế toán");
  await textCapture(page, "FAKE_SALES_UPDATE đã gửi báo giá cho anh Nam");

  // The sheet closes; the licence page shows what the AI understood.
  const card = page.locator(".draft-card");
  await expect(card).toBeVisible();
  await expect(card.locator("h4", { hasText: "Cập nhật gia hạn" })).toBeVisible();
  await expect(card).toContainText("Công ty ABC · Phần mềm kế toán");
  await expect(card).toContainText("Đã báo giá");
  const calls = await (await request.get("http://127.0.0.1:8788/")).json();
  const prompt: string = calls.at(-1).userText;
  expect(prompt).toContain("c1 | Công ty ABC | anh Nam");
  expect(prompt).toContain("đang xem license l1");
  expect(prompt).not.toContain("cabc"); // the model sees aliases, never ids
  await shot(page, "18-ai-proposal");
  await card.getByRole("button", { name: "Lưu tất cả" }).click();
  await expect(page.locator(".big-links")).toContainText("Đã báo giá");
  await expect(page.locator(".facts")).toContainText("50.000.000 đ");
  await expect(page.locator(".row-item", { hasText: "Gọi lại khách" })).toBeVisible();
  expectNoProblems(problems);
});

test("importing from Excel: preview, problems named, duplicates skipped", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("link", { name: "nhập danh sách khách và license từ Excel" }).click();
  const rows = [
    "Khách hàng\tNgười liên hệ\tSản phẩm\tNgày hết hạn\tGiá trị",
    "Công ty Alpha\tchị Hoa\tKế toán\t31/12/2026\t50.000.000",
    "Công ty Alpha\t\tBảo hành server\t15/03/2027\t",
    "Công ty Beta\tanh Tùng\tERP\t01/09/2027\t200 triệu",
    "Công ty Beta\t\tERP\t01/09/2027\t",
    "Công ty Lỗi\t\tERP\tabc\t",
  ].join("\n");
  await page.getByPlaceholder(/Dán dữ liệu từ Excel/).fill(rows);
  await expect(page.locator(".tile.ok")).toContainText("3");
  await expect(page.getByText("2 khách mới")).toBeVisible();
  await expect(page.locator(".row-item", { hasText: "Công ty Lỗi" })).toContainText("Ngày hết hạn");
  await shot(page, "19-import-preview");
  await page.getByRole("button", { name: "Nhập 3 license" }).click();
  await expect(page.getByText("Đã nhập 3 license và 2 khách mới")).toBeVisible();
  await tab(page, "Khách");
  await expect(page.locator(".cust-row")).toHaveCount(2);
  await page.getByRole("link", { name: /Công ty Alpha/ }).click();
  await expect(page.locator(".lic-row")).toHaveCount(2);

  // The same sheet again adds nothing.
  await page.goto("/#/nhap");
  await page.getByPlaceholder(/Dán dữ liệu từ Excel/).fill(rows);
  await expect(page.locator(".tile.ok")).toContainText("0");
  await expect(page.getByRole("button", { name: /Nhập 0 license/ })).toBeDisabled();
  expectNoProblems(problems);
});

test("a header the app does not know is explained, and the column names are editable in Settings", async ({ page }) => {
  await signIn(page, OWNER);
  await page.goto("/#/cai-dat");
  await page.locator("details summary", { hasText: "Sửa tên cột" }).click();
  await page.getByLabel("Tên khách").fill("Đơn vị mua");
  await page.getByRole("button", { name: "Lưu cài đặt" }).click();
  await expect(page.getByText("Đã lưu cài đặt")).toBeVisible();
  await page.goto("/#/nhap");
  await page.getByPlaceholder(/Dán dữ liệu từ Excel/).fill("Đơn vị mua\tSản phẩm\tNgày hết hạn\nCông ty Gamma\tERP\t01/01/2028");
  await expect(page.locator(".tile.ok")).toContainText("1");
  await page.getByPlaceholder(/Dán dữ liệu từ Excel/).fill("Tên lạ\tSản phẩm\tNgày hết hạn\nCông ty Gamma\tERP\t01/01/2028");
  await expect(page.getByRole("alert")).toContainText("Không tìm thấy cột");
});

test("reminder milestones can be changed, and invalid ones cannot be saved", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedAbc(request);
  await signIn(page, OWNER);
  await page.goto("/#/cai-dat");
  const normal = page.locator(".ms").first();
  await expect(normal.locator("input").nth(0)).toHaveValue("90");
  await normal.locator("input").nth(1).fill("95"); // not descending
  await expect(page.getByText("Các mốc phải là số nguyên, giảm dần")).toBeVisible();
  await expect(page.getByRole("button", { name: "Lưu cài đặt" })).toBeDisabled();
  await normal.locator("input").nth(0).fill("120");
  await normal.locator("input").nth(1).fill("50");
  await normal.locator("input").nth(2).fill("30");
  await normal.locator("input").nth(3).fill("10");
  await shot(page, "20-settings");
  await page.getByRole("button", { name: "Lưu cài đặt" }).click();
  await expect(page.getByText("Đã lưu cài đặt")).toBeVisible();
  // 45 days left, milestones 120/50/30/10: the quote step (at 50) is still due, the contract step (30) not yet.
  await tab(page, "Hôm nay");
  await expect(page.locator(".nudge", { hasText: "Phần mềm kế toán" })).toContainText("Gửi báo giá gia hạn");
  expectNoProblems(problems);
});

test("calendar subscription: a secret link serves reminders and tasks, can be replaced and switched off", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await seedAbc(request);
  await seed(request, who, "items", "t1", {
    type: "TASK", status: "OPEN", title: "Gửi báo giá cho ABC", details: "", allDay: true, whenAt: NOW + 2 * 86_400_000, customerId: "cabc", createdAt: NOW,
  });
  await signIn(page, OWNER);
  await page.goto("/#/cai-dat");
  await page.getByRole("button", { name: "Bật lịch tự cập nhật" }).click();
  const box = page.locator(".link-box");
  await expect(box).toContainText("/calendar/");
  const url = (await box.textContent())!;
  expect(url).toMatch(/^http:\/\/127\.0\.0\.1:4173\/calendar\/[A-Za-z0-9_-]{43}\.ics$/);
  await expect(page.getByRole("link", { name: /Thêm vào Lịch iPhone/ })).toHaveAttribute("href", url.replace("http://", "webcal://"));
  await shot(page, "21-calendar-link");

  const feed = await request.get(url);
  expect(feed.status()).toBe(200);
  expect(feed.headers()["content-type"]).toContain("text/calendar");
  const text = (await feed.text()).replace(/\r\n /g, "");
  expect(text).toContain("BEGIN:VCALENDAR");
  expect(text).toContain("Việc: Gửi báo giá cho ABC · Công ty ABC");
  expect(text).toContain("Gia hạn · Chốt hợp đồng gia hạn: Công ty ABC (Phần mềm kế toán)");
  expect(text).toContain("UID:lacc-END@myrecap");
  expect(text).not.toContain("0901");

  // A wrong address gives nothing away.
  expect((await request.get(url.replace(/.{5}\.ics$/, "AAAAA.ics"))).status()).toBe(404);
  expect((await request.get("http://127.0.0.1:4173/calendar/abc.ics")).status()).toBe(404);

  // Replace: the old address stops (after the instance's short cache), the new one works.
  await page.getByRole("button", { name: "Đổi liên kết" }).click();
  await page.getByRole("button", { name: "Đổi liên kết" }).last().click();
  await expect(box).not.toHaveText(url);
  const fresh = (await box.textContent())!;
  expect((await request.get(fresh)).status()).toBe(200);

  // Switch off.
  await page.getByRole("button", { name: "Tắt", exact: true }).click();
  await page.getByRole("button", { name: "Tắt", exact: true }).last().click();
  await expect(page.getByRole("button", { name: "Bật lịch tự cập nhật" })).toBeVisible();
  expect((await request.get(fresh)).status()).toBe(404);

  // The one-off export has the same reminders.
  const [dl] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: /Xuất một lần/ }).click()]);
  expect(readFileSync((await dl.path())!, "utf8")).toContain("UID:lacc-END@myrecap");
  expectNoProblems(problems);
});

test("deleting a customer says what goes with it, and can be undone", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await seedAbc(request);
  await seed(request, who, "items", "t1", { type: "TASK", status: "OPEN", title: "Việc của ABC", details: "", allDay: true, whenAt: NOW, customerId: "cabc", licenseId: "lacc", createdAt: NOW });
  await signIn(page, OWNER);
  await tab(page, "Khách");
  await shot(page, "22-customers");
  await page.getByRole("link", { name: /Công ty ABC/ }).click();
  await shot(page, "23-customer");
  await page.getByRole("button", { name: "Sửa thông tin khách" }).click();
  await page.getByRole("button", { name: "Xoá" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Sẽ xoá luôn 2 license và 1 việc/ghi chú liên quan");
  await page.getByRole("button", { name: "Xoá", exact: true }).last().click();
  await expect(page.getByRole("heading", { name: "Khách hàng" })).toBeVisible();
  await expect(page.getByText("Công ty ABC")).toHaveCount(0);
  await page.getByRole("button", { name: "Hoàn tác" }).click();
  await expect(page.getByRole("link", { name: /Công ty ABC/ })).toBeVisible();
  await page.getByRole("link", { name: /Công ty ABC/ }).click();
  await expect(page.locator(".lic-row")).toHaveCount(2);
  await expect(page.locator(".row-item", { hasText: "Việc của ABC" })).toBeVisible();
  expectNoProblems(problems);
});

test("search finds customers, licences and work and jumps to them", async ({ page, request }) => {
  await seedAbc(request);
  await signIn(page, OWNER);
  await page.getByRole("button", { name: "Tìm kiếm" }).click();
  await page.getByPlaceholder(/Tên khách, sản phẩm/).fill("ke toan");
  await expect(page.getByRole("dialog").getByText("Phần mềm kế toán")).toBeVisible();
  await page.getByPlaceholder(/Tên khách, sản phẩm/).fill("xyz");
  await page.getByRole("dialog").getByRole("button", { name: "XYZ Logistics", exact: true }).click();
  await expect(page.getByRole("heading", { name: "XYZ Logistics" })).toBeVisible();
});

test("an agreed renewal with no licence yet becomes a task for that customer, whether or not the customer exists", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  // No customer yet: the AI proposes one, and the task hangs on it.
  await textCapture(page, "FAKE_RENEW_NOTE_NEW anh Khánh đồng ý gia hạn license đến 12/2027");
  let card = page.locator(".draft-card");
  await expect(card.locator("h4", { hasText: "Khách mới" })).toBeVisible();
  const task = card.locator(".row-item", { hasText: "Xử lý gia hạn license cho Khánh" });
  await expect(task).toBeVisible();
  await expect(task.getByRole("link", { name: "Khánh" })).toBeVisible();
  // … and the renewal itself: a draft licence for that customer, ending at the end of December 2027.
  await expect(card.locator("h4", { hasText: "License / bảo hành mới" })).toBeVisible();
  await expect(card.locator(".row-item", { hasText: "Khánh · License" })).toContainText("31/12/2027");
  await expect(task.getByRole("link", { name: "License" })).toBeVisible();
  await card.getByRole("button", { name: "Lưu tất cả" }).click();
  await expect(page.locator(".draft-card")).toHaveCount(0);

  // Said again: same customer and same end date, so no second licence; the task links to the one just saved.
  await textCapture(page, "FAKE_RENEW_NOTE_KNOWN anh Khánh đồng ý gia hạn license đến 12/2027");
  card = page.locator(".draft-card");
  await expect(card.locator("h4", { hasText: "Khách mới" })).toHaveCount(0);
  await expect(card.locator("h4", { hasText: "License / bảo hành mới" })).toHaveCount(0);
  await expect(card.locator(".row-item", { hasText: "Xử lý gia hạn license cho Khánh" })).toBeVisible();
  await shot(page, "24-renewal-task");
  await card.getByRole("button", { name: "Lưu tất cả" }).click();
  await tab(page, "Khách");
  await expect(page.locator(".cust-row")).toHaveCount(1);
  await page.getByRole("link", { name: /Khánh/ }).click();
  await expect(page.locator(".lic-row")).toHaveCount(1);
  await expect(page.locator(".row-item", { hasText: "Xử lý gia hạn license cho Khánh" })).toHaveCount(2);
  expectNoProblems(problems);
});
