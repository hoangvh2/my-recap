import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { answerConfirm, expectNoProblems, idTokenFor, OWNER, seed, signIn, swipeRow, tab, watchBrowser } from "./helpers";

/** Real screenshots for review go to CHECKLIST_SHOTS when it is set (a folder); otherwise nothing is written. */
const SHOTS = process.env.CHECKLIST_SHOTS;
const shot = async (page: Page, name: string, scheme = "light") => {
  if (!SHOTS) return;
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: `${SHOTS}/${name}-${scheme}.png` });
};

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

const at = (h: number, m = 0, dayOffset = 0) => {
  const d = new Date();
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + dayOffset, h, m).getTime();
};

const row = (page: Page, title: string) => page.locator(".row-item", { hasText: title });
const quick = (page: Page) => page.getByRole("textbox", { name: /Gõ nhanh/ });
/** Undated tasks are not on Hôm nay; they are in Việc → Việc. */
async function openTasks(page: Page) {
  await tab(page, "Việc");
  await page.locator(".chips").getByRole("button", { name: /^Việc/ }).click();
}
const lines = (texts: string[]) => texts.map((text, i) => ({ id: `e${i}`, text }));

async function seedTrip(request: Parameters<typeof idTokenFor>[0], extra: Record<string, unknown> = {}) {
  const who = await idTokenFor(request, OWNER);
  await seed(request, who, "items", "c1", {
    type: "TASK", status: "OPEN", title: "Chuẩn bị đi Hà Nội", details: "", allDay: true, whenAt: at(0), createdAt: Date.now(),
    checklist: lines(["Vé máy bay", "Sạc dự phòng", "Laptop"]), ...extra,
  } as never);
}

for (const scheme of ["light", "dark"] as const) {
  test(`checklist: one tap from a template, tick what is missing, "Đủ rồi" finishes and the routine comes back unticked (${scheme})`, async ({ page }) => {
    const problems = watchBrowser(page);
    await page.emulateMedia({ colorScheme: scheme });
    await page.clock.setFixedTime(at(6)); // 07:30 is still ahead
    await signIn(page, OWNER);

    // The built-in "Ra khỏi nhà" has its own time and repeat, so one tap creates it.
    await quick(page).focus();
    await shot(page, "01-quickadd-templates", scheme);
    await page.getByRole("button", { name: "Mẫu checklist Ra khỏi nhà, 5 mục" }).click();
    await expect(page.getByText("Đã thêm: Ra khỏi nhà (Việc · Hằng ngày · 07:30)")).toBeVisible();
    const r = row(page, "Ra khỏi nhà");
    await expect(r.getByText("Còn: Ví · Chìa khoá · Điện thoại và sạc · +2")).toBeVisible();
    const chip = r.getByRole("button", { name: /^Checklist 0\/5/ });
    await expect(chip).toBeVisible();
    await shot(page, "02-row-remaining", scheme);

    // Opening the card shows the lines; ticking one removes it from the "Còn" line.
    await expect(page.getByRole("checkbox")).toHaveCount(0);
    await chip.click();
    await expect(r.getByRole("checkbox")).toHaveCount(5);
    await r.getByRole("checkbox", { name: "Ví" }).check();
    await expect(r.getByRole("button", { name: /^Checklist 1\/5/ })).toBeVisible();
    await expect(r.getByText("Còn: Chìa khoá · Điện thoại và sạc · Thuốc · +1")).toBeVisible();
    await shot(page, "03-card-open", scheme);

    // "Đủ rồi": everything ticked, the task is done, tomorrow's comes back with nothing ticked.
    await r.getByRole("button", { name: "Đủ rồi" }).click();
    await expect(page.getByText("Đã đủ: Ra khỏi nhà")).toBeVisible();
    await expect(page.locator(".timeline").getByText("Ra khỏi nhà")).toHaveCount(0);
    const tomorrow = row(page, "Ra khỏi nhà");
    await expect(tomorrow).toHaveCount(1);
    await expect(tomorrow.getByRole("button", { name: /^Checklist 0\/5/ })).toBeVisible();

    // Hoàn tác puts today's back as it was before the tap (1 of 5).
    await page.getByRole("button", { name: "Hoàn tác" }).click();
    await expect(page.locator(".timeline").getByRole("button", { name: /^Checklist 1\/5/ })).toBeVisible();
    expectNoProblems(problems);
  });
}

