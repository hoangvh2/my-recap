/**
 * Reads Vietnamese spoken/written amounts: "85k", "85 nghìn", "1tr2", "1 triệu 2", "1,5 triệu",
 * "1.200.000đ", "2 tỷ". Returns whole VND, or null when there is no amount.
 */
export function parseVnd(text: string): number | null {
  const s = text.toLowerCase().replaceAll("đồng", "").replaceAll("vnd", "").replaceAll("đ", "").trim();
  if (!s) return null;
  const unit = /(\d+(?:[.,]\d+)?)\s*(tỷ|ty|triệu|trieu|tr|m|nghìn|ngàn|ngan|nghin|k)\s*(\d{1,3})?/.exec(s);
  if (unit) {
    const base = Number(unit[1].replace(",", "."));
    if (!Number.isFinite(base)) return null;
    const u = unit[2];
    const mul = u === "tỷ" || u === "ty" ? 1_000_000_000 : ["triệu", "trieu", "tr", "m"].includes(u) ? 1_000_000 : 1_000;
    const tail = unit[3];
    const extra = tail && mul >= 1_000_000 ? (Number(tail) / 10 ** tail.length) * mul : 0;
    const v = Math.round(base * mul + extra);
    return v > 0 ? v : null;
  }
  const digits = s.replace(/\D/g, "");
  if (!digits || digits.length > 15) return null;
  const v = Number(digits);
  return v > 0 ? v : null;
}

/** 1250000 → "1.250.000 đ" */
export function formatVnd(amount: number): string {
  return `${String(Math.trunc(amount)).replace(/\B(?=(\d{3})+(?!\d))/g, ".")} đ`;
}
