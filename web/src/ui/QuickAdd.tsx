import { useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { deleteItems, items as itemsStore, newId, saveItem } from "../data";
import { formatVnd } from "../lib/money";
import { TYPE_LABEL } from "../lib/model";
import { whenLabel } from "../lib/format";
import { PART_LABEL, parseQuick, toItem, type Part } from "../lib/quickadd";
import { useStore } from "../lib/store";
import {
  BLANK, addTemplate, editTemplate, featured, fill, hasBlank, matching, setTemplates, suggest, templates, usedTemplate, type QuickTemplate,
} from "../lib/templates";
import { RECURRENCE_LABEL } from "../lib/model";
import { Sheet } from "./common";
import { useUid } from "./hooks";
import { Icon } from "./icons";
import { showToast } from "./toast";

/** "Việc · Mai 15:00 · Quan trọng" / "Chi tiêu · 45.000 đ · Ăn uống": what pressing Thêm will save. */
function summary(r: ReturnType<typeof parseQuick>, now: number): string {
  const item = toItem(r, "preview", now);
  const parts: string[] = [TYPE_LABEL[r.type]];
  if (r.amount !== undefined) parts.push(formatVnd(r.amount));
  const when = whenLabel(item, now);
  if (when) parts.push(when);
  if (r.recurrence) parts.push(RECURRENCE_LABEL[r.recurrence]);
  if (r.category) parts.push(r.category);
  if (r.important) parts.push("Quan trọng");
  return parts.join(" · ");
}

/**
 * "Gõ nhanh": one line becomes a task, an appointment or an expense, understood on this device (no AI,
 * no cost): "gọi anh Nam mai 3h chiều", "họp nhóm thứ 5 9h gấp", "cà phê 45k". The words it understood show
 * as chips under the box; tap one to leave those words in the title instead.
 */
export function QuickAdd() {
  const uid = useUid();
  const all = useStore(itemsStore);
  const saved = useStore(templates);
  const [text, setText] = useState("");
  const [off, setOff] = useState<Part[]>([]);
  const [focused, setFocused] = useState(false);
  const [managing, setManaging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const cursor = useRef<number | null>(null);
  const used = useRef<QuickTemplate | null>(null);
  const now = Date.now();

  const history = useMemo(() => all.filter((i) => i.type === "EXPENSE"), [all]);
  const result = useMemo(() => parseQuick(text, Date.now(), new Set(off), history), [text, off, history]);
  const offered = text.trim() ? matching(saved, text) : focused ? featured(saved, now) : [];

  // Before paint, so a key pressed right after picking a template lands on the blank.
  useLayoutEffect(() => {
    if (cursor.current !== null && input.current) {
      input.current.focus();
      input.current.setSelectionRange(cursor.current, cursor.current);
      cursor.current = null;
    }
  }, [text]);

  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");

  const add = (line: string, template: QuickTemplate | null) => {
    const r = parseQuick(line, Date.now(), template ? new Set() : new Set(off), history);
    if (!r.title.trim()) return;
    const item = toItem(r, newId(), Date.now());
    saveItem(uid, item).then(
      () => showToast(`Đã thêm: ${item.title}`, { label: "Hoàn tác", run: () => deleteItems(uid, [item.id]).catch(fail) }),
      fail,
    );
    if (template) setTemplates(usedTemplate(templates.get(), template, Date.now()));
    setText("");
    setOff([]);
    used.current = null;
  };

  const pick = (t: QuickTemplate) => {
    if (!hasBlank(t.text)) return add(t.text, t);
    const f = fill(t.text);
    used.current = t;
    cursor.current = f.cursor;
    setOff([]);
    setText(f.text);
  };

  const submit = () => {
    const tpl = used.current;
    add(text, null);
    if (tpl) setTemplates(usedTemplate(templates.get(), tpl, Date.now()));
  };

  const toggle = (p: Part) => setOff((o) => (o.includes(p) ? o.filter((x) => x !== p) : [...o, p]));
  const understood = result.spans;
  const typed = text.trim().length > 0;

  return (
    <section class="quick" aria-label="Gõ nhanh">
      <form class="quick-row" onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <input
          ref={input}
          value={text}
          enterkeyhint="done"
          placeholder="Gõ nhanh: cà phê 45k, gọi anh Nam mai 3h chiều…"
          aria-label="Gõ nhanh một việc, lịch hẹn hoặc khoản chi"
          autoComplete="off"
          onInput={(e) => setText((e.target as HTMLInputElement).value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setTimeout(() => setFocused(false), 150)}
        />
        <button class="btn solid" type="submit" disabled={!typed || !result.title.trim()}>Thêm</button>
      </form>
      {typed && (
        <div class="quick-read" aria-live="polite">
          <div class="chips-inline">
            {understood.map((s) => (
              <button key={s.part} type="button" class="pill on" aria-label={`${PART_LABEL[s.part]}: ${s.shown}. Chạm để bỏ`} onClick={() => toggle(s.part)}>
                {s.shown} <Icon name="close" size={12} />
              </button>
            ))}
            {off.map((p) => (
              <button key={p} type="button" class="pill off" aria-label={`Bật lại ${PART_LABEL[p]}`} onClick={() => toggle(p)}>{PART_LABEL[p]}</button>
            ))}
          </div>
          <p class="muted">{summary(result, Date.now())}</p>
        </div>
      )}
      {(offered.length > 0 || focused || managing) && (
        <div class="chips-inline templates" role="group" aria-label="Mẫu câu">
          {offered.map((t) => (
            <button key={t.text} type="button" class="pill tpl" onMouseDown={(e) => e.preventDefault()} onClick={() => pick(t)}>
              {t.text.replace("...", BLANK)}
            </button>
          ))}
          <button type="button" class="pill ghost" onMouseDown={(e) => e.preventDefault()} onClick={() => setManaging(true)}>Mẫu câu…</button>
        </div>
      )}
      {managing && <TemplatesSheet onClose={() => setManaging(false)} />}
    </section>
  );
}

/** The saved lines: edit, delete, add, and lines suggested from what is added over and over. */
function TemplatesSheet({ onClose }: { onClose: () => void }) {
  const list = useStore(templates);
  const all = useStore(itemsStore);
  const [draft, setDraft] = useState("");
  const [editing, setEditing] = useState<{ index: number; text: string } | null>(null);
  const suggestions = useMemo(() => suggest(all, list, Date.now()), [all, list]);

  return (
    <Sheet title="Mẫu câu nhập nhanh" onClose={onClose}>
      <p class="muted">Chạm một mẫu để thêm ngay. Mẫu có dấu “{BLANK}” sẽ đặt con trỏ ở chỗ cần điền, ví dụ “ăn trưa {BLANK}k”.</p>
      <ul class="list">
        {list.map((t, index) => (
          <li class="row-item" key={t.text}>
            {editing?.index === index ? (
              <form class="row gap grow" onSubmit={(e) => { e.preventDefault(); setTemplates(editTemplate(list, index, editing.text)); setEditing(null); }}>
                <input value={editing.text} autofocus aria-label="Sửa mẫu" onInput={(e) => setEditing({ index, text: (e.target as HTMLInputElement).value })} />
                <button class="btn solid" type="submit">Lưu</button>
              </form>
            ) : (
              <>
                <button type="button" class="row-main" onClick={() => setEditing({ index, text: t.text })} aria-label={`Sửa mẫu ${t.text}`}>
                  <span class="title">{t.text}</span>
                  {t.uses > 0 && <span class="meta">Đã dùng {t.uses} lần</span>}
                </button>
                <button type="button" class="icon-btn" aria-label={`Xoá mẫu ${t.text}`} onClick={() => setTemplates(list.filter((_, i) => i !== index))}><Icon name="trash" size={18} /></button>
              </>
            )}
          </li>
        ))}
        {list.length === 0 && <li class="empty">Chưa có mẫu nào.</li>}
      </ul>
      <form class="row gap" onSubmit={(e) => { e.preventDefault(); setTemplates(addTemplate(list, draft)); setDraft(""); }}>
        <input value={draft} placeholder={`VD: đổ xăng ${BLANK}k`} aria-label="Mẫu mới" onInput={(e) => setDraft((e.target as HTMLInputElement).value)} />
        <button class="btn solid" type="submit" disabled={!draft.trim()}>Thêm mẫu</button>
      </form>
      {suggestions.length > 0 && (
        <>
          <h3 class="muted small-caps">Gợi ý từ những gì bạn hay thêm</h3>
          <div class="chips-inline">
            {suggestions.map((s) => <button key={s} type="button" class="pill tpl" onClick={() => setTemplates(addTemplate(list, s))}>+ {s}</button>)}
          </div>
        </>
      )}
    </Sheet>
  );
}