test("a quick line with a colon carries its checklist", async ({ page }) => {
  await signIn(page, OWNER);
  await quick(page).fill("giao hàng anh Nam: hoá đơn, biên bản, hàng mẫu");
  await expect(page.getByText(/Checklist 3 mục/)).toBeVisible();
  await page.getByRole("button", { name: "Thêm", exact: true }).click();
  await expect(page.getByText("Đã thêm: giao hàng anh Nam")).toBeVisible();
  // An ordinary line (a time, not a list) stays an ordinary task: the summary says nothing about a checklist.
  await quick(page).fill("họp nhóm 9:30");
  await expect(page.locator(".quick-read")).not.toContainText("Checklist");
  await openTasks(page);
  const r = row(page, "giao hàng anh Nam");
  await expect(r.getByRole("button", { name: /^Checklist 0\/3/ })).toBeVisible();
  await expect(r.getByText("Còn: hoá đơn · biên bản · hàng mẫu")).toBeVisible();
});

test("a template without a time opens the editor with the checklist already filled", async ({ page }) => {
  await signIn(page, OWNER);
  await quick(page).focus();
  await page.getByRole("button", { name: "Mẫu checklist Giao hàng cho khách, 4 mục" }).click();
  const editor = page.getByRole("dialog", { name: "Thêm mới" });
  await expect(editor.getByLabel("Tiêu đề")).toHaveValue("Giao hàng cho khách");
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(4);
  await editor.getByRole("button", { name: "Lưu", exact: true }).click();
  await openTasks(page);
  await expect(row(page, "Giao hàng cho khách").getByRole("button", { name: /^Checklist 0\/4/ })).toBeVisible();
});

