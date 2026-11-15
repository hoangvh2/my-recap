import { expect, test } from "@playwright/test";
import { idTokenFor, OWNER, seed, signIn, tab } from "./helpers";

// The "Lịch" list also holds dated tasks, so only Việc and Chi tiêu can assert what must stay hidden.
// Editing one item must leave the person on the list they were on (and the scroll/filters with it), so the
// next item is one tap away. The Android app had the same bug: it bounced back to "Tổng quan" after Lưu.
test.use({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, hasTouch: true, isMobile: true });

test.beforeEach(async ({ request }) => {
  await request.delete("http://127.0.0.1:8080/emulator/v1/projects/demo-myrecap/databases/(default)/documents");
});

const KINDS = [
  { chip: /^Việc/, type: "TASK", other: "Hẹn giữ tab", a: "Dọn giữ tab A", b: "Dọn giữ tab B" },
  { chip: /^Lịch/, type: "EVENT", other: null, a: "Hẹn giữ tab A", b: "Hẹn giữ tab B" },
  { chip: /^Chi tiêu/, type: "EXPENSE", other: "Dọn giữ tab", a: "Chi giữ tab A", b: "Chi giữ tab B" },
] as const;

for (const k of KINDS) {
  test(`saving a ${k.type} keeps the ${k.type} list open`, async ({ page, request }) => {
    const who = await idTokenFor(request, OWNER);
    const now = Date.now();
    const when = now + 2 * 3_600_000;
    const put = (id: string, type: string, title: string, extra: Record<string, number | string> = {}) =>
      seed(request, who, "items", id, { type, status: "OPEN", title, details: "", allDay: false, whenAt: when, createdAt: now, ...extra });
    await put("t-a", "TASK", "Dọn giữ tab A");
    await put("t-b", "TASK", "Dọn giữ tab B");
    await put("e-a", "EVENT", "Hẹn giữ tab A");
    await put("e-b", "EVENT", "Hẹn giữ tab B");
    await put("x-a", "EXPENSE", "Chi giữ tab A", { amount: 50_000, category: "Ăn uống" });
    await put("x-b", "EXPENSE", "Chi giữ tab B", { amount: 70_000, category: "Ăn uống" });
    await signIn(page, OWNER);
    await tab(page, "Việc");
    const chip = page.locator("nav.chips").getByRole("button", { name: k.chip });
    await chip.click();
    await expect(chip).toHaveAttribute("aria-pressed", "true");
    if (k.other) await expect(page.getByText(k.other)).toHaveCount(0);

    for (const [from, to] of [[k.a, `${k.a} sửa`], [k.b, `${k.b} sửa`]]) {
      await page.getByText(from, { exact: true }).click();
      const editor = page.getByRole("dialog", { name: "Sửa" });
      await editor.getByLabel("Tiêu đề").fill(to);
      await editor.getByRole("button", { name: "Lưu", exact: true }).click();
      await expect(editor).toHaveCount(0);
      // Same list, not bounced to the overview: the chip is still on and the other kinds stay hidden.
      await expect(page.getByText(to, { exact: true })).toBeVisible();
      await expect(chip).toHaveAttribute("aria-pressed", "true");
      if (k.other) await expect(page.getByText(k.other)).toHaveCount(0);
    }
  });
}
