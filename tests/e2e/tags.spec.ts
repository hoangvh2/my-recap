import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { answerConfirm, expectNoProblems, holdRow, idTokenFor, OWNER, seed, signIn, swipeRow, tab, watchBrowser } from "./helpers";

/** Real screenshots for review go to TAG_SHOTS when it is set (a folder); otherwise nothing is written. */
const SHOTS = process.env.TAG_SHOTS;
const shot = async (page: Page, name: string, scheme: string) => {
  if (!SHOTS) return;
  mkdirSync(SHOTS, { recursive: true });
  await page.screenshot({ path: `${SHOTS}/${name}-${scheme}.png` });
};

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

const at = (h: number, m = 0) => {
  const d = new Date();
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m).getTime();
};

async function seedDay(request: Parameters<typeof idTokenFor>[0]) {
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  const base = { status: "OPEN", details: "", createdAt: now };
  await seed(request, who, "items", "t1", { ...base, type: "TASK", title: "Nộp hồ sơ thuế", allDay: true, whenAt: at(0), tag: "red" });
  await seed(request, who, "items", "t2", { ...base, type: "EVENT", title: "Họp với khách ABC", place: "VP ABC", person: "anh Nam", allDay: false, whenAt: at(23, 0), tag: "yellow" });
  await seed(request, who, "items", "t3", { ...base, type: "TASK", title: "Đón con tan học", allDay: true, whenAt: at(0), tag: "purple" });
  await seed(request, who, "items", "t4", { ...base, type: "TASK", title: "Xác nhận lịch bay", allDay: true, whenAt: at(0), tag: "blue" });
  await seed(request, who, "items", "t5", { ...base, type: "TASK", title: "Mua sữa", allDay: true, whenAt: at(0) });
  await seed(request, who, "items", "t6", { ...base, type: "TASK", title: "Đóng tiền điện", allDay: true, whenAt: at(0), tag: "orange", important: true });
  return who;
}

