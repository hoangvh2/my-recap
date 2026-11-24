import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { expectNoProblems, idTokenFor, OWNER, signIn, watchBrowser } from "./helpers";

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png`, fullPage: true });
const TOKEN = "t".repeat(60);

/** A device document written as the server would (the emulator's owner token skips the rules). */
async function device(request: APIRequestContext, uid: string, id: string, data: { platform: string; name?: string; find?: boolean; updatedAt?: number }) {
  const fields: Record<string, unknown> = {
    token: { stringValue: TOKEN + id },
    platform: { stringValue: data.platform },
    updatedAt: { integerValue: String(data.updatedAt ?? Date.now()) },
  };
  if (data.name) fields.name = { stringValue: data.name };
  if (data.find !== undefined) fields.find = { booleanValue: data.find };
  const res = await request.patch(`http://127.0.0.1:8080/v1/projects/demo-myrecap/databases/(default)/documents/users/${uid}/devices/${id}`, {
    headers: { authorization: "Bearer owner" },
    data: { fields },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
});

test("Tìm điện thoại: only my Android phones that allow it are listed, Ring and Stop send a command, a second ring too soon is held back", async ({ page, request }) => {
  const problems = watchBrowser(page);
  const { uid } = await idTokenFor(request, OWNER);
  await device(request, uid, "phone1", { platform: "android", name: "Tecno Spark 10", find: true });
  await device(request, uid, "phone2", { platform: "android", name: "Máy cũ đã tắt", find: false });
  await device(request, uid, "browser1", { platform: "web" });

  await signIn(page, OWNER);
  await page.getByRole("link", { name: "Cài đặt" }).click();
  const list = page.getByRole("list", { name: "Điện thoại Android của bạn" });
  await expect(list.getByRole("listitem")).toHaveCount(1);
  await expect(list).toContainText("Tecno Spark 10");
  await expect(list).not.toContainText("Máy cũ đã tắt");
  await shot(page, "70-find-phone");

  await page.getByRole("button", { name: "Đổ chuông Tecno Spark 10" }).click();
  await expect(page.getByRole("status")).toContainText("Đã gửi lệnh đổ chuông tới Tecno Spark 10");
  // Straight away again: the server holds it back, and says so in words.
  await page.getByRole("button", { name: "Đổ chuông Tecno Spark 10" }).click();
  await expect(page.getByRole("status")).toContainText("Vừa gửi lệnh xong");
  // Stopping is never held back.
  await page.getByRole("button", { name: "Dừng chuông Tecno Spark 10" }).click();
  await expect(page.getByRole("status")).toContainText("Đã gửi lệnh dừng tới Tecno Spark 10");
  expectNoProblems(problems);
});

test("Tìm điện thoại: with no phone signed in it explains what to do", async ({ page }) => {
  const problems = watchBrowser(page);
  await signIn(page, OWNER);
  await page.getByRole("link", { name: "Cài đặt" }).click();
  await expect(page.getByText("Chưa có điện thoại nào")).toBeVisible();
  expectNoProblems(problems);
});
