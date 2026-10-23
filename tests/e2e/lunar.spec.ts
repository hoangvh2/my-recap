import { expect, test, type Page } from "@playwright/test";
import { answerConfirm, expectNoProblems, idTokenFor, OWNER, seed, signIn, swipeRow, tab, watchBrowser } from "./helpers";

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

/** Local time, so the page (which reads the same clock) sees the same day. */
const local = (y: number, mo: number, d: number, h = 0) => new Date(y, mo - 1, d, h).getTime();
const row = (page: Page, title: string) => page.locator(".row-item", { hasText: title });

async function openCalendar(page: Page) {
  await tab(page, "Việc");
  await page.locator(".chips").getByRole("button", { name: /^Lịch/ }).click();
}

async function seedGio(request: Parameters<typeof idTokenFor>[0]) {
  const who = await idTokenFor(request, OWNER);
  // 26/4/2027 is 20/3 Âm.
  await seed(request, who, "items", "g1", {
    type: "EVENT", status: "OPEN", title: "Giỗ ông nội", details: "", allDay: true, whenAt: local(2027, 4, 26),
    recurrence: "LUNAR_YEARLY", createdAt: Date.now(),
  } as never);
}

test("calendar: the month view shows solar and lunar dates and the repeat shows its lunar date", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedGio(request);
  await page.clock.setFixedTime(local(2027, 4, 20, 9));
  await signIn(page, OWNER);
  await openCalendar(page);

  await page.getByRole("radio", { name: "Tháng", exact: true }).click();
  await expect(page.getByText("Tháng 4/2027", { exact: true })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: /26 .*20 tháng 3 Âm/ })).toBeVisible();
  await page.getByRole("button", { name: "Tháng sau" }).click();
  await expect(page.getByText("Tháng 5/2027", { exact: true })).toBeVisible();
  expectNoProblems(problems);
});

test("banner: a few days before Tết the app says the break is coming and Ẩn can be undone", async ({ page }) => {
  const problems = watchBrowser(page);
  await page.clock.setFixedTime(local(2027, 1, 30, 9)); // Tết 2027 break starts 4/2
  await signIn(page, OWNER);
  const banner = page.locator(".notice.holiday");
  await expect(banner).toContainText("Sắp nghỉ · Tết Nguyên đán");
  await banner.getByRole("button", { name: "Ẩn" }).click();
  await expect(banner).toHaveCount(0);
  await page.getByRole("button", { name: "Hoàn tác" }).click();
  await expect(banner).toContainText("Sắp nghỉ · Tết Nguyên đán");
  expectNoProblems(problems);
});

test("editor: choosing a lunar date fills in the solar date", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedGio(request);
  await page.clock.setFixedTime(local(2027, 4, 20, 9));
  await signIn(page, OWNER);
  await openCalendar(page);
  await row(page, "Giỗ ông nội").locator(".row-main").click();
  const editor = page.getByRole("dialog", { name: "Sửa" });
  await editor.getByRole("radio", { name: "Âm", exact: true }).click();
  await editor.getByLabel("Ngày Âm").selectOption("21");
  await expect(editor.getByText(/= .*27\/4\/2027|= .*27 tháng 4/)).toBeVisible();
  expectNoProblems(problems);
});

test("deleting a lunar repeat asks first: Không keeps it, Xoá removes it", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await seedGio(request);
  await page.clock.setFixedTime(local(2027, 4, 20, 9));
  await signIn(page, OWNER);
  await openCalendar(page);
  const r = row(page, "Giỗ ông nội");
  await expect(r).toBeVisible();

  await swipeRow(page, r, "left");
  await expect(page.getByRole("alertdialog")).toContainText("Xoá sẽ bỏ cả các lần sau");
  await answerConfirm(page, "Không");
  await expect(row(page, "Giỗ ông nội")).toBeVisible();

  await swipeRow(page, row(page, "Giỗ ông nội"), "left");
  await answerConfirm(page, "Xoá");
  await expect(row(page, "Giỗ ông nội")).toHaveCount(0);
  expectNoProblems(problems);
});
