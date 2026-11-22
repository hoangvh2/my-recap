import { useState } from "preact/hooks";
import { COMPANY_MAX, COMPANY_NAME_MAX, companyProblem, companySpan, type CompanyHoliday } from "../../../shared/company";
import { lunarPrefs, setLunarPrefs } from "../lib/lunarCfg";
import { useStore } from "../lib/store";
import { confirmDialog, Sheet } from "./common";
import { Icon } from "./icons";
import { showToast } from "./toast";

const newId = () => `c${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
const sorted = (l: CompanyHoliday[]) => [...l].sort((a, b) => (a.from.slice(5) < b.from.slice(5) ? -1 : a.from.slice(5) > b.from.slice(5) ? 1 : 0));

/** Always asks first; the entry is gone from the calendar and the banners the moment it is deleted. */
export async function deleteCompanyAsk(c: CompanyHoliday): Promise<boolean> {
  const ok = await confirmDialog({ title: "Xoá kỳ nghỉ này?", body: `${c.name} · ${companySpan(c)}${c.yearly ? " · hằng năm" : ` · ${c.from.slice(0, 4)}`}`, ok: "Xoá", danger: true });
  if (ok) setLunarPrefs({ company: lunarPrefs.get().company.filter((x) => x.id !== c.id) });
  return ok;
}

/** Cài đặt → Ngày nghỉ của công ty. */
export function CompanyHolidaysCard() {
  const { company } = useStore(lunarPrefs);
  const [editing, setEditing] = useState<CompanyHoliday | "new" | null>(null);
  return (
    <div class="card-block company-holidays">
      <p class="hint">Ngày nghỉ riêng của công ty (nghỉ hè, team building, nghỉ cuối năm…). Hiện trên Lịch, ở Hôm nay và nhắc trước như ngày lễ nhà nước. Lưu trên máy này.</p>
      <ul class="list" aria-label="Ngày nghỉ của công ty">
        {sorted(company).map((c) => (
          <li class="row-item" key={c.id}>
            <button type="button" class="row-main" aria-label={`Sửa kỳ nghỉ ${c.name}`} onClick={() => setEditing(c)}>
              <span class="title">{c.name}</span>
              <span class="meta">{companySpan(c)}{c.yearly ? " · lặp hằng năm" : ` · năm ${c.from.slice(0, 4)}`}</span>
            </button>
            <button type="button" class="icon-btn" aria-label={`Xoá kỳ nghỉ ${c.name}`} onClick={() => void deleteCompanyAsk(c)}><Icon name="trash" size={18} /></button>
          </li>
        ))}
        {company.length === 0 && <li class="empty">Chưa nhập ngày nghỉ nào.</li>}
      </ul>
      <button type="button" class="btn ghost wide" disabled={company.length >= COMPANY_MAX} onClick={() => setEditing("new")}>
        <Icon name="plus" size={18} /> Thêm ngày nghỉ công ty
      </button>
      {editing && <CompanySheet entry={editing === "new" ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function CompanySheet({ entry, onClose }: { entry: CompanyHoliday | null; onClose: () => void }) {
  const [name, setName] = useState(entry?.name ?? "");
  const [from, setFrom] = useState(entry?.from ?? "");
  const [to, setTo] = useState(entry?.to ?? "");
  const [yearly, setYearly] = useState(entry?.yearly ?? true);
  const [error, setError] = useState("");

  const save = (e: Event) => {
    e.preventDefault();
    const end = to || from;
    const problem = companyProblem({ name, from, to: end });
    if (problem) return setError(problem);
    const next: CompanyHoliday = { id: entry?.id ?? newId(), name: name.trim(), from, to: end, yearly };
    const list = lunarPrefs.get().company;
    setLunarPrefs({ company: entry ? list.map((x) => (x.id === entry.id ? next : x)) : [...list, next] });
    showToast(entry ? "Đã lưu kỳ nghỉ" : `Đã thêm: ${next.name}`);
    onClose();
  };
  const remove = async () => {
    if (entry && (await deleteCompanyAsk(entry))) onClose();
  };

  return (
    <Sheet title={entry ? "Sửa kỳ nghỉ công ty" : "Kỳ nghỉ công ty mới"} onClose={onClose} level={2}>
      <form class="form" onSubmit={save}>
        <label>Tên kỳ nghỉ<input value={name} maxLength={COMPANY_NAME_MAX} autofocus placeholder="VD: Nghỉ hè công ty" onInput={(e) => setName((e.target as HTMLInputElement).value)} /></label>
        <div class="two">
          <label>Từ ngày<input type="date" value={from} onInput={(e) => setFrom((e.target as HTMLInputElement).value)} /></label>
          <label>Đến ngày (bỏ trống nếu một ngày)<input type="date" value={to} min={from || undefined} onInput={(e) => setTo((e.target as HTMLInputElement).value)} /></label>
        </div>
        <label class="check-row"><input type="checkbox" checked={yearly} onChange={(e) => setYearly((e.target as HTMLInputElement).checked)} /> <span>Lặp lại hằng năm<small class="hint block">Cùng ngày, cùng tháng mỗi năm. Bỏ chọn nếu chỉ nghỉ trong năm này.</small></span></label>
        {error && <p class="alert" role="alert">{error}</p>}
        <footer>
          {entry && <button type="button" class="btn danger" onClick={() => void remove()}><Icon name="trash" size={18} /> Xoá</button>}
          <button type="submit" class="btn solid grow">Lưu</button>
        </footer>
      </form>
    </Sheet>
  );
}
