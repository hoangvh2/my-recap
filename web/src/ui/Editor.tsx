import { useEffect, useRef, useState } from "preact/hooks";
import { fromInputs, toDateInput, toTimeInput } from "../lib/format";
import { buildIcs } from "../lib/ics";
import { applyAutoTag, tagRules } from "../lib/autotag";
import { formatVnd, parseVnd } from "../lib/money";
import { useStore } from "../lib/store";
import { EXPENSE_CATEGORIES, MAX, RECURRENCES, RECURRENCE_LABEL, TYPES, TYPE_LABEL, type ChecklistEntry, type Item, type ItemTag, type ItemType, type Recurrence } from "../lib/model";
import { saveFile } from "./download";
import { ChecklistEditor } from "./Checklist";
import { Icon } from "./icons";
import { useAudiences } from "./hooks";
import { TagCornerButton, TagMenu } from "./TagUI";
import { CustomerPicker, LicensePicker } from "./pickers";
import { EditorShareRow } from "./Sharing";
import { isShareable } from "../../../shared/share";

export interface EditorProps {
  item: Item;
  isNew: boolean;
  /** `audience`: who should see it, when the person changed that in the editor (undefined: unchanged). */
  onSave: (item: Item, audience?: string[]) => void;
  onDelete: (item: Item) => void;
  /** Tick off an open task or appointment straight from the editor. */
  onDone?: (item: Item) => void;
  onClose: () => void;
}

