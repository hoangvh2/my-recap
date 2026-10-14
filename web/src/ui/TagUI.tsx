import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { createPortal } from "preact/compat";
import { ITEM_TAGS, type ItemTag } from "../lib/model";
import { createStore, useStore } from "../lib/store";
import { filterByTag, hasTaggedOpen, TAG_INFO, tagLabel, type TagFilter } from "../lib/tags";
import { Icon } from "./icons";
import type { Item } from "../lib/model";

/** A round colour swatch with the tag's glyph in it: the colour is never the only signal. */
export function TagDot({ tag, size = 28 }: { tag: ItemTag; size?: number }) {
  return <i class={`tg tag-${tag}`} aria-hidden="true" style={{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.5)}px` }}>{TAG_INFO[tag].glyph}</i>;
}

/** The triangle at the top-right of a row: colour plus glyph, with a text label for screen readers. */
export function TagCorner({ tag }: { tag: ItemTag }) {
  return (
    <span class={`tag-corner tag-${tag}`} role="img" aria-label={tagLabel(tag)}>
      <b aria-hidden="true">{TAG_INFO[tag].glyph}</b>
    </span>
  );
}

export interface PopoverAction {
  label: string;
  icon: "check" | "trash";
  danger?: boolean;
  run: () => void;
}

/**
 * The quick pick opened by a long press or a right click on a row: five tags, "Bỏ thẻ", and the same
 * actions the swipes do, so nothing depends on a gesture. Anchored to the row, drawn on top of everything.
 */
export function TagPopover(props: { anchor: DOMRect; current: ItemTag | undefined; actions: PopoverAction[]; onPick: (t: ItemTag | undefined) => void; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number }>({ top: -999, left: 0 });
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    const vw = document.documentElement.clientWidth;
    const vh = window.innerHeight;
    const a = props.anchor;
    const left = Math.max(8, Math.min(a.right - w - 12, vw - w - 8));
    const below = a.bottom + 6;
    const top = below + h <= vh - 8 ? below : Math.max(8, a.top - h - 6);
    setPos({ top, left });
  }, [props.anchor]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") props.onClose();
    };
    document.addEventListener("keydown", onKey);
    box.current?.querySelector<HTMLButtonElement>("button")?.focus({ preventScroll: true });
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return createPortal(
    <div class="pop-backdrop" onPointerDown={(e) => e.target === e.currentTarget && props.onClose()} onContextMenu={(e) => { e.preventDefault(); if (e.target === e.currentTarget) props.onClose(); }}>
      <div ref={box} class="tag-pop" role="dialog" aria-label="Chọn thẻ" style={{ top: `${pos.top}px`, left: `${pos.left}px` }}>
        <div class="tag-dots" role="group" aria-label="Thẻ">
          {ITEM_TAGS.map((t) => (
            <button key={t} type="button" class={`tag-pick ${props.current === t ? "sel" : ""}`} aria-label={tagLabel(t)} aria-pressed={props.current === t} onClick={() => props.onPick(t)}>
              <TagDot tag={t} size={34} />
            </button>
          ))}
        </div>
        <button type="button" class="pop-btn" onClick={() => props.onPick(undefined)} disabled={props.current === undefined}>Bỏ thẻ</button>
        {props.actions.length > 0 && (
          <div class="pop-actions">
            {props.actions.map((a) => (
              <button key={a.label} type="button" class={`pop-btn ${a.danger ? "danger-text" : ""}`} onClick={a.run}><Icon name={a.icon} size={16} /> {a.label}</button>
            ))}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}

/** The list opened from the editor's corner: colour dot, glyph, name, and "Không thẻ". */
export function TagMenu(props: { current: ItemTag | undefined; onPick: (t: ItemTag | undefined) => void; onClose: () => void }) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    box.current?.querySelector<HTMLButtonElement>("button")?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        props.onClose();
      }
    };
    const onDown = (e: PointerEvent) => {
      const t = e.target as HTMLElement;
      if (!box.current?.contains(t) && !t.closest(".tag-trigger")) props.onClose();
    };
    document.addEventListener("keydown", onKey, true);
    document.addEventListener("pointerdown", onDown);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      document.removeEventListener("pointerdown", onDown);
    };
  }, []);
  return (
    <div ref={box} class="tag-menu" role="menu" aria-label="Chọn thẻ">
      {ITEM_TAGS.map((t) => (
        <button key={t} type="button" role="menuitemradio" aria-checked={props.current === t} class={props.current === t ? "sel" : ""} onClick={() => props.onPick(t)}>
          <TagDot tag={t} size={26} />
          <span class="glyph" aria-hidden="true">{TAG_INFO[t].glyph}</span>
          <span class="name">{TAG_INFO[t].label}</span>
          {props.current === t && <Icon name="check" size={16} />}
        </button>
      ))}
      <button type="button" role="menuitemradio" aria-checked={props.current === undefined} class={props.current === undefined ? "sel none" : "none"} onClick={() => props.onPick(undefined)}>
        <svg class="no-tag" width="26" height="26" viewBox="0 0 26 26" aria-hidden="true"><circle cx="13" cy="13" r="11" /></svg>
        <span class="name">Không thẻ</span>
        {props.current === undefined && <Icon name="check" size={16} />}
      </button>
    </div>
  );
}

/** The corner control of the editor: a diagonal tag when one is set, a dashed corner outline when not. */
export function TagCornerButton({ tag, onClick, expanded }: { tag: ItemTag | undefined; onClick: () => void; expanded: boolean }) {
  return (
    <button type="button" class={`tag-trigger ${tag ? `tag-${tag}` : "unset"}`} aria-label={tag ? `${tagLabel(tag)}. Đổi thẻ` : "Chọn thẻ"} aria-haspopup="menu" aria-expanded={expanded} onClick={onClick}>
      <b aria-hidden="true">{tag ? TAG_INFO[tag].glyph : ""}</b>
    </button>
  );
}

// ---------------------------------------------------------------- list filter

/** The chosen filter, shared by Today and the task / appointment lists. Not saved across reloads. */
export const tagFilter = createStore<TagFilter>(null);

/**
 * "Có thẻ · ! · ★ · ▲ · ? · ♥": shown only when at least one open task or appointment has a tag. Tapping a chip
 * filters the list; tapping it again clears the filter. Returns the list to show along with the bar.
 */
export function useTagFilter(items: Item[]): { shown: Item[]; active: TagFilter; visible: boolean } {
  const active = useStore(tagFilter);
  const visible = hasTaggedOpen(items);
  // The last tagged item went away (done, deleted, untagged): drop the filter so the list is not left empty.
  useEffect(() => {
    if (!visible && active !== null) tagFilter.set(null);
  }, [visible, active]);
  const shown = useMemo(() => (visible ? filterByTag(items, active) : items), [items, active, visible]);
  return { shown, active: visible ? active : null, visible };
}

export function TagFilterBar({ active }: { active: TagFilter }) {
  const toggle = (f: Exclude<TagFilter, null>) => tagFilter.set(active === f ? null : f);
  return (
    <nav class="chips tag-filter" aria-label="Lọc theo thẻ">
      <button type="button" class={active === "any" ? "on" : ""} aria-pressed={active === "any"} onClick={() => toggle("any")}>Có thẻ</button>
      {ITEM_TAGS.map((t) => (
        <button key={t} type="button" class={`tagchip ${active === t ? "on" : ""}`} aria-pressed={active === t} aria-label={tagLabel(t)} onClick={() => toggle(t)}>
          <TagDot tag={t} size={22} />
        </button>
      ))}
    </nav>
  );
}