for (const scheme of ["light", "dark"] as const) {
  test(`tags: list, long-press pick, swipes, editor picker and filter (${scheme})`, async ({ page, request }) => {
    const problems = watchBrowser(page);
    await seedDay(request);
    await page.emulateMedia({ colorScheme: scheme });
    // Pinned to the morning so a timed appointment is still ahead whatever the hour the suite runs at.
    await page.clock.setFixedTime(at(8));
    await signIn(page, OWNER);
    const row = (title: string) => page.locator(".row-item", { hasText: title });

    // ---- the corner triangle: colour AND glyph AND a label, and no check button on the left
    await expect(row("Nộp hồ sơ thuế").getByRole("img", { name: "Thẻ Gấp" })).toBeVisible();
    await expect(row("Họp với khách ABC").getByRole("img", { name: "Thẻ Nổi bật" })).toBeVisible();
    await expect(row("Mua sữa").getByRole("img", { name: /^Thẻ / })).toHaveCount(0);
    await expect(page.getByRole("checkbox")).toHaveCount(0);
    await expect(row("Nộp hồ sơ thuế").locator(".tag-corner")).toHaveCSS("width", "34px");
    await shot(page, "01-list-with-tags", scheme);

    // ---- filter chips: only while something open is tagged; single choice; "Có thẻ" = any
    const bar = page.getByRole("navigation", { name: "Lọc theo thẻ" });
    await expect(bar).toBeVisible();
    await shot(page, "05-filter-chips", scheme);
    await bar.getByRole("button", { name: "Thẻ Nổi bật" }).click();
    await expect(page.locator(".row-item")).toHaveCount(1);
    await expect(row("Họp với khách ABC")).toBeVisible();
    await shot(page, "06-filter-active", scheme);
    await bar.getByRole("button", { name: "Có thẻ" }).click();
    await expect(page.locator(".row-item")).toHaveCount(5);
    await expect(row("Mua sữa")).toHaveCount(0);
    await bar.getByRole("button", { name: "Có thẻ" }).click();
    await expect(page.locator(".row-item")).toHaveCount(6);

    // ---- long press: popover with the six tags; picking applies and closes; the row does not open
    await holdRow(page, row("Mua sữa"));
    const pop = page.getByRole("dialog", { name: "Chọn thẻ" });
    await expect(pop).toBeVisible();
    await expect(pop.getByRole("button", { name: /^Thẻ / })).toHaveCount(6);
    await expect(page.getByRole("dialog", { name: "Sửa" })).toHaveCount(0);
    await shot(page, "02-long-press-popover", scheme);
    await pop.getByRole("button", { name: "Thẻ Gia đình" }).click();
    await expect(pop).toHaveCount(0);
    await expect(row("Mua sữa").getByRole("img", { name: "Thẻ Gia đình" })).toBeVisible();
    await expect(page.getByRole("dialog", { name: "Sửa" })).toHaveCount(0);

    // ---- right click on another row: selected tag is marked; "Bỏ thẻ" removes it
    await row("Đón con tan học").click({ button: "right" });
    await expect(pop).toBeVisible();
    await expect(pop.getByRole("button", { name: "Thẻ Gia đình" })).toHaveAttribute("aria-pressed", "true");
    await pop.getByRole("button", { name: "Bỏ thẻ" }).click();
    await expect(row("Đón con tan học").locator(".tag-corner")).toHaveCount(0);

    // ---- swipe: a short drag snaps back and opens nothing; right finishes; left deletes with Hoàn tác
    await swipeRow(page, row("Mua sữa"), "right", 40);
    await expect(row("Mua sữa")).toBeVisible();
    await expect(page.getByRole("dialog", { name: "Sửa" })).toHaveCount(0);
    await expect.poll(() => row("Mua sữa").evaluate((e) => getComputedStyle(e).transform)).toMatch(/none|matrix\(1, 0, 0, 1, 0, 0\)/);

    const box = (await row("Xác nhận lịch bay").boundingBox())!;
    await page.mouse.move(box.x + box.width * 0.3, box.y + box.height / 2);
    await page.mouse.down();
    for (let i = 1; i <= 8; i++) await page.mouse.move(box.x + box.width * 0.3 + i * 10, box.y + box.height / 2);
    await expect(row("Xác nhận lịch bay").locator(".swipe-act.ok")).toBeVisible();
    await shot(page, "03-swipe-right-done", scheme);
    await page.mouse.up();
    await expect(row("Xác nhận lịch bay")).toBeVisible(); // 80px < threshold: snapped back
    await expect(page.getByText("Đã xong: Xác nhận lịch bay")).toHaveCount(0);

    await page.mouse.move(box.x + box.width * 0.7, box.y + box.height / 2);
    await page.mouse.down();
    for (let i = 1; i <= 9; i++) await page.mouse.move(box.x + box.width * 0.7 - i * 10, box.y + box.height / 2);
    await expect(row("Xác nhận lịch bay").locator(".swipe-act.del")).toBeVisible();
    await shot(page, "04-swipe-left-delete", scheme);
    await page.mouse.up();
    await answerConfirm(page, "Không"); // a long swipe left asks first; "Không" keeps the row
    await expect(row("Xác nhận lịch bay")).toBeVisible();

    await swipeRow(page, row("Mua sữa"), "right");
    await expect(page.getByText("Đã xong: Mua sữa")).toBeVisible();
    await expect(row("Mua sữa")).toHaveCount(0);
    await page.getByRole("button", { name: "Hoàn tác" }).click();
    await expect(row("Mua sữa")).toBeVisible();

    await swipeRow(page, row("Mua sữa"), "left");
    // A swipe never deletes on its own: the row springs back and the dialog asks.
    await expect(page.getByRole("alertdialog")).toContainText("Mua sữa");
    await answerConfirm(page, "Không");
    await expect(row("Mua sữa")).toBeVisible();
    await expect(page.getByText("Đã xoá: Mua sữa")).toHaveCount(0);
    await swipeRow(page, row("Mua sữa"), "left");
    await answerConfirm(page, "Xoá");
    await expect(page.getByText("Đã xoá: Mua sữa")).toBeVisible();
    await expect(row("Mua sữa")).toHaveCount(0);
    await page.getByRole("button", { name: "Hoàn tác" }).click();
    await expect(row("Mua sữa")).toBeVisible();

    // ---- non-gesture twins: the popover's own buttons, and the Delete key
    await row("Đóng tiền điện").click({ button: "right" });
    await pop.getByRole("button", { name: "Xong", exact: true }).click();
    await expect(page.getByText("Đã xong: Đóng tiền điện")).toBeVisible();
    await row("Đón con tan học").locator(".row-main").focus();
    await page.keyboard.press("Delete");
    await answerConfirm(page, "Xoá");
    await expect(page.getByText("Đã xoá: Đón con tan học")).toBeVisible();

    // ---- editor: corner trigger, list with dot + glyph + name + "Không thẻ"; saved with the item
    await row("Mua sữa").locator(".row-main").click();
    const editor = page.getByRole("dialog", { name: "Sửa" });
    await editor.getByRole("button", { name: /Đổi thẻ/ }).click(); // it already has one (Gia đình)
    const menu = editor.getByRole("menu", { name: "Chọn thẻ" });
    await expect(menu.getByRole("menuitemradio")).toHaveCount(7);
    await expect(menu.getByRole("menuitemradio", { name: /Không thẻ/ })).toBeVisible();
    await shot(page, "07-editor-tag-picker", scheme);
    await menu.getByRole("menuitemradio", { name: /Cần xác nhận/ }).click();
    await expect(editor.getByRole("button", { name: /Thẻ Cần xác nhận/ })).toBeVisible();
    await shot(page, "08-editor-tag-set", scheme);
    await editor.getByRole("button", { name: "Lưu", exact: true }).click();
    await expect(row("Mua sữa").getByRole("img", { name: "Thẻ Cần xác nhận" })).toBeVisible();

    // A note has no tag: the trigger is hidden, and a tag chosen before switching type is dropped.
    await tab(page, "Việc");
    await page.getByRole("button", { name: "Thêm", exact: true }).click();
    await page.locator(".add-tile", { hasText: "Việc" }).click();
    const fresh = page.getByRole("dialog", { name: "Thêm mới" });
    await fresh.getByRole("button", { name: "Chọn thẻ" }).click();
    await fresh.getByRole("menuitemradio", { name: /Lưu ý/ }).click();
    await fresh.getByRole("radio", { name: "Ghi chú" }).click();
    await expect(fresh.getByRole("button", { name: /thẻ/i })).toHaveCount(0);
    await fresh.getByRole("radio", { name: "Việc" }).click();
    await expect(fresh.getByRole("button", { name: "Chọn thẻ" })).toBeVisible();
    await fresh.getByRole("button", { name: "Đóng" }).click();
    expectNoProblems(problems);
  });
}