/** Bottom sheet to create or change one item. */
export function Editor({ item, isNew, onSave, onDelete, onDone, onClose }: EditorProps) {
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
  const [customerId, setCustomerId] = useState<string | undefined>(item.customerId);
  const [licenseId, setLicenseId] = useState<string | undefined>(item.licenseId);
  const [important, setImportant] = useState(item.important === true);
  const [tag, setTag] = useState<ItemTag | undefined>(item.tag);
  const [checklist, setChecklist] = useState<ChecklistEntry[]>(item.checklist ?? []);
  // Once the person has chosen (including "Không thẻ"), the auto-tag rules stay out of it.
  const [tagTouched, setTagTouched] = useState(false);
  const [tagMenu, setTagMenu] = useState(false);
  const rules = useStore(tagRules);
  const sharedWith = useAudiences().get(item.id) ?? [];
  const [audience, setAudienceDraft] = useState<string[] | undefined>(undefined);
  const [error, setError] = useState("");
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);
  // A new appointment usually has a time; a new task usually just a day or nothing.
  useEffect(() => {
    if (isNew && !date) setAllDay(type === "TASK");
  }, [type]);

  const wasDraft = item.status === "DRAFT";
  const dated = type === "TASK" || type === "EVENT";
  const changeType = (t: ItemType) => {
    setType(t);
    // Notes and expenses cannot carry a tag.
    if (t !== "TASK" && t !== "EVENT") {
      setTag(undefined);
      setTagMenu(false);
    }
  };

  const build = (): Item | null => {
    const t = title.trim();
    if (!t) return fail("Cần nhập tiêu đề");
    if (t.length > MAX.title) return fail(`Tiêu đề dài quá ${MAX.title} ký tự`);
    const out: Item = { id: item.id, type, status: wasDraft ? "OPEN" : item.status, title: t, details: details.trim().slice(0, MAX.details), allDay: false, createdAt: item.createdAt };
    if (type !== "EXPENSE" && customerId) {
      out.customerId = customerId;
      if (licenseId) out.licenseId = licenseId;
    }
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
      if (important) out.important = true;
      if (tag) out.tag = tag;
      const lines = checklist.filter((e) => e.text.trim());
      if (lines.length > 0) out.checklist = lines.map((e) => ({ ...e, text: e.text.trim() }));
      if (place.trim()) out.place = place.trim().slice(0, MAX.place);
      if (person.trim()) out.person = person.trim().slice(0, MAX.person);
    }
    // A new (or just-confirmed) task or appointment without a tag gets one from the rules; an edit never does.
    return dated && !out.tag && (isNew || wasDraft) && !tagTouched ? applyAutoTag(rules, out) : out;
  };
  const fail = (m: string): null => {
    setError(m);
    return null;
  };

  const submit = (e: Event) => {
    e.preventDefault();
    const out = build();
    if (!out) return;
    const changed = audience !== undefined && [...audience].sort().join() !== [...sharedWith].sort().join();
    onSave(out, changed && isShareable(out.type) ? audience : undefined);
  };

  const addToCalendar = async () => {
    const out = build();
    if (out) await saveFile(`${out.title.slice(0, 40).replace(/[^\p{L}\p{N}]+/gu, "-")}.ics`, "text/calendar", buildIcs([out]));
  };

  return (
    <div class="sheet-backdrop" role="dialog" aria-modal="true" aria-label={isNew ? "Thêm mới" : "Sửa"} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <form class="sheet" onSubmit={submit}>
        {dated && (
          <>
            <TagCornerButton tag={tag} expanded={tagMenu} onClick={() => setTagMenu(!tagMenu)} />
            {tagMenu && <TagMenu current={tag} onClose={() => setTagMenu(false)} onPick={(t) => { setTag(t); setTagTouched(true); setTagMenu(false); }} />}
          </>
        )}
        <header class={dated ? "has-tag" : ""}>
          <h2>{isNew ? "Thêm mới" : wasDraft ? "Xem lại" : "Sửa"}</h2>
          <button type="button" class="icon-btn" aria-label="Đóng" onClick={onClose}><Icon name="close" /></button>
        </header>

        {(isNew || wasDraft) && (
          <div class="seg" role="radiogroup" aria-label="Loại">
            {TYPES.map((t) => (
              <button type="button" key={t} role="radio" aria-checked={type === t} class={type === t ? "on" : ""} onClick={() => changeType(t)}>{TYPE_LABEL[t]}</button>
            ))}
          </div>
        )}

        <label>Tiêu đề<input ref={first} value={title} maxLength={MAX.title} onInput={(e) => setTitle((e.target as HTMLInputElement).value)} /></label>

        {type !== "EXPENSE" && (
          <>
            <label>Khách liên quan (tuỳ chọn)
              <CustomerPicker optional value={customerId} label="Không gắn với khách nào" onChange={(id) => { setCustomerId(id); setLicenseId(undefined); }} />
            </label>
            <LicensePicker customerId={customerId} value={licenseId} onChange={setLicenseId} />
          </>
        )}

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
            <label class="check-row important"><input type="checkbox" checked={important} onChange={(e) => setImportant((e.target as HTMLInputElement).checked)} /> ❗ Quan trọng (nhắc khẩn đến khi bấm xác nhận)</label>
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

        {dated && <ChecklistEditor entries={checklist} onChange={setChecklist} title={title} onNeedTitle={setTitle} />}

        {!wasDraft && <EditorShareRow item={{ ...item, type }} value={audience ?? sharedWith} onChange={setAudienceDraft} />}

        <label>Chi tiết<textarea rows={3} value={details} maxLength={MAX.details} onInput={(e) => setDetails((e.target as HTMLTextAreaElement).value)} /></label>

        {item.quote && <p class="quote">Câu gốc: “{item.quote}”</p>}
        {error && <p class="alert" role="alert">{error}</p>}

        <footer>
          {!isNew && !wasDraft && <button type="button" class="btn danger" onClick={() => onDelete(item)}><Icon name="trash" size={18} /> Xoá</button>}
          {!isNew && !wasDraft && onDone && item.status === "OPEN" && (item.type === "TASK" || item.type === "EVENT") && (
            <button type="button" class="btn ghost" onClick={() => onDone(item)}><Icon name="check" size={18} /> Đã xong</button>
          )}
          {dated && date && <button type="button" class="btn ghost" onClick={() => void addToCalendar()}><Icon name="calendar" size={18} /> Thêm vào Lịch</button>}
          <button type="submit" class="btn solid grow">Lưu</button>
        </footer>
      </form>
    </div>
  );
}
