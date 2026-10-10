import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { expectNoProblems, geminiCalls, OWNER, signIn, STRANGER, textCapture, watchBrowser } from "./helpers";

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png` });

test.beforeEach(async ({ request }) => {
  // A clean database for every test (this also resets the daily quota counter).
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
  await request.get("http://127.0.0.1:8788/reset");
});

test("login screen is the only thing a signed-out visitor sees", async ({ page }) => {
  const problems = watchBrowser(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Đăng nhập bằng Google" })).toBeVisible();
  await expect(page.getByText("Chỉ các tài khoản được cấp quyền")).toBeVisible();
  await expect(page.getByText("Họp anh Nam")).toHaveCount(0);
  await shot(page, "01-login");
  expectNoProblems(problems);
});

test("typed note → drafts to review → saved → shows in the agenda and in expenses", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await expect(page.getByRole("heading", { name: "Thư ký" })).toBeVisible();
  await expect(page.getByText("Chưa có việc hay lịch hẹn nào")).toBeVisible();

  await textCapture(page, "mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn");
  await expect(page.getByText("Chờ bạn xem lại")).toBeVisible();
  const drafts = page.locator(".draft-card");
  await expect(drafts.locator(".title", { hasText: "Họp anh Nam" })).toBeVisible();
  await expect(drafts.locator(".title", { hasText: "Ăn phở" })).toBeVisible();
  await expect(drafts.getByText("65.000 đ")).toBeVisible();
  await shot(page, "02-drafts");

  await drafts.getByRole("button", { name: /Lưu tất cả \(2\)/ }).click();
  await expect(page.getByText("Chờ bạn xem lại")).toHaveCount(0);
  // The appointment is tomorrow at 15:00 → "Sắp tới".
  const upcoming = page.locator(".block", { has: page.getByRole("heading", { name: /Sắp tới/ }) });
  await expect(upcoming.getByText("Họp anh Nam")).toBeVisible();
  await expect(upcoming.getByText(/Mai 15:00/)).toBeVisible();
  await expect(page.getByText(/Chi tiêu tháng này/)).toContainText("65.000 đ");
  await shot(page, "03-agenda");

  await page.getByRole("button", { name: /^Chi tiêu/ }).click();
  await expect(page.locator(".month .big")).toHaveText("65.000 đ");
  await expect(page.locator(".bars")).toContainText("Ăn uống");
  await shot(page, "04-expenses");
  expectNoProblems(problems);
});

test("voice note: the recorder produces a WAV the server accepts and Gemini receives", async ({ page, request }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("button", { name: "Ghi nhanh bằng giọng nói" }).click();
  await expect(page.locator(".timer")).toBeVisible();
  await shot(page, "05-recording");
  await page.waitForTimeout(2_500);
  await page.getByRole("button", { name: /Xong, phân tích/ }).click();
  await expect(page.locator(".draft-card .title", { hasText: "Họp anh Nam" })).toBeVisible({ timeout: 20_000 });
  // The transcript the model heard is shown, so a mis-hearing can be spotted.
  await expect(page.locator(".draft-card summary")).toContainText("mai 3 giờ chiều họp anh Nam");

  const calls = await geminiCalls(request);
  const stt = calls.find((c) => c.kind === "transcribe")!;
  expect(stt.audioMime).toBe("audio/wav");
  expect(stt.audioHead.startsWith("RIFF")).toBe(true);
  expect(stt.audioHead.slice(8, 12)).toBe("WAVE");
  expect(stt.audioBytes).toBeGreaterThan(44 + 16_000 * 2 * 2); // at least ~2 s at 16 kHz mono 16-bit
  expect(stt.key).toBe("fake-e2e-key");
  expectNoProblems(problems);
});

test("manual add, tick off a repeating task, undo, delete", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("button", { name: "Thêm bằng tay" }).click();
  await page.getByRole("menuitem", { name: "Việc" }).click();
  await page.getByLabel("Tiêu đề").fill("Uống thuốc");
  const today = new Date();
  const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  await page.getByLabel(/^Hạn/).fill(iso);
  await page.getByLabel("Cả ngày").uncheck();
  await page.getByLabel("Giờ").fill("23:59");
  await page.getByLabel("Lặp lại").selectOption("DAILY");
  await shot(page, "06-editor");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();

  const row = page.locator(".row-item", { hasText: "Uống thuốc" });
  await expect(row).toHaveCount(1);
  await row.getByRole("checkbox").click();
  // Finishing a repeating task creates tomorrow's: still exactly one open "Uống thuốc".
  await page.getByRole("button", { name: /^Việc/ }).click();
  await expect(page.locator(".block", { has: page.getByRole("heading", { name: /Cần làm/ }) }).locator(".row-item", { hasText: "Uống thuốc" })).toHaveCount(1);
  await expect(page.getByRole("button", { name: /Hiện việc đã xong \(1\)/ })).toBeVisible();

  // Delete with undo.
  await page.locator(".row-item", { hasText: "Uống thuốc" }).first().locator(".row-main").click();
  await page.getByRole("button", { name: /Xoá/ }).click();
  await expect(page.getByText("Đã xoá: Uống thuốc")).toBeVisible();
  await page.getByRole("button", { name: "Hoàn tác" }).click();
  await expect(page.locator(".row-item", { hasText: "Uống thuốc" }).first()).toBeVisible();
  expectNoProblems(problems);
});

test("a Google account that is not on the list is told so and sees nothing", async ({ page }) => {
  await signIn(page, STRANGER);
  await expect(page.getByRole("heading", { name: "Chưa được cấp quyền" })).toBeVisible();
  await expect(page.getByText(STRANGER)).toBeVisible();
  await expect(page.getByRole("heading", { name: "Thư ký" })).toHaveCount(0);
  await shot(page, "07-denied");
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await expect(page.getByRole("button", { name: "Đăng nhập bằng Google" })).toBeVisible();
});

test("exports: calendar file and JSON backup; sign out", async ({ page }) => {
  await signIn(page, OWNER);
  await textCapture(page, "mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn");
  await page.locator(".draft-card").getByRole("button", { name: /Lưu tất cả/ }).click();
  await expect(page.getByText("Chờ bạn xem lại")).toHaveCount(0);

  await page.getByRole("button", { name: "Menu" }).click();
  await shot(page, "08-menu");
  let [dl] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: /Xuất lịch/ }).click()]);
  expect(dl.suggestedFilename()).toBe("my-recap-lich.ics");
  const ics = readFileSync((await dl.path())!, "utf8");
  expect(ics).toContain("BEGIN:VEVENT");
  expect(ics).toContain("SUMMARY:Họp anh Nam");
  expect(ics).toContain("TRIGGER:-PT15M");

  await page.getByRole("button", { name: "Menu" }).click();
  [dl] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: /Sao lưu dữ liệu/ }).click()]);
  const backup = JSON.parse(readFileSync((await dl.path())!, "utf8"));
  expect(backup.items.map((i: { title: string }) => i.title).sort()).toEqual(["Họp anh Nam", "Ăn phở"].sort());

  await page.getByRole("button", { name: "Menu" }).click();
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await expect(page.getByRole("button", { name: "Đăng nhập bằng Google" })).toBeVisible();
  // Signed out: the previous person's data is gone from the screen.
  await expect(page.getByText("Họp anh Nam")).toHaveCount(0);
});

test("personal data never lands in the browser: no localStorage/sessionStorage/IndexedDB copy of items", async ({ page }) => {
  await signIn(page, OWNER);
  await textCapture(page, "mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn");
  await page.locator(".draft-card").getByRole("button", { name: /Lưu tất cả/ }).click();
  await expect(page.getByText(/Chi tiêu tháng này/)).toBeVisible();
  await page.waitForTimeout(1_000);

  const dump = await page.evaluate(async () => {
    const out: Record<string, unknown> = { local: { ...localStorage }, session: { ...sessionStorage }, idb: {} as Record<string, unknown> };
    const dbs = (await indexedDB.databases?.()) ?? [];
    for (const info of dbs) {
      if (!info.name) continue;
      const db = await new Promise<IDBDatabase>((res, rej) => { const r = indexedDB.open(info.name!); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
      const stores: Record<string, unknown> = {};
      for (const name of Array.from(db.objectStoreNames)) {
        stores[name] = await new Promise((res) => { const r = db.transaction(name).objectStore(name).getAll(); r.onsuccess = () => res(r.result); r.onerror = () => res([]); });
      }
      (out.idb as Record<string, unknown>)[info.name] = stores;
      db.close();
    }
    out.caches = await (self.caches ? caches.keys() : Promise.resolve([]));
    return out;
  });
  const text = JSON.stringify(dump);
  for (const secret of ["Họp anh Nam", "Ăn phở", "65000", "phở", "fake-e2e-key"]) expect(text, `found "${secret}" in browser storage`).not.toContain(secret);
  expect(Object.keys(dump.idb as object).some((n) => n.startsWith("firestore/"))).toBe(false);
  expect(dump.caches).toEqual([]);
  // What is there: Firebase's sign-in token and bookkeeping only.
  console.log("browser storage holds:", Object.keys(dump.idb as object), Object.keys(dump.local as object));
});
