import { useEffect, useState } from "preact/hooks";
import { addDays, formatDate } from "../../../shared/dates";
import { calendarToken, feedUrl } from "../api";
import { customers, licenses, items as itemsStore, prefs as prefsStore, savePrefs } from "../data";
import { buildEverythingCalendar } from "../lib/ics";
import {
  DEFAULT_IMPORT_COLUMNS, DEFAULT_PREFS, IMPORT_FIELDS, MILESTONE_LABELS, normalizePrefs, validMilestones, type ImportField, type Prefs,
} from "../lib/model";
import { MAX_RULES, MAX_RULE_TEXT, RULE_FIELDS, RULE_FIELD_LABEL, setTagRules, tagRules, type RuleField, type TagRule } from "../lib/autotag";
import { ITEM_TAGS } from "../lib/model";
import { TAG_INFO } from "../lib/tags";
import { templateCsv } from "../lib/table";
import { to } from "../lib/router";
import { useStore } from "../lib/store";
import { setTheme, THEME_LABEL, THEME_MODES, themeMode } from "../lib/theme";
import { signOut } from "../session";
import { confirmDialog, Link, Section, Signature, TopBar } from "./common";
import { saveFile } from "./download";
import { useCtx, useGraph, useUid } from "./hooks";
import { Icon } from "./icons";
import { NotifySettingsCard } from "./Notify";
import { MembersCard, SharePushCard } from "./Sharing";
import { useContacts } from "./hooks";
import { showToast } from "./toast";

const FIELD_LABEL: Record<ImportField, string> = {
  customer: "Tên khách", contact: "Người liên hệ", phone: "Số điện thoại", email: "Email", product: "Sản phẩm", kind: "Loại",
  startDate: "Ngày bắt đầu", endDate: "Ngày hết hạn", termMonths: "Thời hạn (tháng)", value: "Giá trị", contractNo: "Số hợp đồng", note: "Ghi chú",
};

function MilestoneEditor(props: { title: string; hint: string; value: number[]; onChange: (v: number[]) => void }) {
  const err = !validMilestones(props.value);
  return (
    <div class="ms">
      <h4>{props.title}</h4>
      <p class="hint">{props.hint}</p>
      {MILESTONE_LABELS.map((label, i) => (
        <label class="ms-row" key={label}>
          <span>{label}</span>
          <span class="num">
            <input type="number" inputMode="numeric" min={1} max={365} value={props.value[i]}
              onInput={(e) => props.onChange(props.value.map((v, j) => (j === i ? Number((e.target as HTMLInputElement).value) : v)))} />
            <em>ngày trước hạn</em>
          </span>
        </label>
      ))}
      {err && <p class="alert" role="alert">Các mốc phải là số nguyên, giảm dần (VD 90, 60, 30, 14).</p>}
    </div>
  );
}

function CalendarLink() {
  const [token, setToken] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    calendarToken("get").then(setToken).catch((e: Error) => { setToken(null); setError(e.message); });
  }, []);
  const act = async (action: "rotate" | "revoke") => {
    if (action === "rotate" && token) {
      const ok = await confirmDialog({ title: "Đổi liên kết lịch?", body: "Liên kết cũ sẽ ngừng hoạt động. Bạn phải thêm lại lịch trên điện thoại bằng liên kết mới.", ok: "Đổi liên kết" });
      if (!ok) return;
    }
    if (action === "revoke") {
      const ok = await confirmDialog({ title: "Tắt lịch tự cập nhật?", body: "Lịch đã thêm vào điện thoại sẽ không cập nhật nữa.", ok: "Tắt", danger: true });
      if (!ok) return;
    }
    setBusy(true);
    setError("");
    try {
      setToken(await calendarToken(action));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Chưa làm được, thử lại");
    } finally {
      setBusy(false);
    }
  };
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(feedUrl(token!));
      showToast("Đã sao chép liên kết");
    } catch {
      showToast("Không sao chép được, hãy nhấn giữ vào liên kết để sao chép");
    }
  };
  return (
    <div class="cal-link">
      {token === undefined && <p class="muted">Đang kiểm tra…</p>}
      {token === null && (
        <>
          <p class="muted">Thêm một lịch vào iPhone, tự cập nhật các mốc nhắc gia hạn và lịch hẹn. Không cần mở app để xuất file.</p>
          <button class="btn solid wide" disabled={busy} onClick={() => void act("rotate")}><Icon name="calendar" size={18} /> Bật lịch tự cập nhật</button>
        </>
      )}
      {token && (
        <>
          <a class="btn solid wide" href={feedUrl(token, "webcal")}><Icon name="calendar" size={18} /> Thêm vào Lịch iPhone</a>
          <p class="hint">Bấm nút trên, iPhone sẽ hỏi “Đăng ký”. Sau đó lịch tự cập nhật (iPhone thường làm mỗi vài giờ). Lịch không chứa số điện thoại hay email.</p>
          <div class="link-box" aria-label="Liên kết lịch">{feedUrl(token)}</div>
          <div class="row gap">
            <button class="btn ghost grow" onClick={() => void copy()}><Icon name="copy" size={18} /> Sao chép</button>
            <button class="btn ghost grow" disabled={busy} onClick={() => void act("rotate")}>Đổi liên kết</button>
            <button class="btn danger" disabled={busy} onClick={() => void act("revoke")}>Tắt</button>
          </div>
        </>
      )}
      {error && <p class="alert" role="alert">{error}</p>}
    </div>
  );
}

