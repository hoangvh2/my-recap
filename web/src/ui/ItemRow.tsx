import { useEffect, useRef, useState } from "preact/hooks";
import { deleteItems, saveChecklist, saveItem, setDone } from "../data";
import { chipLabel, progress, remainingLabel, reset, tickAll, toggleEntry } from "../lib/checklist";
import { deleteBody, whenLabel } from "../lib/format";
import { formatVnd } from "../lib/money";
import { canTag, RECURRENCE_LABEL, type Item, type ItemTag } from "../lib/model";
import { isHorizontalSwipe, swipeCommit, swipeOffset, type SwipeAction } from "../lib/swipe";
import { tagOf, withTag } from "../lib/tags";
import { confirmDialog, CustomerChip, LicenseChip } from "./common";
import { useAudiences, useReceivedMap, useUid } from "./hooks";
import { Icon, type IconName } from "./icons";
import { ChecklistPanel } from "./Checklist";
import { AudienceChip, openShared, OwnerChip } from "./Sharing";
import { TagCorner, TagPopover, type PopoverAction } from "./TagUI";
import { showToast } from "./toast";

export const TYPE_ICON: Record<Item["type"], IconName> = { TASK: "task", EVENT: "calendar", EXPENSE: "coin", NOTE: "note" };

/** Hold this long (ms) on a row, or right-click it, to pick a tag. */
const LONG_PRESS_MS = 450;
/** Moving further than this (px) before the hold is over means it is a scroll or a swipe, not a hold. */
const HOLD_SLOP = 8;

const failToast = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");

/**
 * One task, appointment, expense or note.
 *
 * Gestures (own, saved items only; never on something another person shared with me or on a draft):
 * swipe left to delete (all types), swipe right to finish / reopen (tasks and appointments), hold or
 * right-click to pick a colour tag. Every gesture has a non-gesture twin: the quick-pick popover also has
 * "Đánh dấu xong" and "Xoá" buttons, the Delete key removes the focused row, and the editor has all of it.
 */
