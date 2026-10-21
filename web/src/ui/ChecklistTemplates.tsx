import { useState } from "preact/hooks";
import {
  DEFAULTS, MAX_NAME, MAX_TEMPLATES, addTemplate, checklistTemplates, removeTemplate, restoreDefaults, scheduleLabel, setChecklistTemplates,
  updateTemplate, validate, type ChecklistTemplate,
} from "../lib/checklistTemplates";
import { CHECKLIST_MAX_ENTRIES, RECURRENCES, RECURRENCE_LABEL, type Recurrence } from "../lib/model";
import { fold } from "../lib/views";
import { useStore } from "../lib/store";
import { confirmDialog, Sheet } from "./common";
import { Icon } from "./icons";
import { showToast } from "./toast";

/** Always asks first; a template cannot be brought back except by typing it again (or "Khôi phục mẫu có sẵn" for the built-in ones). */
export async function deleteTemplateAsk(t: ChecklistTemplate): Promise<boolean> {
  const ok = await confirmDialog({ title: "Xoá mẫu này?", body: `${t.name} · ${t.items.length} mục`, ok: "Xoá", danger: true });
  if (ok) setChecklistTemplates(removeTemplate(checklistTemplates.get(), t.id));
  return ok;
}

/** Cài đặt → Mẫu checklist: add, edit and delete the templates quick add offers. */
export function ChecklistTemplatesCard() {
  const list = useStore(checklistTemplates);
  const [editing, setEditing] = useState<ChecklistTemplate | "new" | null>(null);
  const missing = DEFAULTS.some((d) => !list.some((t) => fold(t.name) === fold(d.name)));
  return (
    <div class="card-block ck-templates">
      <p class="hint">Một chạm ở ô “Gõ nhanh” là tạo việc kèm checklist từ mẫu. Mẫu có giờ hoặc lặp thì tạo luôn, mẫu không có thì mở màn sửa để chọn ngày. Mẫu lưu trên máy này.</p>
      <ul class="list" aria-label="Mẫu checklist">
        {list.map((t) => (
          <li class="row-item" key={t.id}>
            <button type="button" class="row-main" aria-label={`Sửa mẫu ${t.name}`} onClick={() => setEditing(t)}>
              <span class="title">{t.name}</span>
              <span class="meta">{t.items.length} mục · {scheduleLabel(t)}{t.uses > 0 ? ` · đã dùng ${t.uses} lần` : ""}</span>
            </button>
            <button type="button" class="icon-btn" aria-label={`Xoá mẫu ${t.name}`} onClick={() => void deleteTemplateAsk(t)}><Icon name="trash" size={18} /></button>
          </li>
        ))}
        {list.length === 0 && <li class="empty">Chưa có mẫu nào.</li>}
      </ul>
      <button type="button" class="btn ghost wide" disabled={list.length >= MAX_TEMPLATES} onClick={() => setEditing("new")}>
        <Icon name="plus" size={18} /> Thêm mẫu ({list.length}/{MAX_TEMPLATES})
      </button>
      {missing && <button type="button" class="btn ghost wide" onClick={() => { setChecklistTemplates(restoreDefaults(list)); showToast("Đã khôi phục mẫu có sẵn"); }}>Khôi phục mẫu có sẵn</button>}
      {editing && <TemplateSheet template={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function TemplateSheet({ template, onClose }: { template: ChecklistTemplate | null; onClose: () => void }) {
  const [name, setName] = useState(template?.name ?? "");
  const [lines, setLines] = useState(template?.items.join("\n") ?? "");
  const [type, setType] = useState<ChecklistTemplate["type"]>(template?.type ?? "TASK");
  const [recurrence, setRecurrence] = useState<Recurrence | "">(template?.recurrence ?? "");
  const [time, setTime] = useState(template?.time ?? "");
  const [error, setError] = useState("");

  const save = (e: Event) => {
    e.preventDefault();
    const list = checklistTemplates.get();
    const r = validate(list, { name, lines, type, recurrence: recurrence || undefined, time: time || undefined }, template?.id);
    if (typeof r === "string") return setError(r);
    setChecklistTemplates(template ? updateTemplate(list, template.id, r) : addTemplate(list, r));
    showToast(template ? "Đã lưu mẫu" : `Đã thêm mẫu: ${r.name}`);
    onClose();
  };
  const remove = async () => {
    if (template && (await deleteTemplateAsk(template))) onClose();
  };

  return (
    <Sheet title={template ? "Sửa mẫu checklist" : "Mẫu checklist mới"} onClose={onClose} level={2}>
      <form class="form" onSubmit={save}>
        <label>Tên mẫu<input value={name} maxLength={MAX_NAME} autofocus placeholder="VD: Ra khỏi nhà" onInput={(e) => setName((e.target as HTMLInputElement).value)} /></label>
        <label>Các mục (mỗi dòng một mục, tối đa {CHECKLIST_MAX_ENTRIES})
          <textarea rows={6} value={lines} placeholder={"Ví\nChìa khoá\nSạc dự phòng"} onInput={(e) => setLines((e.target as HTMLTextAreaElement).value)} />
        </label>
        <div class="two">
          <label>Tạo ra
            <select value={type} onChange={(e) => setType((e.target as HTMLSelectElement).value as ChecklistTemplate["type"])}>
              <option value="TASK">Việc</option>
              <option value="EVENT">Lịch hẹn</option>
            </select>
          </label>
          <label>Giờ (tuỳ chọn)<input type="time" value={time} onInput={(e) => setTime((e.target as HTMLInputElement).value)} /></label>
        </div>
        <label>Lặp lại (tuỳ chọn)
          <select value={recurrence} onChange={(e) => setRecurrence((e.target as HTMLSelectElement).value as Recurrence | "")}>
            <option value="">Không lặp</option>
            {RECURRENCES.map((r) => <option key={r} value={r}>{RECURRENCE_LABEL[r]}</option>)}
          </select>
        </label>
        <p class="hint">Việc lặp tự bỏ tick mỗi lần sang lượt mới, nên không cần dọn tay.</p>
        {error && <p class="alert" role="alert">{error}</p>}
        <footer>
          {template && <button type="button" class="btn danger" onClick={() => void remove()}><Icon name="trash" size={18} /> Xoá</button>}
          <button type="submit" class="btn solid grow">Lưu</button>
        </footer>
      </form>
    </Sheet>
  );
}