test("the filter row only exists while an open task or appointment has a tag", async ({ page, request }) => {
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "items", "a", { type: "TASK", status: "OPEN", title: "Chưa có thẻ", details: "", allDay: true, whenAt: at(0), createdAt: now });
  await seed(request, who, "items", "n", { type: "NOTE", status: "OPEN", title: "Ghi chú", details: "", allDay: true, createdAt: now });
  await signIn(page, OWNER);
  await expect(page.getByRole("navigation", { name: "Lọc theo thẻ" })).toHaveCount(0);
  const row = page.locator(".row-item", { hasText: "Chưa có thẻ" });
  await holdRow(page, row);
  await page.getByRole("dialog", { name: "Chọn thẻ" }).getByRole("button", { name: "Thẻ Gấp" }).click();
  const bar = page.getByRole("navigation", { name: "Lọc theo thẻ" });
  await expect(bar).toBeVisible();
  // It is on the task list too, and clearing the last tag brings the row away again.
  await tab(page, "Việc");
  await page.locator(".chips").getByRole("button", { name: /^Việc/ }).click();
  await expect(bar).toBeVisible();
  await bar.getByRole("button", { name: "Thẻ Gấp" }).click();
  await expect(row).toBeVisible();
  await row.click({ button: "right" });
  await page.getByRole("dialog", { name: "Chọn thẻ" }).getByRole("button", { name: "Bỏ thẻ" }).click();
  await expect(bar).toHaveCount(0);
  await expect(row).toBeVisible();
  // Notes can not be tagged and a long press on one opens nothing.
  await page.locator(".chips").getByRole("button", { name: /^Ghi chú/ }).click();
  await holdRow(page, page.locator(".row-item", { hasText: "Ghi chú" }));
  await expect(page.getByRole("dialog", { name: "Chọn thẻ" })).toHaveCount(0);
});

