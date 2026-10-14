import { canTag, ITEM_TAGS, type Item, type ItemTag } from "./model";
import { createStore } from "./store";

/**
 * Auto-tag rules: "when a new task or appointment has this person / place / word, give it this tag".
 * Matching is a "contains" test that ignores case and Vietnamese accents (NFD without combining marks, đ → d);
 * the first rule in the list that matches wins. The Android app mirrors these semantics exactly.
 *
 * Rules are applied once, when a task or appointment is created or confirmed without a tag, and never again
 * on edit, so a tag the person removed stays removed.
 */
export type RuleField = "person" | "place" | "keyword";
export const RULE_FIELDS: RuleField[] = ["person", "place", "keyword"];
export const RULE_FIELD_LABEL: Record<RuleField, string> = { person: "Với ai", place: "Địa điểm", keyword: "Từ khoá" };

export interface TagRule {
  field: RuleField;
  text: string;
  tag: ItemTag;
}

export const MAX_RULES = 20;
export const MAX_RULE_TEXT = 60;

/** Lower case, accents removed, đ → d: the form both sides of a comparison are put in. */
export function foldText(s: string): string {
  return s.toLowerCase().replaceAll("đ", "d").normalize("NFD").replace(/\p{M}+/gu, "");
}

/** Whether one rule matches an item. An empty rule text never matches. */
export function ruleMatches(rule: TagRule, item: Pick<Item, "title" | "details" | "person" | "place">): boolean {
  const needle = foldText(rule.text.trim());
  if (!needle) return false;
  const hay = rule.field === "person" ? item.person : rule.field === "place" ? item.place : `${item.title}\n${item.details}`;
  return hay !== undefined && foldText(hay).includes(needle);
}

/** The tag of the first matching rule, or undefined. */
export function matchTag(rules: TagRule[], item: Pick<Item, "title" | "details" | "person" | "place">): ItemTag | undefined {
  return rules.find((r) => ruleMatches(r, item))?.tag;
}

/** For a task or appointment that is being created or confirmed: adds the matching tag unless it already has one. */
export function applyAutoTag(rules: TagRule[], item: Item): Item {
  if (!canTag(item.type) || item.tag !== undefined) return item;
  const tag = matchTag(rules, item);
  return tag ? { ...item, tag } : item;
}

/** Keeps the valid rules (known field and tag, non-empty text up to the length cap), at most MAX_RULES. */
export function normalizeRules(raw: unknown): TagRule[] {
  if (!Array.isArray(raw)) return [];
  const out: TagRule[] = [];
  for (const o of raw) {
    const r = o as Partial<TagRule> | null;
    if (!r || typeof r.text !== "string" || !RULE_FIELDS.includes(r.field as RuleField) || !ITEM_TAGS.includes(r.tag as ItemTag)) continue;
    const text = r.text.replace(/\s+/g, " ").trim().slice(0, MAX_RULE_TEXT);
    if (text) out.push({ field: r.field as RuleField, text, tag: r.tag as ItemTag });
    if (out.length >= MAX_RULES) break;
  }
  return out;
}

const KEY = "vhub.tagRules";

function load(): TagRule[] {
  try {
    const raw = typeof localStorage === "undefined" ? null : localStorage.getItem(KEY);
    return raw === null ? [] : normalizeRules(JSON.parse(raw));
  } catch {
    return [];
  }
}

export const tagRules = createStore<TagRule[]>(load());

/** Saves the list; if the browser refuses (private mode) the rules last until the app is closed. */
export function setTagRules(list: TagRule[]): void {
  const next = normalizeRules(list);
  tagRules.set(next);
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    /* kept in memory only */
  }
}
