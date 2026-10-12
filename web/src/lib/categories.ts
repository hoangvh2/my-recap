import type { Item } from "./model";
import { fold } from "./views";

/**
 * Keywords per category, on folded text; checked in order so "vé phim" lands on entertainment and
 * "mua điện thoại" on shopping, not bills. Port of the Android `Categories`.
 */
const KEYWORDS: [string, string[]][] = [
  ["Giải trí", ["phim", "netflix", "spotify", "karaoke", "game", "du lich"]],
  ["Ăn uống", ["ca phe", "cafe", "coffee", "an", "com", "pho", "bun", "banh", "tra sua", "tra da", "nuoc uong", "bia", "nhau", "lau", "nuong", "an sang", "an trua", "an toi"]],
  ["Di chuyển", ["grab", "be bike", "be car", "xanh sm", "xang", "taxi", "gui xe", "ve xe", "ve may bay", "ve tau", "do xe", "sua xe"]],
  ["Mua sắm", ["shopee", "lazada", "tiki", "mua", "quan ao", "giay", "sieu thi"]],
  ["Hoá đơn", ["dien", "nuoc", "internet", "wifi", "tien nha", "thue nha", "dien thoai", "4g", "truyen hinh"]],
  ["Sức khoẻ", ["thuoc", "kham", "benh vien", "nha khoa", "gym", "bao hiem"]],
  ["Giáo dục", ["sach", "hoc", "khoa hoc", "hoc phi"]],
  ["Gia đình", ["bo me", "sinh nhat", "dam cuoi", "dam gio", "mung cuoi"]],
];

export const OTHER = "Khác";

/**
 * The category the user gave the latest expense with the same title, else a keyword guess, else
 * "Khác". History wins so the user's own correction sticks.
 */
export function guessCategory(title: string, history: Item[]): string {
  const key = fold(title);
  let latest: Item | undefined;
  for (const i of history) {
    if (i.type !== "EXPENSE" || !i.category?.trim() || fold(i.title) !== key) continue;
    if (!latest || i.createdAt > latest.createdAt) latest = i;
  }
  if (latest) return latest.category!;
  for (const [category, words] of KEYWORDS) {
    if (words.some((w) => new RegExp(`\\b${w}\\b`).test(key))) return category;
  }
  return OTHER;
}