/** "Thẻ tự động": up to 20 rules that tag a new task or appointment. Saved on this device as you type (like the quick-add lines). */
function TagRulesCard() {
  const saved = useStore(tagRules);
  const [rules, setRules] = useState<TagRule[]>(saved);
  const change = (next: TagRule[]) => {
    setRules(next);
    setTagRules(next);
  };
  const edit = (i: number, patch: Partial<TagRule>) => change(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <div class="card-block tag-rules">
      <p class="hint">Việc hoặc lịch hẹn <strong>mới</strong> không có thẻ sẽ được gắn thẻ theo quy tắc đầu tiên khớp (không phân biệt hoa thường và dấu). Quy tắc không tự áp dụng lại khi bạn sửa, nên thẻ đã bỏ sẽ không tự quay lại.</p>
      {rules.length === 0 && <p class="muted">Chưa có quy tắc nào.</p>}
      {rules.map((r, i) => (
        <div class="rule-row" key={i}>
          <select aria-label={`Quy tắc ${i + 1}: áp dụng cho`} value={r.field} onChange={(e) => edit(i, { field: (e.target as HTMLSelectElement).value as RuleField })}>
            {RULE_FIELDS.map((f) => <option key={f} value={f}>{RULE_FIELD_LABEL[f]}</option>)}
          </select>
          <input aria-label={`Quy tắc ${i + 1}: chứa chữ`} value={r.text} maxLength={MAX_RULE_TEXT} placeholder="chứa chữ…" onInput={(e) => edit(i, { text: (e.target as HTMLInputElement).value })} />
          <select aria-label={`Quy tắc ${i + 1}: gắn thẻ`} value={r.tag} onChange={(e) => edit(i, { tag: (e.target as HTMLSelectElement).value as TagRule["tag"] })}>
            {ITEM_TAGS.map((t) => <option key={t} value={t}>{TAG_INFO[t].glyph} {TAG_INFO[t].label}</option>)}
          </select>
          <button type="button" class="icon-btn" aria-label={`Xoá quy tắc ${i + 1}`} onClick={() => change(rules.filter((_, j) => j !== i))}><Icon name="trash" size={18} /></button>
        </div>
      ))}
      <button type="button" class="btn ghost wide" disabled={rules.length >= MAX_RULES} onClick={() => change([...rules, { field: "keyword", text: "", tag: "red" }])}>
        <Icon name="plus" size={18} /> Thêm quy tắc ({rules.length}/{MAX_RULES})
      </button>
    </div>
  );
}

