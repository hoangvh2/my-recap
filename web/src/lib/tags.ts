import { canTag, ITEM_TAGS, type Item, type ItemTag } from "./model";

/** Visual marks for tasks and appointments. Colour is never the only signal: each has a glyph and a name. */
export const TAG_INFO: Record<ItemTag, { label: string; glyph: string }> = {
  red: { label: "Gấp", glyph: "!" },
  yellow: { label: "Nổi bật", glyph: "★" },
  orange: { label: "Lưu ý", glyph: "▲" },
  blue: { label: "Cần xác nhận", glyph: "?" },
  purple: { label: "Gia đình", glyph: "♥" },
};
export { ITEM_TAGS };

export const tagLabel = (t: ItemTag): string => `Thẻ ${TAG_INFO[t].label}`;

/** A tag that is one of the five known values and sits on something that can carry one. */
export const tagOf = (i: Pick<Item, "type" | "tag">): ItemTag | undefined =>
  canTag(i.type) && i.tag !== undefined && ITEM_TAGS.includes(i.tag) ? i.tag : undefined;

/** The list filter: nothing chosen, any tag, or one colour. */
export type TagFilter = ItemTag | "any" | null;

/** Whether the chip row is worth showing: at least one open task or appointment has a tag. */
export const hasTaggedOpen = (items: Item[]): boolean => items.some((i) => i.status === "OPEN" && tagOf(i) !== undefined);

/** Keeps only what matches the filter; with no filter the list is returned as it is. */
export function filterByTag(items: Item[], f: TagFilter): Item[] {
  if (f === null) return items;
  return items.filter((i) => {
    const t = tagOf(i);
    return f === "any" ? t !== undefined : t === f;
  });
}

/** Sets or clears the tag. Anything that cannot carry one is returned unchanged. */
export function withTag(item: Item, tag: ItemTag | undefined): Item {
  if (!canTag(item.type)) return item;
  const out: Item = { ...item };
  if (tag) out.tag = tag;
  else delete out.tag;
  return out;
}
