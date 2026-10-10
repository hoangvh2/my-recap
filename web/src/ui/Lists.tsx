import { useMemo, useState } from "preact/hooks";
import { dayLabel } from "../lib/format";
import { formatVnd } from "../lib/money";
import type { Item } from "../lib/model";
import { expensesOfMonth, monthKey, overview, shiftMonth } from "../lib/views";
import { Icon } from "./icons";
import { ItemRow } from "./ItemRow";

interface Handlers {
  onOpen: (i: Item) => void;
  onToggle: (i: Item, done: boolean) => void;
}

function Section(props: { title: string; items: Item[]; tone?: "warn"; showDate?: boolean } & Handlers) {
  if (props.items.length === 0) return null;
  return (
    <section class="block">
      <h3 class={props.tone ?? ""}>{props.title} <span class="count">{props.items.length}</span></h3>
      <ul class="list">
        {props.items.map((i) => <ItemRow key={i.id} item={i} onOpen={props.onOpen} onToggle={props.onToggle} showDate={props.showDate} />)}
      </ul>
    </section>
  );
}

const Empty = ({ children }: { children: string }) => <p class="empty">{children}</p>;

export function Overview({ items, ...h }: { items: Item[] } & Handlers) {
  const o = useMemo(() => overview(items, Date.now()), [items]);
  const todayExpense = useMemo(() => {
    const k = monthKey(Date.now());
    return expensesOfMonth(items, k).total;
  }, [items]);
  const empty = !o.overdue.length && !o.today.length && !o.upcoming.length && !o.someday.length;
  return (
    <>
      <Section title="Quá hạn" tone="warn" items={o.overdue} {...h} />
      <Section title="Hôm nay" items={o.today} {...h} showDate={false} />
      <Section title="Sắp tới" items={o.upcoming.slice(0, 30)} {...h} />
      <Section title="Chưa có hạn" items={o.someday} {...h} />
      {empty && <Empty>Chưa có việc hay lịch hẹn nào. Bấm micro và nói thử một câu.</Empty>}
      {todayExpense > 0 && <p class="footnote">Chi tiêu tháng này: <strong>{formatVnd(todayExpense)}</strong></p>}
    </>
  );
}

export function Tasks({ items, ...h }: { items: Item[] } & Handlers) {
  const [showDone, setShowDone] = useState(false);
  const open = items.filter((i) => i.type === "TASK" && i.status === "OPEN").sort((a, b) => (a.whenAt ?? Infinity) - (b.whenAt ?? Infinity) || a.createdAt - b.createdAt);
  const done = items.filter((i) => i.type === "TASK" && i.status === "DONE").sort((a, b) => (b.doneAt ?? 0) - (a.doneAt ?? 0)).slice(0, 50);
  return (
    <>
      <Section title="Cần làm" items={open} {...h} />
      {open.length === 0 && <Empty>Không có việc nào đang chờ.</Empty>}
      {done.length > 0 && (
        <>
          <button class="btn text" onClick={() => setShowDone(!showDone)}>{showDone ? "Ẩn" : "Hiện"} việc đã xong ({done.length})</button>
          {showDone && <Section title="Đã xong" items={done} {...h} />}
        </>
      )}
    </>
  );
}

export function Events({ items, ...h }: { items: Item[] } & Handlers) {
  const [showPast, setShowPast] = useState(false);
  const now = Date.now();
  const events = items.filter((i) => i.type === "EVENT" && i.status === "OPEN" && i.whenAt !== undefined).sort((a, b) => a.whenAt! - b.whenAt!);
  const isPast = (i: Item) => (i.allDay ? i.whenAt! + 86_400_000 : i.whenAt! + 3_600_000) <= now;
  const upcoming = events.filter((i) => !isPast(i));
  const past = events.filter(isPast).reverse().slice(0, 50);
  const groups = new Map<string, Item[]>();
  for (const e of upcoming) {
    const k = dayLabel(e.whenAt!, now);
    groups.set(k, [...(groups.get(k) ?? []), e]);
  }
  return (
    <>
      {[...groups].map(([day, list]) => <Section key={day} title={day} items={list} {...h} showDate={false} />)}
      {upcoming.length === 0 && <Empty>Chưa có lịch hẹn sắp tới.</Empty>}
      {past.length > 0 && (
        <>
          <button class="btn text" onClick={() => setShowPast(!showPast)}>{showPast ? "Ẩn" : "Hiện"} lịch đã qua ({past.length})</button>
          {showPast && <Section title="Đã qua" items={past} {...h} />}
        </>
      )}
    </>
  );
}

export function Notes({ items, ...h }: { items: Item[] } & Handlers) {
  const notes = items.filter((i) => i.type === "NOTE" && i.status !== "DRAFT").sort((a, b) => b.createdAt - a.createdAt);
  return (
    <>
      <Section title="Ghi chú" items={notes} {...h} showDate={false} />
      {notes.length === 0 && <Empty>Chưa có ghi chú.</Empty>}
    </>
  );
}

const MONTHS = (key: string) => `Tháng ${Number(key.slice(5))}/${key.slice(0, 4)}`;

export function Expenses({ items, onOpen }: { items: Item[]; onOpen: (i: Item) => void }) {
  const [key, setKey] = useState(monthKey(Date.now()));
  const s = useMemo(() => expensesOfMonth(items, key), [items, key]);
  const thisMonth = key === monthKey(Date.now());
  return (
    <>
      <div class="month">
        <button class="icon-btn" aria-label="Tháng trước" onClick={() => setKey(shiftMonth(key, -1))}><Icon name="chevronLeft" /></button>
        <div>
          <div class="muted">{MONTHS(key)}</div>
          <div class="big">{formatVnd(s.total)}</div>
        </div>
        <button class="icon-btn" aria-label="Tháng sau" disabled={thisMonth} onClick={() => setKey(shiftMonth(key, 1))}><Icon name="chevronRight" /></button>
      </div>
      {s.byCategory.length > 0 && (
        <ul class="bars">
          {s.byCategory.map((c) => (
            <li key={c.category}>
              <span>{c.category}</span>
              <span class="bar"><i style={{ width: `${Math.max(4, Math.round((c.total / s.total) * 100))}%` }} /></span>
              <span class="amount">{formatVnd(c.total)}</span>
            </li>
          ))}
        </ul>
      )}
      {s.items.length > 0 ? (
        <ul class="list">{s.items.map((i) => <ItemRow key={i.id} item={i} onOpen={onOpen} />)}</ul>
      ) : (
        <Empty>Chưa có khoản chi nào trong tháng này.</Empty>
      )}
    </>
  );
}
