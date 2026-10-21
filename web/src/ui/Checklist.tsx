import { useState } from "preact/hooks";
import { appendLines, parseLines } from "../lib/checklist";
import { addTemplate, checklistTemplates, setChecklistTemplates, validate, byUse } from "../lib/checklistTemplates";
import { CHECKLIST_MAX_ENTRIES, CHECKLIST_MAX_TEXT, type ChecklistEntry, type Item } from "../lib/model";
import { useStore } from "../lib/store";
import { confirmDialog } from "./common";
import { Icon } from "./icons";
import { showToast } from "./toast";

/** The open card under a row: what is still missing first, what is already in the bag below, one button for "all there". */
export function ChecklistPanel(props: { item: Item; remaining: ChecklistEntry[]; ticked: ChecklistEntry[]; onToggle: (id: string) => void; onAll: () => void; onReset: () => void }) {
  const line = (e: ChecklistEntry) => (
    <label class={`ck-it ${e.done ? "on" : ""}`} key={e.id}>
      <input type="checkbox" checked={e.done === true} onChange={() => props.onToggle(e.id)} />
      <span>{e.text}</span>
    </label>
  );
  return (
    <div class="ck-panel ck-ui" role="group" aria-label={`Checklist: ${props.item.title}`}>
      {props.remaining.map(line)}
      {props.ticked.length > 0 && <p class="ck-sep">Đã có ({props.ticked.length})</p>}
      {props.ticked.map(line)}
      <div class="ck-foot">
        {props.remaining.length > 0 && <button type="button" class="btn solid grow" onClick={props.onAll}><Icon name="check" size={18} /> Đủ rồi</button>}
        {props.ticked.length > 0 && <button type="button" class="btn ghost" onClick={props.onReset}>Đặt lại</button>}
      </div>
    </div>
  );
}

/** The checklist section of the editor: type a line and press Enter, paste a list, or attach a template. */
export function ChecklistEditor(props: { entries: ChecklistEntry[]; onChange: (e: ChecklistEntry[]) => void; title: string; onNeedTitle: (name: string) => void }) {
  const [draft, setDraft] = useState("");
  const templates = useStore(checklistTemplates);
  const { entries, onChange } = props;
  const full = entries.length >= CHECKLIST_MAX_ENTRIES;

  const add = (text: string, splitCommas = true) => {
    const lines = parseLines(text, splitCommas);
    if (lines.length === 0) return;
    onChange(appendLines(entries, lines));
    setDraft("");
  };
  const remove = async (e: ChecklistEntry) => {
    const ok = await confirmDialog({ title: "Xoá mục này?", body: e.text, ok: "Xoá", danger: true });
    if (ok) onChange(entries.filter((x) => x.id !== e.id));
  };
  const clear = async () => {
    const ok = await confirmDialog({ title: "Xoá checklist này?", body: `${entries.length} mục. Việc vẫn còn, chỉ mất danh sách.`, ok: "Xoá", danger: true });
    if (ok) onChange([]);
  };
  const attach = (id: string) => {
    const t = templates.find((x) => x.id === id);
    if (!t) return;
    onChange(appendLines(entries, t.items));
    if (!props.title.trim()) props.onNeedTitle(t.name);
  };
  const saveAsTemplate = () => {
    const name = props.title.trim();
    if (!name) return showToast("Nhập tiêu đề trước, tiêu đề sẽ là tên mẫu");
    const r = validate(templates, { name, type: "TASK", lines: entries.map((e) => e.text).join("\n") });
    if (typeof r === "string") return showToast(r);
    setChecklistTemplates(addTemplate(templates, r));
    showToast(`Đã lưu mẫu: ${name}`);
  };

  return (
    <fieldset class="ck-editor">
      <legend>Checklist{entries.length > 0 ? ` · ${entries.length} mục` : ""}</legend>
      {entries.map((e, i) => (
        <div class="ck-edit-row" key={e.id}>
          <input
            value={e.text}
            maxLength={CHECKLIST_MAX_TEXT}
            aria-label={`Mục ${i + 1}`}
            onInput={(ev) => onChange(entries.map((x) => (x.id === e.id ? { ...x, text: (ev.target as HTMLInputElement).value } : x)))}
          />
          <button type="button" class="icon-btn" aria-label={`Xoá mục ${e.text}`} onClick={() => void remove(e)}><Icon name="trash" size={18} /></button>
        </div>
      ))}
      <div class="ck-edit-row">
        <input
          value={draft}
          disabled={full}
          maxLength={CHECKLIST_MAX_TEXT * 8}
          placeholder={full ? "Đã đủ số mục tối đa" : "Thêm mục, Enter để thêm tiếp…"}
          aria-label="Thêm mục checklist"
          enterkeyhint="next"
          onInput={(ev) => setDraft((ev.target as HTMLInputElement).value)}
          onKeyDown={(ev) => {
            if (ev.key === "Enter") {
              ev.preventDefault();
              add(draft);
            }
          }}
          onPaste={(ev) => {
            const text = ev.clipboardData?.getData("text") ?? "";
            if (text.includes("\n")) {
              ev.preventDefault();
              add(text);
            }
          }}
        />
        <button type="button" class="icon-btn" aria-label="Thêm mục" disabled={!draft.trim() || full} onClick={() => add(draft)}><Icon name="plus" size={18} /></button>
      </div>
      <div class="chips-inline">
        {templates.length > 0 && (
          <select aria-label="Gắn mẫu checklist" value="" onChange={(ev) => { attach((ev.target as HTMLSelectElement).value); (ev.target as HTMLSelectElement).value = ""; }}>
            <option value="">Gắn mẫu…</option>
            {byUse(templates).map((t) => <option key={t.id} value={t.id}>{t.name} · {t.items.length}</option>)}
          </select>
        )}
        {entries.length > 0 && <button type="button" class="pill ghost" onClick={saveAsTemplate}>Lưu thành mẫu</button>}
        {entries.length > 0 && <button type="button" class="pill ghost danger-text" onClick={() => void clear()}>Xoá checklist</button>}
      </div>
    </fieldset>
  );
}