test("editor: add by Enter, paste a list, delete a line and the whole checklist always ask first", async ({ page, request }) => {
  await seedTrip(request);
  await signIn(page, OWNER);
  await row(page, "Chuẩn bị đi Hà Nội").locator(".row-main").click();
  const editor = page.getByRole("dialog", { name: "Sửa" });
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(3);
  await editor.locator(".ck-editor").scrollIntoViewIfNeeded();
  await shot(page, "05-editor-checklist");

  const add = editor.getByRole("textbox", { name: "Thêm mục checklist" });
  await add.fill("Thuốc");
  await add.press("Enter");
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(4);
  await add.fill("Áo mưa, Ô");
  await add.press("Enter");
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(6);
  await expect(editor.getByRole("dialog")).toHaveCount(0); // Enter in the box did not submit the form

  // One line: "Không" keeps it, "Xoá" removes it.
  await editor.getByRole("button", { name: "Xoá mục Thuốc" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Thuốc");
  await answerConfirm(page, "Không");
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(6);
  await editor.getByRole("button", { name: "Xoá mục Thuốc" }).click();
  await answerConfirm(page, "Xoá");
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(5);

  // Whole checklist: same.
  await editor.getByRole("button", { name: "Xoá checklist" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Xoá checklist này?");
  await shot(page, "06-confirm-delete-checklist");
  await answerConfirm(page, "Không");
  await expect(editor.getByRole("textbox", { name: /^Mục \d$/ })).toHaveCount(5);
  await editor.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(row(page, "Chuẩn bị đi Hà Nội").getByRole("button", { name: /^Checklist 0\/5/ })).toBeVisible();

  await row(page, "Chuẩn bị đi Hà Nội").locator(".row-main").click();
  await editor.getByRole("button", { name: "Xoá checklist" }).click();
  await answerConfirm(page, "Xoá");
  await editor.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(row(page, "Chuẩn bị đi Hà Nội")).toBeVisible();
  await expect(row(page, "Chuẩn bị đi Hà Nội").getByRole("button", { name: /^Checklist/ })).toHaveCount(0);
});

test("finishing something with lines still missing asks once; the last tick finishes it by itself", async ({ page, request }) => {
  await seedTrip(request);
  await signIn(page, OWNER);
  const r = row(page, "Chuẩn bị đi Hà Nội");
  await swipeRow(page, r, "right");
  await expect(page.getByRole("alertdialog")).toContainText("Còn 3 mục chưa có. Xong luôn?");
  await shot(page, "04-confirm-finish");
  await answerConfirm(page, "Không");
  await expect(r).toBeVisible();
  await expect(page.getByText("Đã xong: Chuẩn bị đi Hà Nội")).toHaveCount(0);

  await r.getByRole("button", { name: /^Checklist 0\/3/ }).click();
  for (const name of ["Vé máy bay", "Sạc dự phòng"]) await r.getByRole("checkbox", { name }).check();
  await expect(r).toBeVisible();
  await r.getByRole("checkbox", { name: "Laptop" }).click();
  await expect(page.getByText("Đã đủ: Chuẩn bị đi Hà Nội")).toBeVisible();
  await expect(r).toHaveCount(0); // finished, so it leaves today's list
  await page.getByRole("button", { name: "Hoàn tác" }).click();
  await expect(r.getByRole("button", { name: /^Checklist 2\/3/ })).toBeVisible();
});

test("Cài đặt → Mẫu checklist: add, edit, delete (asks first) and use a template", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("link", { name: "Cài đặt" }).click();
  const list = page.getByRole("list", { name: "Mẫu checklist" });
  await expect(list.locator("li")).toHaveCount(3);
  await expect(list).toContainText("Ra khỏi nhà");
  await expect(list).toContainText("5 mục · Việc · Hằng ngày · 07:30");
  await list.scrollIntoViewIfNeeded();
  await shot(page, "07-settings-templates");

  // Add one; a duplicate name is refused with a message.
  await page.getByRole("button", { name: /^Thêm mẫu/ }).click();
  const sheet = page.getByRole("dialog", { name: "Mẫu checklist mới" });
  await shot(page, "08-template-new");
  await sheet.getByLabel("Tên mẫu").fill("ra khoi nha");
  await sheet.getByLabel(/^Các mục/).fill("Ví");
  await sheet.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(sheet.getByRole("alert")).toHaveText("Đã có mẫu trùng tên");
  await sheet.getByLabel("Tên mẫu").fill("Đi công tác");
  await sheet.getByLabel(/^Các mục/).fill("Vé\nSạc\nSạc\n\nLaptop");
  await sheet.getByLabel("Giờ (tuỳ chọn)").fill("06:00");
  await sheet.getByLabel(/^Lặp lại/).selectOption("WEEKDAYS");
  await sheet.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(list.locator("li")).toHaveCount(4);
  await expect(list).toContainText("Đi công tác");
  await expect(list).toContainText("3 mục · Việc · Ngày làm việc · 06:00");

  // Edit it.
  await list.getByRole("button", { name: "Sửa mẫu Đi công tác" }).click();
  await page.getByRole("dialog", { name: "Sửa mẫu checklist" }).getByLabel(/^Các mục/).fill("Vé\nSạc\nLaptop\nHộ chiếu");
  await page.getByRole("dialog", { name: "Sửa mẫu checklist" }).getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(list).toContainText("4 mục · Việc · Ngày làm việc · 06:00");

  // Delete: "Không" keeps it, "Xoá" removes it, and the built-in one can be restored.
  await list.getByRole("button", { name: "Xoá mẫu Chuẩn bị họp" }).click();
  await expect(page.getByRole("alertdialog")).toContainText("Chuẩn bị họp · 3 mục");
  await shot(page, "09-confirm-delete-template");
  await answerConfirm(page, "Không");
  await expect(list.locator("li")).toHaveCount(4);
  await list.getByRole("button", { name: "Xoá mẫu Chuẩn bị họp" }).click();
  await answerConfirm(page, "Xoá");
  await expect(list.locator("li")).toHaveCount(3);
  await expect(list).not.toContainText("Chuẩn bị họp");
  // From the edit sheet it asks the same way.
  await list.getByRole("button", { name: "Sửa mẫu Đi công tác" }).click();
  await page.getByRole("dialog", { name: "Sửa mẫu checklist" }).getByRole("button", { name: "Xoá", exact: true }).click();
  await answerConfirm(page, "Không");
  await expect(page.getByRole("dialog", { name: "Sửa mẫu checklist" })).toBeVisible();
  await page.getByRole("dialog", { name: "Sửa mẫu checklist" }).getByRole("button", { name: "Đóng" }).click();
  await page.getByRole("button", { name: "Khôi phục mẫu có sẵn" }).click();
  await expect(list).toContainText("Chuẩn bị họp");

  // The new template is offered in quick add.
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: /Gõ nhanh/ })).toBeVisible();
  await quick(page).fill("cong tac");
  await expect(page.getByRole("button", { name: /^Mẫu checklist Đi công tác, 4 mục/ })).toBeVisible();
  expectNoProblems(problems);
});