export function Settings({ email }: { email: string }) {
  const uid = useUid();
  const saved = useStore(prefsStore);
  const theme = useStore(themeMode);
  const g = useGraph();
  const ctx = useCtx();
  const people = useContacts();
  const [draft, setDraft] = useState<Prefs>(saved);
  const [dirty, setDirty] = useState(false);
  useEffect(() => { if (!dirty) setDraft(saved); }, [saved, dirty]);
  const edit = (p: Partial<Prefs>) => { setDraft({ ...draft, ...p }); setDirty(true); };
  const valid = validMilestones(draft.milestones) && validMilestones(draft.shortMilestones);
  const cols = draft.importColumns;

  const save = async () => {
    try {
      await savePrefs(uid, normalizePrefs(draft));
      setDirty(false);
      showToast("Đã lưu cài đặt");
    } catch {
      showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
    }
  };
  const reset = () => { setDraft({ ...DEFAULT_PREFS, importColumns: { ...DEFAULT_IMPORT_COLUMNS } }); setDirty(true); };

  const sample = "2027-12-31";
  const ex = valid ? draft.milestones.map((d) => formatDate(addDays(sample, -d))) : [];

  const exportCalendar = async () => {
    const text = buildEverythingCalendar(g, ctx);
    if (!text) return showToast("Chưa có việc hay mốc gia hạn nào phía trước");
    await saveFile("vhub-desk-lich.ics", "text/calendar", text);
  };
  const backup = async () => {
    const day = new Date().toISOString().slice(0, 10);
    await saveFile(`vhub-desk-sao-luu-${day}.json`, "application/json", JSON.stringify({
      app: "my-recap-web", exportedAt: Date.now(), customers: customers.get(), licenses: licenses.get(), items: itemsStore.get().filter((i) => i.status !== "DRAFT"),
    }, null, 2));
  };

  return (
    <>
      <TopBar title="Cài đặt" backTo={to.today} />

      <Section title="Giao diện">
        <div class="card-block">
          <div class="seg seg3" role="radiogroup" aria-label="Chế độ màu">
            {THEME_MODES.map((m) => (
              <button type="button" key={m} role="radio" aria-checked={theme === m} class={theme === m ? "on" : ""} onClick={() => setTheme(m)}>{THEME_LABEL[m]}</button>
            ))}
          </div>
          <p class="hint">“Tự động” đổi theo cài đặt Sáng/Tối của điện thoại. Lựa chọn được nhớ trên máy này.</p>
        </div>
      </Section>

      <Section title="Thẻ tự động">
        <TagRulesCard />
      </Section>

      <Section title="Chia sẻ">
        <div class="card-block">
          <Link to={to.contacts} class="btn ghost wide"><Icon name="users" size={18} /> Liên hệ chia sẻ{people.length > 0 ? ` (${people.length})` : ""}</Link>
          <SharePushCard />
        </div>
      </Section>

      <Section title="Thông báo trên điện thoại">
        <div class="card-block"><NotifySettingsCard /></div>
      </Section>

      <Section title="Nhắc gia hạn">
        <div class="card-block">
          <MilestoneEditor title="License thông thường" hint="Nhắc bạn bao nhiêu ngày trước khi hết hạn." value={draft.milestones} onChange={(v) => edit({ milestones: v })} />
          {valid && <p class="hint">Ví dụ license hết hạn {formatDate(sample)}: hỏi khách {ex[0]}, báo giá {ex[1]}, chốt hợp đồng {ex[2]}, báo đỏ {ex[3]}.</p>}
          <MilestoneEditor title={`License ngắn (từ ${draft.shortTermMonths} tháng trở xuống)`} hint="Hạn ngắn thì nhắc sát hơn." value={draft.shortMilestones} onChange={(v) => edit({ shortMilestones: v })} />
          <label class="ms-row"><span>Coi là ngắn nếu từ</span><span class="num"><input type="number" inputMode="numeric" min={1} max={60} value={draft.shortTermMonths} onInput={(e) => edit({ shortTermMonths: Number((e.target as HTMLInputElement).value) })} /><em>tháng trở xuống</em></span></label>
          <label class="ms-row"><span>Hỏi lại nếu khách chưa trả lời sau</span><span class="num"><input type="number" inputMode="numeric" min={1} max={60} value={draft.staleDays} onInput={(e) => edit({ staleDays: Number((e.target as HTMLInputElement).value) })} /><em>ngày</em></span></label>
          <label class="ms-row"><span>Báo “khách lâu không liên lạc” sau</span><span class="num"><input type="number" inputMode="numeric" min={1} max={365} value={draft.quietDays} onInput={(e) => edit({ quietDays: Number((e.target as HTMLInputElement).value) })} /><em>ngày</em></span></label>
        </div>
      </Section>

      <Section title="Tên cột khi nhập từ Excel">
        <details class="card-block">
          <summary>Sửa tên cột (nếu file Excel của bạn đặt tên khác)</summary>
          <p class="hint">Nếu một cột có nhiều tên, ngăn cách bằng dấu phẩy. App cũng tự nhận các tên thông dụng như “Tên KH”, “Sản phẩm”, “Ngày hết hạn”.</p>
          {IMPORT_FIELDS.map((f) => (
            <label class="ms-row" key={f}>
              <span>{FIELD_LABEL[f]}</span>
              <input value={cols[f]} maxLength={200} onInput={(e) => edit({ importColumns: { ...cols, [f]: (e.target as HTMLInputElement).value } })} />
            </label>
          ))}
        </details>
      </Section>

      <div class="save-bar">
        <button class="btn ghost" onClick={reset}>Khôi phục mặc định</button>
        <button class="btn solid grow" disabled={!dirty || !valid} onClick={() => void save()}>Lưu cài đặt</button>
      </div>

      <Section title="Lịch trên điện thoại">
        <div class="card-block">
          <CalendarLink />
          <button class="btn ghost wide" onClick={() => void exportCalendar()}><Icon name="download" size={18} /> Xuất một lần (file .ics)</button>
        </div>
      </Section>

      <Section title="Nhập và sao lưu">
        <div class="card-block">
          <Link to={to.import} class="btn ghost wide"><Icon name="upload" size={18} /> Nhập khách và license từ Excel</Link>
          <button class="btn ghost wide" onClick={() => void saveFile("mau-nhap-license.csv", "text/csv", templateCsv(draft.importColumns))}><Icon name="download" size={18} /> Tải file Excel mẫu (CSV)</button>
          <button class="btn ghost wide" onClick={() => void backup()}><Icon name="download" size={18} /> Sao lưu dữ liệu (JSON)</button>
        </div>
      </Section>

      <MembersCard />

      <Section title="Tài khoản">
        <div class="card-block">
          <p class="muted">{email}</p>
          <p class="note"><Icon name="lock" size={14} /> Dữ liệu lưu trên Google, không lưu trong trình duyệt. Khoá Gemini nằm ở máy chủ.</p>
          <button class="btn danger wide" onClick={() => void signOut()}><Icon name="logout" size={18} /> Đăng xuất</button>
        </div>
      </Section>
      <Signature />
    </>
  );
}
