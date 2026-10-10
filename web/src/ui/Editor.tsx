import { useEffect, useRef, useState } from "preact/hooks";
import { fromInputs, toDateInput, toTimeInput } from "../lib/format";
import { buildIcs } from "../lib/ics";
import { formatVnd, parseVnd } from "../lib/money";
import { EXPENSE_CATEGORIES, MAX, RECURRENCES, RECURRENCE_LABEL, TYPES, TYPE_LABEL, type Item, type ItemType, type Recurrence } from "../lib/model";
import { saveFile } from "./download";
import { Icon } from "./icons";

export interface EditorProps {
  item: Item;
  isNew: boolean;
  onSave: (item: Item) => void;
  onDelete: (item: Item) => void;
  onClose: () => void;
}

/** Bottom sheet to create or change one item. */
export function Editor({ item, isNew, onSave, onDelete, onClose }: EditorProps) {
  const [type, setType] = useState<ItemType>(item.type);
  const [title, setTitle] = useState(item.title);
  const [details, setDetails] = useState(item.details);
  const [date, setDate] = useState(item.whenAt !== undefined ? toDateInput(item.whenAt) : "");
  const [time, setTime] = useState(item.whenAt !== undefined && !item.allDay ? toTimeInput(item.whenAt) : "");
  const [allDay, setAllDay] = useState(item.whenAt === undefined ? item.type === "TASK" : item.allDay);
  const [amount, setAmount] = useState(item.amount !== undefined ? String(item.amount) : "");
  const [category, setCategory] = useState(item.category ?? "Khác");
  const [place, setPlace] = useState(item.place ?? "");
  const [person, setPerson] = useState(item.person ?? "");
  const [repeat, setRepeat] = useState<Recurrence | "">(item.recurrence ?? "");
  const [error, setError] = useState("");
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);
  // A new appointment usually has a time; a new task usually just a day or nothing.
  useEffect(() => {
    if (isNew && !date) setAllDay(type === "TASK");
  }, [type]);

  const wasDraft = item.status === "DRAFT";
  const dated = type === "TASK" || type === "EVENT";

  const build = (): Item | null => {
    const t = title.trim();
    if (!t) return fail("Cần nhập tiêu đề");
    if (t.length > MAX.title) return fail(`Tiêu đề dài quá ${MAX.title} ký tự`);
    const out: Item = { id: item.id, type, status: wasDraft ? "OPEN" : item.status, title: t, details: details.trim().slice(0, MAX.details), allDay: false, createdAt: item.createdAt };
    if (item.sourceId) out.sourceId = item.sourceId;
    if (item.quote) out.quote = item.quote;
    if (item.doneAt !== undefined && out.status === "DONE") out.doneAt = item.doneAt;
    if (type === "EXPENSE") {
      const v = parseVnd(amount);
      if (v === null) return fail("Số tiền chưa đúng, VD: 85000 hoặc 85k");
      const when = fromInputs(date || toDateInput(Date.now()), "", true);
      if (when === null) return fail("Ngày chưa đúng");
      out.amount = v;
      out.category = category;
      out.whenAt = when;
      out.allDay = true;
    } else if (dated) {
      if (date || type === "EVENT") {
        const eventAllDay = allDay;
        const when = fromInputs(date, time, eventAllDay);
        if (when === null) return fail(type === "EVENT" && !date ? "Lịch hẹn cần có ngày" : eventAllDay ? "Ngày chưa đúng" : "Cần nhập cả ngày và giờ, hoặc chọn Cả ngày");
        out.whenAt = when;
        out.allDay = eventAllDay;
        if (repeat) out.recurrence = repeat;
      }
      if (place.trim()) out.place = place.trim().slice(0, MAX.place);
      if (person.trim()) out.person = person.trim().slice(0, MAX.person);
    }
    return out;
  };
  const fail = (m: string): null => {
    setError(m);
    return null;
  };

  const submit = (e: Event) => {
    e.preventDefault();
    const out = build();
    if (out) onSave(out);
  };

  const addToCalendar = async () => {
    const out = build();
    if (out) await saveFile(`${out.title.slice(0, 40).replace(/[^\p{L}\p{N}]+/gu, "-")}.ics`, "text/calendar", buildIcs([out]));
  };

  return (
    <div class="sheet-backdrop" role="dialog" aria-modal="true" aria-label={isNew ? "Thêm mới" : "Sửa"} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form class="sheet" onSubmit={submit}>
        <header>
          <h2>{isNew ? "Thêm mới" : wasDraft ? "Xem lại" : "Sửa"}</h2>
          <button type="button" class="icon-btn" aria-label="Đóng" onClick={onClose}><Icon name="close" /></button>
        </header>

        {(isNew || wasDraft) && (
          <div class="seg" role="radiogroup" aria-label="Loại">
            {TYPES.map((t) => (
              <button type="button" key={t} role="radio" aria-checked={type === t} class={type === t ? "on" : ""} onClick={() => setType(t)}>{TYPE_LABEL[t]}</button>
            ))}
          </div>
        )}

        <label>Tiêu đề<input ref={first} value={title} maxLength={MAX.title} onInput={(e) => setTitle((e.target as HTMLInputElement).value)} /></label>

        {type === "EXPENSE" && (
          <>
            <label>Số tiền (VND)<input inputMode="decimal" value={amount} placeholder="85000 hoặc 85k" onInput={(e) => setAmount((e.target as HTMLInputElement).value)} />
              {parseVnd(amount) !== null && <small>{formatVnd(parseVnd(amount)!)}</small>}
            </label>
            <label>Nhóm
              <select value={category} onChange={(e) => setCategory((e.target as HTMLSelectElement).value)}>
                {EXPENSE_CATEGORIES.map((c) => <option key={c}>{c}</option>)}
              </select>
            </label>
            <label>Ngày<input type="date" value={date || toDateInput(Date.now())} onInput={(e) => setDate((e.target as HTMLInputElement).value)} /></label>
          </>
        )}

        {dated && (
          <>
            <div class="two">
              <label>{type === "TASK" ? "Hạn (tuỳ chọn)" : "Ngày"}<input type="date" value={date} onInput={(e) => setDate((e.target as HTMLInputElement).value)} /></label>
              <label>Giờ<input type="time" value={time} disabled={allDay} onInput={(e) => setTime((e.target as HTMLInputElement).value)} /></label>
            </div>
            <label class="check-row"><input type="checkbox" checked={allDay} onChange={(e) => setAllDay((e.target as HTMLInputElement).checked)} /> Cả ngày</label>
            <label>Lặp lại
              <select value={repeat} disabled={!date} onChange={(e) => setRepeat((e.target as HTMLSelectElement).value as Recurrence | "")}>
                <option value="">Không lặp</option>
                {RECURRENCES.map((r) => <option key={r} value={r}>{RECURRENCE_LABEL[r]}</option>)}
              </select>
            </label>
            {type === "EVENT" && (
              <div class="two">
                <label>Địa điểm<input value={place} maxLength={MAX.place} onInput={(e) => setPlace((e.target as HTMLInputElement).value)} /></label>
                <label>Với ai<input value={person} maxLength={MAX.person} onInput={(e) => setPerson((e.target as HTMLInputElement).value)} /></label>
              </div>
            )}
          </>
        )}

        <label>Chi tiết<textarea rows={3} value={details} maxLength={MAX.details} onInput={(e) => setDetails((e.target as HTMLTextAreaElement).value)} /></label>

        {item.quote && <p class="quote">Câu gốc: “{item.quote}”</p>}
        {error && <p class="alert" role="alert">{error}</p>}

        <footer>
          {!isNew && !wasDraft && <button type="button" class="btn danger" onClick={() => onDelete(item)}><Icon name="trash" size={18} /> Xoá</button>}
          {dated && date && <button type="button" class="btn ghost" onClick={() => void addToCalendar()}><Icon name="calendar" size={18} /> Thêm vào Lịch</button>}
          <button type="submit" class="btn solid grow">Lưu</button>
        </footer>
      </form>
    </div>
  );
}
