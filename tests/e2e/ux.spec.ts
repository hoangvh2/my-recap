import { expect, test, type Page } from "@playwright/test";
import { expectNoProblems, idTokenFor, OWNER, seed, signIn, tab, textCapture, watchBrowser } from "./helpers";

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png` });

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

test("the customer picker inside the licence form closes on choose and on ✕, without submitting the form", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "customers", "cvan", { status: "OPEN", name: "Chị Vân", createdAt: now, updatedAt: now });
  await signIn(page, OWNER);
  await tab(page, "Gia hạn");
  await page.getByRole("button", { name: "Thêm" }).click();
  const form = page.getByRole("dialog", { name: "Thêm license / bảo hành" });
  await form.locator("button.picker").click();
  const picker = page.getByRole("dialog", { name: "Chọn khách" });
  await expect(picker).toBeVisible();
  // ✕ closes the picker only: the licence form stays open, with no validation error.
  await picker.getByRole("button", { name: "Đóng" }).click();
  await expect(picker).toHaveCount(0);
  await expect(form).toBeVisible();
  await expect(form.getByRole("alert")).toHaveCount(0);
  // Choosing closes it and fills the field.
  await form.locator("button.picker").click();
  await picker.getByRole("button", { name: "Chị Vân" }).click();
  await expect(picker).toHaveCount(0);
  await expect(form.locator("button.picker", { hasText: "Chị Vân" })).toBeVisible();
  // The picker is not a descendant of the form or of a label (WebKit forwards taps inside a label to its first button).
  await form.locator("button.picker", { hasText: "Chị Vân" }).click();
  expect(await page.evaluate(() => {
    const d = [...document.querySelectorAll('[role="dialog"]')].find((x) => x.getAttribute("aria-label") === "Chọn khách")!;
    return !!d.closest("label, form");
  })).toBe(false);
  await page.getByRole("dialog", { name: "Chọn khách" }).getByRole("button", { name: "Đóng" }).click();
  expectNoProblems(problems);
});

test("Today is a timeline with times, appointments can be ticked off, and a repeating one moves to its next date", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await idTokenFor(request, OWNER);
  const d = new Date();
  const at = (h: number, m = 0) => new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m).getTime();
  const now = Date.now();
  await seed(request, who, "items", "a", { type: "EVENT", status: "OPEN", title: "Họp với khách", details: "", allDay: false, whenAt: at(23, 0), createdAt: now });
  await seed(request, who, "items", "b", { type: "TASK", status: "OPEN", title: "Gửi email cho khách", details: "", allDay: false, whenAt: at(22, 30), createdAt: now });
  await seed(request, who, "items", "c", { type: "TASK", status: "OPEN", title: "Đóng học phí", details: "", allDay: true, whenAt: at(0, 0), createdAt: now });
  await seed(request, who, "items", "w", { type: "EVENT", status: "OPEN", title: "Họp tuần", details: "", allDay: false, whenAt: at(23, 30), recurrence: "WEEKLY", createdAt: now });
  // Past timed items leave today's timeline, so the page's clock is pinned to this morning: the run passes at any hour.
  await page.clock.setFixedTime(at(8));
  await signIn(page, OWNER);
  const tl = page.locator(".timeline");
  await expect(tl.locator(".row-item")).toHaveCount(4);
  expect(await tl.locator(".row-item .when").allTextContents()).toEqual(["Cả ngày", "22:30", "23:00", "23:30"]);
  expect(await tl.locator(".row-item .title").allTextContents()).toEqual(["Đóng học phí", "Gửi email cho khách", "Họp với khách", "Họp tuần"]);
  await shot(page, "50-today-timeline");

  // An appointment has its own "done" circle.
  await tl.locator(".row-item", { hasText: "Họp với khách" }).getByRole("checkbox").click();
  await expect(page.getByText("Đã xong: Họp với khách")).toBeVisible();
  await expect(tl.locator(".row-item", { hasText: "Họp với khách" })).toHaveCount(0);
  // A weekly one is not ended: it moves to next week (and leaves today's timeline).
  await tl.locator(".row-item", { hasText: "Họp tuần" }).getByRole("checkbox").click();
  await expect(tl.locator(".row-item", { hasText: "Họp tuần" })).toHaveCount(0);
  await page.locator(".tabbar").getByText("Việc", { exact: true }).click();
  await page.locator(".chips").getByRole("button", { name: /^Lịch/ }).click();
  await expect(page.locator(".row-item", { hasText: "Họp tuần" })).toHaveCount(1);
  // And from the editor: "Đã xong" without hunting for the circle.
  await page.locator(".chips").getByRole("button", { name: /^Việc/ }).click();
  await page.locator(".row-item", { hasText: "Gửi email cho khách" }).locator(".row-main").click();
  await page.getByRole("button", { name: "Đã xong" }).click();
  await expect(page.getByText("Đã xong: Gửi email cho khách")).toBeVisible();
  expectNoProblems(problems);
});

test("'cần 2 license SIM và CheckPoint' arrives as two draft licences and lands in Gia hạn", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "customers", "cvan", { status: "OPEN", name: "Chị Vân", createdAt: now, updatedAt: now });
  await signIn(page, OWNER);
  await textCapture(page, "FAKE_NEED_TWO Chị Vân cần 2 license SIM và CheckPoint trước thứ 2 tuần sau");
  const card = page.locator(".draft-card");
  await expect(card.locator("h4", { hasText: "License / bảo hành mới" })).toBeVisible();
  await expect(card.locator(".row-item", { hasText: "Chị Vân · SIM" })).toBeVisible();
  await expect(card.locator(".row-item", { hasText: "Chị Vân · CheckPoint" })).toBeVisible();
  await shot(page, "51-two-licences");
  await card.getByRole("button", { name: "Lưu tất cả" }).click();
  await expect(page.locator(".draft-card")).toHaveCount(0);
  await tab(page, "Gia hạn");
  await page.getByRole("button", { name: "Đang theo dõi" }).click();
  await expect(page.locator(".lic-row", { hasText: "SIM" })).toBeVisible();
  await expect(page.locator(".lic-row", { hasText: "CheckPoint" })).toBeVisible();
  expectNoProblems(problems);
});

test("Light/Dark/Auto theme from Settings, remembered on this device; the VHub signature is there but small", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "items", "a", { type: "EVENT", status: "OPEN", title: "Họp với khách", details: "", place: "VP ABC", allDay: false, whenAt: now + 3_600_000, createdAt: now });
  await seed(request, who, "items", "b", { type: "TASK", status: "OPEN", title: "Gửi báo giá", details: "", allDay: true, whenAt: now, important: true, createdAt: now });
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page, OWNER);
  const bg = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(await bg()).toBe("rgb(246, 247, 249)");
  await shot(page, "60-today-light");

  await page.getByRole("link", { name: "Cài đặt" }).click();
  await page.getByRole("radio", { name: "Tối" }).click();
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe("dark");
  expect(await bg()).toBe("rgb(11, 13, 16)");
  expect(await page.locator('meta[name="theme-color"]').first().getAttribute("content")).toBe("#0b0d10");
  // The signature: app name, version, copyright, at the bottom of Settings.
  const sig = page.locator(".signature");
  await expect(sig).toContainText("VHub Desk");
  await expect(sig).toContainText(/© 20\d\d VHub/);
  expect((await sig.boundingBox())!.height).toBeLessThan(90);
  await shot(page, "61-settings-dark");

  // Remembered after a reload, before anything is drawn.
  await page.reload();
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe("dark");
  await page.locator(".tabbar").getByText("Hôm nay", { exact: true }).click();
  await shot(page, "62-today-dark");

  // "Tự động" follows the phone.
  await page.goto("/#/cai-dat");
  await page.getByRole("radio", { name: "Tự động" }).click();
  expect(await page.evaluate(() => document.documentElement.hasAttribute("data-theme"))).toBe(false);
  await page.emulateMedia({ colorScheme: "dark" });
  expect(await bg()).toBe("rgb(11, 13, 16)");
  await page.emulateMedia({ colorScheme: "light" });
  expect(await bg()).toBe("rgb(246, 247, 249)");
  expectNoProblems(problems);
});
