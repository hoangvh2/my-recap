import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { expectNoProblems, idTokenFor, OWNER, seed, signIn, watchBrowser } from "./helpers";

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png`, fullPage: true });
const KEY = "uQiRzpo4DXghDmr9QzzfQu27cmVRsG"; // 30 letters and digits, the shape of a Pushover user key
const TICK = "http://127.0.0.1:5001/demo-myrecap/asia-southeast1/notifytick";

type Push = Record<string, string>;
const pushes = async (request: APIRequestContext) => (await (await request.get("http://127.0.0.1:8788/pushes")).json()) as Push[];
const tick = async (request: APIRequestContext, now: number) => {
  const res = await request.post(`${TICK}?now=${now}`);
  expect(res.ok(), await res.text()).toBeTruthy();
  return res.json() as Promise<{ users: number; sent: number }>;
};

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

async function enable(page: Page) {
  await page.getByRole("link", { name: "Cài đặt" }).click();
  await page.getByLabel("User Key của Pushover").fill(KEY);
  await page.getByRole("button", { name: "Lưu và bật" }).click();
  await expect(page.locator(".notify .status")).toContainText("Đang bật");
}

test("Pushover is set up from Settings: a wrong key is refused, a right one is checked and a test arrives", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("link", { name: "Cài đặt" }).click();
  await expect(page.locator(".notify .status")).toContainText("Chưa bật");
  await page.getByLabel("User Key của Pushover").fill("BAD" + KEY.slice(3));
  await page.getByRole("button", { name: "Lưu và bật" }).click();
  await expect(page.locator(".notify .alert")).toContainText("User Key không đúng");

  await page.getByLabel("User Key của Pushover").fill(KEY);
  await page.getByRole("button", { name: "Lưu và bật" }).click();
  await expect(page.locator(".notify .status")).toContainText("Đang bật");
  await page.getByRole("button", { name: "Gửi thử" }).click();
  await expect.poll(async () => (await pushes(request)).length).toBe(1);
  const [test1] = await pushes(request);
  expect(test1).toMatchObject({ user: KEY, priority: "1", html: "1" });
  expect(test1.title).toContain("Ví dụ");
  await shot(page, "30-notify-settings");

  await page.getByRole("button", { name: "Tắt", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Tắt" }).click();
  await expect(page.locator(".notify .status")).toContainText("Chưa bật");
  expectNoProblems(problems);
});

test("the scheduler reminds an appointment before it starts, an important task as an emergency, each only once", async ({ page, request }) => {
  await signIn(page, OWNER);
  await enable(page);
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  const minute = 60_000;
  await seed(request, who, "items", "m1", { type: "EVENT", status: "OPEN", title: "Họp với anh Nam", details: "Mang hợp đồng", place: "VP ABC", allDay: false, whenAt: now + 10 * minute, createdAt: now });
  await seed(request, who, "items", "t1", { type: "TASK", status: "OPEN", title: "Đóng tiền học cho con", details: "", allDay: false, whenAt: now - minute, important: true, createdAt: now });
  await seed(request, who, "items", "d1", { type: "TASK", status: "DONE", title: "Việc đã xong", details: "", allDay: false, whenAt: now - minute, createdAt: now });
  await seed(request, who, "items", "f1", { type: "EVENT", status: "OPEN", title: "Lịch còn xa", details: "", allDay: false, whenAt: now + 5 * 60 * minute, createdAt: now });

  expect(await tick(request, now)).toMatchObject({ users: 1, sent: 2 });
  const got = await pushes(request);
  const meeting = got.find((p) => p.title.includes("Họp với anh Nam"))!;
  expect(meeting).toMatchObject({ priority: "1", title: "🟠 15 phút nữa · Họp với anh Nam" });
  expect(meeting.message).toContain("📍 VP ABC");
  expect(meeting.message).toContain("Mang hợp đồng");
  expect(Number(meeting.ttl)).toBeGreaterThan(0);
  const urgent = got.find((p) => p.title.includes("Đóng tiền học"))!;
  // Repeats every 5 minutes until acknowledged, for at most 30 minutes after the task's time.
  expect(urgent).toMatchObject({ priority: "2", retry: "300", sound: "persistent" });
  expect(Number(urgent.expire)).toBeGreaterThan(25 * 60);
  expect(Number(urgent.expire)).toBeLessThanOrEqual(30 * 60);
  expect(urgent.title).toContain("🔴 KHẨN");

  // The next minute, nothing new: every reminder goes out once.
  expect(await tick(request, now + minute)).toMatchObject({ sent: 0 });
  expect(await pushes(request)).toHaveLength(2);
});

test("an item can be marked important in the editor, and shows the flag", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.locator(".tabbar").getByText("Việc", { exact: true }).click();
  await page.getByRole("button", { name: "Thêm" }).click();
  await page.locator(".add-tile", { hasText: "Việc" }).first().click();
  await page.getByLabel("Tiêu đề").fill("Nộp hồ sơ visa");
  await page.getByLabel(/Quan trọng/).check();
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.locator(".row-item", { hasText: "Nộp hồ sơ visa" }).locator(".flag")).toBeVisible();
  expectNoProblems(problems);
});

test("an emergency stops as soon as the task is ticked off from the notification's Đã xong link", async ({ page, request }) => {
  await signIn(page, OWNER);
  await enable(page);
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "items", "t1", { type: "TASK", status: "OPEN", title: "Đóng tiền điện", details: "", allDay: false, whenAt: now - 60_000, important: true, createdAt: now });
  await tick(request, now);
  const [urgent] = await pushes(request);
  expect(urgent).toMatchObject({ priority: "2", url_title: "✅ Đã xong", expire: expect.stringMatching(/^\d+$/) });
  expect(Number(urgent.expire)).toBeLessThanOrEqual(30 * 60);
  // What tapping "✅ Đã xong" does: open the app on that link (the URL host is the app's; here the test server).
  const path = new URL(urgent.url ?? "http://x/#/xong/t1").hash;
  expect(path).toBe("#/xong/t1");
  await page.goto(`/${path}`);
  await expect(page.getByText("Đã xong: Đóng tiền điện")).toBeVisible();
  await expect(page).toHaveURL(/#\/$/);
  // The next scheduler run cancels the repeating notification on the phone.
  expect(await tick(request, now + 60_000)).toMatchObject({ cancelled: 1 });
  expect((await pushes(request)).some((p) => p.cancel)).toBe(true);
});
