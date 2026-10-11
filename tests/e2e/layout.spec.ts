import { expect, test, type Page } from "@playwright/test";
import { idTokenFor, isoIn, OWNER, seed, signIn } from "./helpers";

// iPhone 12 Pro Max: 428 × 926 CSS pixels, 3× screen.
test.use({ viewport: { width: 428, height: 926 }, deviceScaleFactor: 3, hasTouch: true, isMobile: true });

const shot = (page: Page, name: string) => page.screenshot({ path: `e2e/.artifacts/${name}.png` });

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
});

/** Nothing wider than the screen: no sideways scrolling, no element sticking out. */
async function expectFits(page: Page, where: string) {
  const r = await page.evaluate(() => {
    const vw = document.documentElement.clientWidth;
    const wide = [...document.querySelectorAll("body *")]
      .filter((el) => {
        const b = el.getBoundingClientRect();
        // Horizontally scrolling rows (the chip filters) are allowed to hold wider content.
        const scroller = el.closest(".chips");
        return !scroller && b.width > 0 && (b.right > vw + 1 || b.left < -1);
      })
      .slice(0, 5)
      .map((el) => `${el.tagName}.${(el as HTMLElement).className}`);
    return { scroll: document.documentElement.scrollWidth, vw, wide };
  });
  expect(r.scroll, `${where}: page wider than the screen`).toBeLessThanOrEqual(r.vw);
  expect(r.wide, `${where}: elements outside the screen`).toEqual([]);
}

test("every screen fits an iPhone 12 Pro Max, and zooming is switched off", async ({ page, request }) => {
  const who = await idTokenFor(request, OWNER);
  const now = Date.now();
  await seed(request, who, "customers", "c1", { status: "OPEN", name: "Công ty TNHH Thương mại Dịch vụ Rất Dài Tên", contact: "chị Vân", createdAt: now, updatedAt: now });
  await seed(request, who, "licenses", "l1", { customerId: "c1", status: "OPEN", product: "Microsoft Office 365 Business Premium", kind: "LICENSE", endDate: isoIn(20), stage: "ACTIVE", stageAt: now, createdAt: now, updatedAt: now });
  await seed(request, who, "items", "i1", { type: "EVENT", status: "OPEN", title: "Họp với anh Nam về hợp đồng gia hạn phần mềm kế toán năm 2027", details: "", place: "Văn phòng ABC tầng 12", allDay: false, whenAt: now + 3_600_000, customerId: "c1", createdAt: now });
  await seed(request, who, "items", "i2", { type: "TASK", status: "OPEN", title: "Gửi báo giá", details: "", allDay: true, whenAt: now, important: true, createdAt: now });
  await signIn(page, OWNER);

  const meta = await page.locator('meta[name="viewport"]').getAttribute("content");
  expect(meta).toContain("maximum-scale=1");
  expect(meta).toContain("user-scalable=no");
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).touchAction)).toBe("pan-x pan-y");
  // Safari's pinch gesture is cancelled.
  expect(await page.evaluate(() => {
    const e = new Event("gesturestart", { cancelable: true });
    document.dispatchEvent(e);
    return e.defaultPrevented;
  })).toBe(true);
  // No field under 16px (iOS zooms into those when they get focus).
  await page.locator(".tabbar").getByText("Việc", { exact: true }).click();
  await page.getByRole("button", { name: "Thêm" }).click();
  await page.locator(".add-tile", { hasText: "Lịch hẹn" }).click();
  const small = await page.evaluate(() => [...document.querySelectorAll("input, select, textarea")].filter((el) => parseFloat(getComputedStyle(el).fontSize) < 16).length);
  expect(small).toBe(0);
  await expectFits(page, "editor");
  await shot(page, "40-editor-428");
  await page.getByRole("button", { name: "Đóng" }).click();

  const screens: [string, () => Promise<unknown>][] = [
    ["today", () => page.locator(".tabbar").getByText("Hôm nay", { exact: true }).click()],
    ["customers", () => page.locator(".tabbar").getByText("Khách", { exact: true }).click()],
    ["customer", () => page.getByRole("link", { name: /Công ty TNHH/ }).click()],
    ["renewals", () => page.locator(".tabbar").getByText("Gia hạn", { exact: true }).click()],
    ["work", () => page.locator(".tabbar").getByText("Việc", { exact: true }).click()],
    ["calendar", () => page.locator(".chips").getByRole("button", { name: /^Lịch/ }).click()],
    ["settings", () => page.goto("/#/cai-dat")],
  ];
  for (const [name, open] of screens) {
    await open();
    await page.waitForTimeout(200);
    await expectFits(page, name);
    await shot(page, `41-${name}-428`);
  }
});