export function ItemRow(props: {
  item: Item;
  onOpen: (i: Item) => void;
  /** How a list ticks a task off (it may show its own toast); without it the row does it itself. */
  onToggle?: (i: Item, done: boolean) => void;
  showDate?: boolean;
  hideCustomer?: boolean;
  /** Timeline layout: the time in a column of its own ("09:30", "Cả ngày"). */
  time?: string;
  /** Already over (a past appointment today): shown dimmed. */
  past?: boolean;
}) {
  const { item } = props;
  const uid = useUid();
  // Something another person shared with me: read-only, labelled with who, opens the read-only view.
  const share = useReceivedMap().get(item.id);
  const audience = useAudiences().get(item.id)?.length ?? 0;
  const onOpen = share ? () => openShared(share.id) : props.onOpen;
  const done = item.status === "DONE";
  const tag = tagOf(item);
  // Gestures and the quick pick only on my own saved items.
  const mine = !share && item.status !== "DRAFT";
  const tickable = mine && canTag(item.type);
  const meta = [
    props.showDate === false ? null : whenLabel(item),
    item.place,
    item.person,
    item.type === "EXPENSE" && item.category ? item.category : null,
  ].filter(Boolean);

  // ---- checklist: the chip opens the card under the row; ticking the last line finishes the item
  const cl = progress(item);
  const hasCl = mine && cl.total > 0;
  const [ckOpen, setCkOpen] = useState(false);
  const changeChecklist = (next: Item, say?: string) => {
    const before = item;
    saveChecklist(uid, item, next).catch(failToast);
    const finishes = progress(next).complete && item.status === "OPEN";
    if (finishes) showToast(`Đã đủ: ${item.title}`, { label: "Hoàn tác", run: () => saveItem(uid, before).catch(failToast) });
    else if (say) showToast(say, { label: "Hoàn tác", run: () => saveItem(uid, before).catch(failToast) });
  };

  // ---- actions (shared by gestures, popover and keyboard)
  const tick = async (next: boolean) => {
    // Finishing something whose checklist is not all ticked asks once, so a swipe cannot skip what was forgotten.
    if (next && hasCl && !cl.complete) {
      const ok = await confirmDialog({ title: `Còn ${cl.remaining.length} mục chưa có. Xong luôn?`, body: remainingLabel(item, 5), ok: "Xong luôn" });
      if (!ok) return;
    }
    if (props.onToggle) return props.onToggle(item, next);
    setDone(uid, item, next).catch(failToast);
    if (next) showToast(`Đã xong: ${item.title}`, { label: "Hoàn tác", run: () => setDone(uid, item, false).catch(failToast) });
  };
  /** Asks first, always: a swipe or a key press must not delete on its own. */
  const remove = async () => {
    const ok = await confirmDialog({ title: "Xoá mục này?", body: deleteBody(item), ok: "Xoá", danger: true });
    if (!ok) return;
    deleteItems(uid, [item.id]).catch(failToast);
    showToast(`Đã xoá: ${item.title}`, { label: "Hoàn tác", run: () => saveItem(uid, item).catch(failToast) });
  };
  const setTag = (t: ItemTag | undefined) => {
    const before = item;
    saveItem(uid, withTag(item, t)).catch(failToast);
    showToast(t ? "Đã gắn thẻ" : "Đã bỏ thẻ", { label: "Hoàn tác", run: () => saveItem(uid, before).catch(failToast) });
  };

  // ---- gesture state
  const li = useRef<HTMLLIElement>(null);
  const [dx, setDx] = useState(0);
  const offset = useRef(0);
  const [settling, setSettling] = useState(false);
  const [pop, setPop] = useState<DOMRect | null>(null);
  const drag = useRef<{ id: number; x: number; y: number; locked: boolean } | null>(null);
  const hold = useRef<number>(0);
  const quiet = useRef<number>(0);
  const swallow = useRef(false);
  const timers = useRef<number[]>([]);
  useEffect(() => () => { clearTimeout(hold.current); clearTimeout(quiet.current); timers.current.forEach(clearTimeout); }, []);

  const stopHold = () => { clearTimeout(hold.current); hold.current = 0; };
  /** The click that follows a swipe or a hold must not open the row. */
  const swallowNextClick = () => {
    swallow.current = true;
    clearTimeout(quiet.current);
    quiet.current = window.setTimeout(() => { swallow.current = false; }, 450);
  };
  const openPop = (fromHold = false) => {
    if (!tickable || !li.current) return;
    stopHold();
    // After a hold the finger lifts and a click follows; a right click has no click to swallow.
    if (fromHold) swallowNextClick();
    setPop(li.current.getBoundingClientRect());
  };

  const onPointerDown = (e: PointerEvent) => {
    if (!mine || (e.pointerType === "mouse" && e.button !== 0) || !e.isPrimary) return;
    // Chips inside the row (customer, licence, owner) are links of their own.
    if ((e.target as HTMLElement).closest(".links, .ck-ui")) return;
    drag.current = { id: e.pointerId, x: e.clientX, y: e.clientY, locked: false };
    swallow.current = false; // a new touch starts a new gesture
    stopHold();
    if (tickable) hold.current = window.setTimeout(() => { drag.current = null; openPop(true); }, LONG_PRESS_MS);
  };
  const onPointerMove = (e: PointerEvent) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.id) return;
    const mx = e.clientX - d.x;
    const my = e.clientY - d.y;
    if (!d.locked) {
      if (Math.abs(mx) > HOLD_SLOP || Math.abs(my) > HOLD_SLOP) stopHold();
      if (isHorizontalSwipe(mx, my)) {
        d.locked = true;
        try { li.current?.setPointerCapture(e.pointerId); } catch { /* the gesture still works without capture */ }
      } else if (Math.abs(my) > HOLD_SLOP) {
        drag.current = null; // a vertical scroll
        return;
      } else return;
    }
    setSettling(false);
    offset.current = swipeOffset(mx, tickable);
    setDx(offset.current);
  };
  const finish = (e: PointerEvent, cancelled: boolean) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.id) return;
    drag.current = null;
    stopHold();
    if (!d.locked) return;
    swallowNextClick();
    try { li.current?.releasePointerCapture(e.pointerId); } catch { /* already released */ }
    const act: SwipeAction | null = cancelled ? null : swipeCommit(offset.current);
    setSettling(true);
    if (!act) {
      offset.current = 0;
      return setDx(0);
    }
    if (act === "delete") {
      // The row springs back and the dialog decides; the list only changes once the person confirms.
      offset.current = 0;
      setDx(0);
      void remove();
      return;
    }
    // Slide the row out, then do it; the list re-renders without the row (or with it reset).
    const width = li.current?.offsetWidth ?? 360;
    setDx(width);
    timers.current.push(window.setTimeout(() => {
      void tick(!done);
      setSettling(false);
      offset.current = 0;
      setDx(0);
    }, 170));
  };

  const onContextMenu = (e: Event) => {
    if (!tickable) return;
    e.preventDefault();
    if (!pop) openPop();
  };
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === "Delete" && mine && (e.target as HTMLElement).closest(".row-main")) {
      e.preventDefault();
      void remove();
    }
  };

  const actions: PopoverAction[] = [
    ...(tickable ? [{ label: done ? "Mở lại" : "Xong", icon: "check" as const, run: () => { setPop(null); void tick(!done); } }] : []),
    { label: "Xoá", icon: "trash" as const, danger: true, run: () => { setPop(null); void remove(); } },
  ];

  const swiping = dx !== 0;
  return (
    <li
      ref={li}
      class={`row-item ${hasCl ? "has-ck" : ""} ${done ? "done" : ""} ${props.time !== undefined ? "tl" : ""} ${props.past ? "past" : ""} ${share ? "shared-in" : ""} ${mine ? "gest" : ""} ${swiping ? "swiping" : ""} ${tag ? "tagged" : ""} t-${item.type}`}
      style={swiping || settling ? { transform: `translateX(${dx}px)`, transition: settling ? "transform 170ms ease-out" : "none" } : undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={(e) => finish(e, false)}
      onPointerCancel={(e) => finish(e, true)}
      onContextMenu={onContextMenu}
      onKeyDown={onKeyDown}
      onTransitionEnd={() => dx === 0 && setSettling(false)}
      onClickCapture={(e) => {
        if (swallow.current) {
          e.preventDefault();
          e.stopPropagation();
          swallow.current = false;
        }
      }}
    >
      {props.time !== undefined && <span class="when">{props.time}</span>}
      <span class={`badge t-${item.type} ${done ? "ok" : ""}`}>
        <Icon name={done ? "check" : item.type === "TASK" ? "circle" : TYPE_ICON[item.type]} size={18} />
        {done && <span class="sr-only">Đã xong</span>}
      </span>
      <button class="row-main" aria-haspopup={tickable ? "dialog" : undefined} onClick={() => onOpen(item)}>
        <span class="title">{item.important && <span class="flag" aria-label="Quan trọng">❗ </span>}{item.title}</span>
        {(meta.length > 0 || item.recurrence) && (
          <span class="meta">
            {meta.join(" · ")}
            {item.recurrence && <span class="rep"> <Icon name="repeat" size={12} /> {RECURRENCE_LABEL[item.recurrence]}</span>}
          </span>
        )}
        {hasCl && !cl.complete && <span class="ck-rem">{remainingLabel(item)}</span>}
      </button>
      {hasCl && (
        <button type="button" class={`ck-chip ck-ui ${cl.complete ? "full" : ""}`} aria-expanded={ckOpen} aria-label={`Checklist ${chipLabel(item)}. ${ckOpen ? "Thu gọn" : "Mở để tick"}`} onClick={() => setCkOpen(!ckOpen)}>
          <Icon name="check" size={14} /> {chipLabel(item)}
        </button>
      )}
      {item.type === "EXPENSE" && item.amount !== undefined && <span class="amount">{formatVnd(item.amount)}</span>}
      {((!props.hideCustomer && item.customerId) || item.licenseId || share || audience > 0) && (
        <div class="links">
          {share && <OwnerChip share={share} />}
          {!share && audience > 0 && <AudienceChip item={item} count={audience} />}
          {!props.hideCustomer && <CustomerChip id={item.customerId} />}
          <LicenseChip id={item.licenseId} />
        </div>
      )}
      {hasCl && ckOpen && (
        <ChecklistPanel
          item={item}
          remaining={cl.remaining}
          ticked={cl.ticked}
          onToggle={(id) => changeChecklist(toggleEntry(item, id))}
          onAll={() => changeChecklist(tickAll(item))}
          onReset={() => changeChecklist(reset(item), "Đã đặt lại checklist")}
        />
      )}
      {tag && <TagCorner tag={tag} />}
      {swiping && dx < 0 && <span class="swipe-act del" aria-hidden="true"><Icon name="trash" size={20} /> Xoá</span>}
      {swiping && dx > 0 && tickable && <span class="swipe-act ok" aria-hidden="true"><Icon name="check" size={20} /> {done ? "Mở lại" : "Xong"}</span>}
      {pop && (
        <TagPopover
          anchor={pop}
          current={tag}
          actions={actions}
          onPick={(t) => { setPop(null); setTag(t); }}
          onClose={() => setPop(null)}
        />
      )}
    </li>
  );
}
