import { expect, test, type Page } from "@playwright/test";
import { answerConfirm, expectNoProblems, idTokenFor, OWNER, seed, signIn, tab, watchBrowser } from "./helpers";

// Features that came from the Android app: typed quick add, templates, Rà soát, spending stats, weekly recap.
test.use({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, hasTouch: true, isMobile: true });

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png` });

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
});

const at = (monthsAgo: number, day: number, hour = 12) => {
  const d = new Date();
  return new Date(d.getFullYear(), d.getMonth() - monthsAgo, day, hour).getTime();
};

test("Gõ nhanh: a typed line becomes a task, an appointment or an expense, and templates fill in", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  const box = page.getByLabel("Gõ nhanh một việc, lịch hẹn hoặc khoản chi");

  await box.fill("họp nhóm thứ 5 tuần sau 9h gấp");
  await expect(page.locator(".quick-read")).toContainText("Lịch hẹn");
  await expect(page.locator(".quick-read")).toContainText("Quan trọng");
  await shot(page, "50-quickadd-event");
  await page.getByRole("button", { name: "Thêm", exact: true }).first().click();
  await expect(page.getByText("Đã thêm: họp nhóm")).toBeVisible();
  await expect(box).toHaveValue("");

  await box.fill("cà phê 45k");
  await expect(page.locator(".quick-read")).toContainText("Chi tiêu");
  await expect(page.locator(".quick-read")).toContainText("45.000 đ");
  await expect(page.locator(".quick-read")).toContainText("Ăn uống");
  await shot(page, "51-quickadd-expense");
  await page.locator(".quick-row").getByRole("button", { name: "Thêm" }).click();
  await expect(page.getByText("Đã thêm: cà phê")).toBeVisible();

  // Templates: the empty box offers one line per kind; a line with a blank puts the cursor on it.
  await box.focus();
  await expect(page.locator(".templates .tpl")).toHaveCount(3);
  await shot(page, "52-quickadd-templates");
  await box.fill("an tru");
  await expect(page.locator(".templates .tpl")).toHaveText(["ăn trưa …k"]);
  await shot(page, "52b-quickadd-matching");
  await page.locator(".templates .tpl", { hasText: "ăn trưa …k" }).click();
  await expect(box).toHaveValue("ăn trưa k");
  await page.keyboard.type("50");
  await expect(box).toHaveValue("ăn trưa 50k");
  await page.locator(".quick-row").getByRole("button", { name: "Thêm" }).click();
  await expect(page.getByText("Đã thêm: ăn trưa")).toBeVisible();

  // A phrase can be switched off: its words go back to the title. With the day off, 15:00 means today only while
  // 15:00 is still ahead (afterwards it means tomorrow), so the page's clock is pinned to this morning.
  const morning = new Date();
  morning.setHours(9, 0, 0, 0);
  await page.clock.setFixedTime(morning);
  await box.fill("gọi anh Nam mai 3h chiều");
  await page.getByRole("button", { name: /Ngày: Mai/ }).click();
  await expect(page.locator(".quick-read")).toContainText("Hôm nay");
  await page.getByRole("button", { name: "Bật lại Ngày" }).click();
  await box.fill("");

  await tab(page, "Việc");
  await page.getByRole("button", { name: /^Chi tiêu/ }).click();
  await expect(page.locator(".month .big")).toHaveText("95.000 đ");
  expectNoProblems(problems);
});

test("Mẫu câu: add, edit and delete from the manager", async ({ page }) => {
  await signIn(page, OWNER);
  await page.getByLabel("Gõ nhanh một việc, lịch hẹn hoặc khoản chi").focus();
  await page.getByRole("button", { name: "Mẫu câu…" }).click();
  const sheet = page.getByRole("dialog", { name: "Mẫu câu nhập nhanh" });
  await sheet.getByLabel("Mẫu mới").fill("phở 60k");
  await sheet.getByRole("button", { name: "Thêm mẫu" }).click();
  await expect(sheet.getByText("phở 60k")).toBeVisible();
  await shot(page, "53-templates-manager");
  await sheet.getByRole("button", { name: "Xoá mẫu phở 60k" }).click();
  await answerConfirm(page, "Không");
  await expect(sheet.getByText("phở 60k")).toBeVisible();
  await sheet.getByRole("button", { name: "Xoá mẫu phở 60k" }).click();
  await answerConfirm(page, "Xoá");
  await expect(sheet.getByText("phở 60k")).toHaveCount(0);
});

test("Rà soát moves an overdue task with one tap", async ({ page, request }) => {
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "items", "t1", { type: "TASK", status: "OPEN", title: "Gửi báo giá cho anh Nam", details: "", allDay: true, whenAt: now - 5 * 86_400_000, createdAt: now });
  await seed(request, who, "items", "t2", { type: "TASK", status: "OPEN", title: "Nộp thuế", details: "", allDay: true, whenAt: now - 2 * 86_400_000, createdAt: now });
  await signIn(page, OWNER);
  const card = page.getByRole("group", { name: "Rà soát việc quá hạn" });
  await expect(card).toContainText("còn 2 việc");
  await expect(card).toContainText("Gửi báo giá cho anh Nam");
  await shot(page, "54-triage");
  await card.getByRole("button", { name: "Mai" }).click();
  await expect(card).toContainText("còn 1 việc");
  await expect(card).toContainText("Nộp thuế");
  await card.getByRole("button", { name: "Để sau" }).click();
  await expect(card).toHaveCount(0);
});

test("spending dashboard and weekly recap", async ({ page, request }) => {
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  let n = 0;
  const spend = (title: string, amount: number, whenAt: number, category: string) =>
    seed(request, who, "items", `e${n++}`, { type: "EXPENSE", status: "OPEN", title, details: "", allDay: true, amount, category, whenAt, createdAt: whenAt });
  // Three months of rent and food, this month a bit faster, plus one big purchase.
  for (const m of [1, 2, 3]) {
    await spend("Tiền nhà", 5_000_000, at(m, 3), "Hoá đơn");
    for (let d = 1; d <= 20; d += 2) await spend("Ăn trưa", 60_000 + d * 1000, at(m, d), "Ăn uống");
  }
  await spend("Tiền nhà", 5_000_000, at(0, 1), "Hoá đơn");
  for (let d = 1; d <= 5; d += 1) await spend("Ăn trưa", 70_000, at(0, d), "Ăn uống");
  await spend("Tai nghe", 2_400_000, at(0, 2), "Mua sắm");
  for (let w = 0; w < 3; w++) await seed(request, who, "items", `d${w}`, { type: "TASK", status: "DONE", title: `Xong ${w}`, details: "", allDay: true, whenAt: now, doneAt: now - w * 3_600_000, createdAt: now });
  await seed(request, who, "items", "old", { type: "TASK", status: "OPEN", title: "Việc cũ", details: "", allDay: true, whenAt: now - 12 * 86_400_000, createdAt: now });
  await signIn(page, OWNER);

  await tab(page, "Việc");
  await page.getByRole("button", { name: /^Chi tiêu/ }).click();
  const panel = page.getByRole("region", { name: "Thống kê chi tiêu" });
  await expect(panel).toBeVisible();
  await expect(panel.getByLabel("Chi tiêu 6 tháng gần nhất").locator("li")).toHaveCount(6);
  await expect(panel.locator(".chart")).toBeVisible();
  await shot(page, "55-spending");

  await page.getByRole("button", { name: "Tuần", exact: true }).click();
  await expect(page.getByRole("region", { name: "Việc đã xong" })).toContainText("3");
  await expect(page.getByRole("list", { name: "Điều đáng chú ý" })).toContainText("quá hạn hơn 7 ngày");
  await shot(page, "56-weekly");
});