test("auto-tag rules: set up in Settings, applied to a new item once, never again on edit", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("link", { name: "Cài đặt" }).click();
  await page.getByRole("button", { name: /Thêm quy tắc/ }).click();
  await page.getByLabel("Quy tắc 1: chứa chữ").fill("HỌP");
  await page.getByLabel("Quy tắc 1: gắn thẻ").selectOption("blue");
  await page.getByRole("button", { name: /Thêm quy tắc/ }).click();
  await page.getByLabel("Quy tắc 2: áp dụng cho").selectOption("person");
  await page.getByLabel("Quy tắc 2: chứa chữ").fill("dang");
  await page.getByLabel("Quy tắc 2: gắn thẻ").selectOption("purple");
  await shot(page, "09-settings-rules", "light");
  // They are remembered on this device.
  await page.reload();
  await expect(page.getByLabel("Quy tắc 1: chứa chữ")).toHaveValue("HỌP");
  await tab(page, "Hôm nay");

  // Quick add: "họp" matches rule 1 (accents and case ignored by the rule: "HỌP" vs "họp nhóm").
  await page.getByLabel("Gõ nhanh một việc, lịch hẹn hoặc khoản chi").fill("hop nhom thu 5 9h");
  await page.getByRole("button", { name: "Thêm", exact: true }).click();
  await expect(page.getByText(/Đã thêm/)).toBeVisible();
  await tab(page, "Việc");
  await page.locator(".chips").getByRole("button", { name: /^Lịch/ }).click();
  const row = page.locator(".row-item", { hasText: /nhom/i });
  await expect(row.getByRole("img", { name: "Thẻ Cần xác nhận" })).toBeVisible();

  // The person removes it; editing the item afterwards does not bring it back.
  await row.click({ button: "right" });
  await page.getByRole("dialog", { name: "Chọn thẻ" }).getByRole("button", { name: "Bỏ thẻ" }).click();
  await expect(row.locator(".tag-corner")).toHaveCount(0);
  await row.locator(".row-main").click();
  await page.getByLabel("Tiêu đề").fill("Họp nhóm sản phẩm");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.locator(".row-item", { hasText: "Họp nhóm sản phẩm" })).toBeVisible();
  await expect(page.locator(".row-item", { hasText: "Họp nhóm sản phẩm" }).locator(".tag-corner")).toHaveCount(0);

  // The editor applies the rules to a new item too, unless a choice (even "Không thẻ") was made there.
  await page.locator(".chips").getByRole("button", { name: /^Việc/ }).click();
  await page.getByRole("button", { name: "Thêm", exact: true }).click();
  await page.locator(".add-tile", { hasText: "Việc" }).click();
  await page.getByLabel("Tiêu đề").fill("Họp phụ huynh");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.locator(".row-item", { hasText: "Họp phụ huynh" }).getByRole("img", { name: "Thẻ Cần xác nhận" })).toBeVisible();
  await page.getByRole("button", { name: "Thêm", exact: true }).click();
  await page.locator(".add-tile", { hasText: "Việc" }).click();
  await page.getByLabel("Tiêu đề").fill("Họp không thẻ");
  await page.getByRole("button", { name: "Chọn thẻ" }).click();
  await page.getByRole("menuitemradio", { name: /Không thẻ/ }).click();
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.locator(".row-item", { hasText: "Họp không thẻ" })).toBeVisible();
  await expect(page.locator(".row-item", { hasText: "Họp không thẻ" }).locator(".tag-corner")).toHaveCount(0);
  expectNoProblems(problems);
});

test("AI drafts get their auto-tag when they are confirmed", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "items", "d1", { type: "TASK", status: "DRAFT", title: "Gửi hợp đồng cho anh Nam", details: "", person: "anh Nam", allDay: true, whenAt: at(0), sourceId: "cap1", createdAt: now });
  await page.addInitScript(() => localStorage.setItem("vhub.tagRules", JSON.stringify([{ field: "person", text: "NAM", tag: "orange" }])));
  await signIn(page, OWNER);
  await page.locator(".draft-card").getByRole("button", { name: /Lưu tất cả/ }).click();
  await expect(page.locator(".draft-card")).toHaveCount(0);
  await expect(page.locator(".row-item", { hasText: "Gửi hợp đồng" }).getByRole("img", { name: "Thẻ Lưu ý" })).toBeVisible();
  expectNoProblems(problems);
});
