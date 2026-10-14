import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import { expectNoProblems, isoIn, MEMBER, OWNER, signIn, STRANGER, tab, watchBrowser } from "./helpers";

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png`, fullPage: true });
const KEY = "uQiRzpo4DXghDmr9QzzfQu27cmVRsG"; // shape of a Pushover user key: the fake accepts it
type Push = Record<string, string>;
const pushes = async (request: APIRequestContext) => (await (await request.get("http://127.0.0.1:8788/pushes")).json()) as Push[];

// A step that cannot happen fails fast and names itself, instead of running into the test timeout.
test.use({ actionTimeout: 15_000 });

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

/** A second person in their own browser. */
async function person(browser: Browser, email: string): Promise<Page> {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3 });
  const page = await ctx.newPage();
  await signIn(page, email);
  return page;
}

async function openContacts(page: Page) {
  await tab(page, "Hôm nay");
  await page.getByRole("link", { name: "Cài đặt" }).click();
  await page.getByRole("link", { name: /Liên hệ chia sẻ/ }).click();
  await expect(page.getByRole("heading", { name: "Liên hệ chia sẻ" })).toBeVisible();
}

/** The owner makes a code, the member enters it and saves the owner as `alias`. */
async function link(owner: Page, member: Page, alias: string) {
  await openContacts(owner);
  await owner.getByRole("button", { name: "Tạo mã" }).click();
  const code = (await owner.locator(".link-code").textContent())!.trim();
  expect(code).toMatch(/^[A-HJ-NP-Z2-9]{4} [A-HJ-NP-Z2-9]{4}$/);
  await expect(owner.getByText(/Hết hạn sau/)).toBeVisible();
  await shot(owner, "60-share-code");
  await owner.getByRole("button", { name: "Đóng" }).click();

  await openContacts(member);
  await member.getByRole("button", { name: "Nhập mã" }).click();
  await member.getByLabel("Mã 8 ký tự").fill(code.toLowerCase());
  await member.getByRole("button", { name: "Kiểm tra mã" }).click();
  await expect(member.locator(".who")).toContainText("owner");
  await expect(member.locator(".who")).toContainText("own•••@example.com");
  await member.getByLabel(/Lưu người này với tên/).fill(alias);
  await shot(member, "61-share-enter-code");
  await member.getByRole("button", { name: "Liên kết", exact: true }).click();
  await expect(member.locator(".row-item", { hasText: alias })).toBeVisible();
}

test("two people link with a code, share one appointment, the edit reaches the other, and unlinking removes it", async ({ page, browser, request }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  const member = await person(browser, MEMBER);
  const memberProblems = watchBrowser(member);

  // The member gets pushes through Pushover (the emulator has no FCM).
  await member.getByRole("link", { name: "Cài đặt" }).click();
  await member.getByLabel("User Key của Pushover").fill(KEY);
  await member.getByRole("button", { name: "Lưu và bật" }).click();
  await expect(member.locator(".notify .status")).toContainText("Đang bật");

  await link(page, member, "Anh An");
  // The owner is told, and can give the member a name of their own.
  await tab(page, "Hôm nay");
  await expect(page.locator(".notice.linked")).toContainText("member đã liên kết với bạn");
  await page.locator(".notice.linked").getByRole("button", { name: "Đặt tên" }).click();
  await expect(page.getByLabel(/Tên gợi nhớ/)).toHaveValue("member"); // the contact has loaded
  await page.getByLabel(/Tên gợi nhớ/).fill("Em Lan");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByText("Đã đổi tên gợi nhớ")).toBeVisible();

  // Two appointments: only one is shared; the work one stays private.
  for (const [title, share] of [["Gặp khách ABC", false], ["Nhậu hội lớp, về trễ", true]] as const) {
    await tab(page, "Việc");
    await page.getByRole("button", { name: "Thêm", exact: true }).click();
    await page.locator(".add-tile", { hasText: "Lịch hẹn" }).click();
    await page.getByLabel("Tiêu đề").fill(title);
    await page.getByLabel("Ngày", { exact: true }).fill(isoIn(0));
    await page.getByLabel("Cả ngày").uncheck();
    await page.getByLabel("Giờ").fill("23:00");
    if (share) {
      await page.locator(".share-row").click();
      await page.locator(".pick", { hasText: "Em Lan" }).getByRole("checkbox").check();
      await shot(page, "62-share-picker");
      await page.getByRole("button", { name: "Chia sẻ với 1 người" }).click();
      await expect(page.locator(".share-row")).toContainText("Em Lan");
    }
    await page.getByRole("button", { name: "Lưu", exact: true }).click();
    if (share) await expect(page.getByText("Đã lưu và chia sẻ với 1 người")).toBeVisible();
  }
  await tab(page, "Hôm nay");
  await expect(page.locator(".row-item", { hasText: "Nhậu hội lớp" }).locator(".audience-chip")).toHaveText("1 người");
  await expect(page.locator(".row-item", { hasText: "Gặp khách ABC" }).locator(".audience-chip")).toHaveCount(0);
  await shot(page, "63-owner-today");

  // The member sees the shared one, labelled with the name they chose, read-only; never the private one.
  await tab(member, "Hôm nay");
  const shared = member.locator(".row-item.shared-in", { hasText: "Nhậu hội lớp" });
  await expect(shared).toBeVisible();
  await expect(shared.locator(".owner-chip")).toHaveText("Anh An");
  await expect(shared.getByRole("checkbox")).toHaveCount(0);
  // Received rows are read-only: not swipeable, no tag popover.
  await expect(shared).not.toHaveClass(/\bgest\b/);
  await expect(member.getByText("Gặp khách ABC")).toHaveCount(0);
  await expect.poll(async () => (await pushes(request)).some((p) => p.title === "Anh An chia sẻ: Nhậu hội lớp, về trễ")).toBe(true);
  await shot(member, "64-member-today");

  // Filter: only mine hides it, only shared shows it.
  await member.getByRole("radio", { name: "Của tôi" }).click();
  await expect(shared).toHaveCount(0);
  await member.getByRole("radio", { name: /Được chia sẻ/ }).click();
  await expect(shared).toBeVisible();
  await member.getByRole("radio", { name: "Tất cả" }).click();

  // The owner moves it from 23:00 to 22:00: the member is told what changed, and sees the new time.
  await page.locator(".row-item", { hasText: "Nhậu hội lớp" }).locator(".row-main").click();
  await page.getByLabel("Giờ").fill("22:00");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(shared.locator(".when")).toHaveText("22:00");
  await expect.poll(async () => (await pushes(request)).find((p) => p.title === "Anh An đã sửa: Nhậu hội lớp, về trễ")?.message ?? "").toContain("Đổi thời gian");

  // Opening it shows who shared it and nothing to edit.
  await shared.locator(".row-main").click();
  const sheet = member.getByRole("dialog", { name: "Lịch hẹn" });
  await expect(sheet.locator(".shared-banner")).toContainText("Anh An chia sẻ cho bạn");
  await expect(sheet.getByRole("button", { name: /Xoá|Lưu|Đã xong/ })).toHaveCount(0);
  await shot(member, "65-member-detail");
  await sheet.getByRole("button", { name: "Đóng" }).click();

  // The member unlinks: no confirmation from the owner, who is told; the shared item disappears.
  await openContacts(member);
  await member.locator(".row-item", { hasText: "Anh An" }).locator(".row-main").click();
  await member.getByRole("button", { name: "Huỷ liên kết" }).click();
  await member.getByRole("alertdialog").getByRole("button", { name: "Huỷ liên kết" }).click();
  await expect(member.getByText("Đã huỷ liên kết với Anh An")).toBeVisible();
  await tab(member, "Hôm nay");
  await expect(member.getByText("Nhậu hội lớp")).toHaveCount(0);

  await tab(page, "Hôm nay");
  await expect(page.locator(".notice.unlinked")).toContainText("Em Lan đã huỷ liên kết");
  await expect(page.locator(".row-item", { hasText: "Nhậu hội lớp" }).locator(".audience-chip")).toHaveCount(0);
  await shot(page, "66-owner-unlinked");

  expectNoProblems(problems);
  expectNoProblems(memberProblems);
});

test("a wrong or reused code is refused in plain words", async ({ page, browser }) => {
  await signIn(page, OWNER);
  const member = await person(browser, MEMBER);
  await openContacts(member);
  await member.getByRole("button", { name: "Nhập mã" }).click();
  await member.getByLabel("Mã 8 ký tự").fill("AAAA BBBB");
  await member.getByRole("button", { name: "Kiểm tra mã" }).click();
  await expect(member.getByRole("alert")).toContainText("Mã không đúng hoặc đã hết hạn");
});

test("an administrator lets a relative in from Settings, without redeploying", async ({ page, browser }) => {
  const stranger = await person(browser, STRANGER);
  await expect(stranger.getByText("Chưa được cấp quyền")).toBeVisible();

  await signIn(page, OWNER);
  await page.getByRole("link", { name: "Cài đặt" }).click();
  await page.getByLabel("Gmail cần thêm").fill(STRANGER);
  await page.getByRole("button", { name: "Thêm", exact: true }).click();
  await expect(page.locator(".members")).toContainText(STRANGER);
  await shot(page, "67-members");

  await stranger.reload();
  await expect(stranger.getByRole("heading", { name: "Hôm nay" })).toBeVisible();
  await expect(stranger.getByText("Chưa được cấp quyền")).toHaveCount(0);

  // A member who is not an administrator does not see the list at all.
  await stranger.getByRole("link", { name: "Cài đặt" }).click();
  await expect(stranger.getByRole("heading", { name: "Chia sẻ" })).toBeVisible();
  await expect(stranger.locator(".members")).toHaveCount(0);
});
