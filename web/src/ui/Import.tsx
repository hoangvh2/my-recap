import { useMemo, useState } from "preact/hooks";
import { formatDate } from "../../../shared/dates";
import { importRecords, newId, prefs as prefsStore } from "../data";
import { recordsFromPlan } from "../lib/importer";
import { formatVnd } from "../lib/money";
import { KIND_LABEL, type ImportField } from "../lib/model";
import { go, to } from "../lib/router";
import { useStore } from "../lib/store";
import { planImport, templateCsv } from "../lib/table";
import { Link, Section, TopBar } from "./common";
import { saveFile } from "./download";
import { useGraph, useUid } from "./hooks";
import { Icon } from "./icons";
import { showToast } from "./toast";

const FIELD_NAME: Record<ImportField, string> = {
  customer: "Khách hàng", contact: "Người liên hệ", phone: "Số điện thoại", email: "Email", product: "Sản phẩm", kind: "Loại",
  startDate: "Ngày bắt đầu", endDate: "Ngày hết hạn", termMonths: "Thời hạn", value: "Giá trị", contractNo: "Số hợp đồng", note: "Ghi chú",
};

const MAX_FILE = 2_000_000;

/** Bring existing customers and licences in from Excel: paste the cells or open a CSV, check, then confirm. */
export function ImportScreen() {
  const g = useGraph();
  const uid = useUid();
  const prefs = useStore(prefsStore);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  const plan = useMemo(
    () => (text.trim() ? planImport(text, prefs.importColumns, { customers: g.customers, licenses: g.licenses }) : null),
    [text, prefs.importColumns, g.customers, g.licenses],
  );
  const ok = plan?.rows.filter((r) => r.status === "ok") ?? [];
  const dup = plan?.rows.filter((r) => r.status === "duplicate") ?? [];
  const bad = plan?.rows.filter((r) => r.status === "error") ?? [];

  const onFile = async (e: Event) => {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    setNote("");
    if (/\.xlsx?$/i.test(file.name)) {
      setNote("App chưa đọc trực tiếp file Excel (.xlsx). Cách dễ nhất: mở file trong Excel, bấm Ctrl+A rồi Ctrl+C để chép tất cả, rồi dán vào ô bên dưới. Hoặc lưu thành “CSV UTF-8” rồi chọn lại.");
      return;
    }
    if (file.size > MAX_FILE) {
      setNote("File quá lớn (trên 2 MB). Hãy chia nhỏ file.");
      return;
    }
    setText(await file.text());
  };

  const run = async () => {
    if (!plan) return;
    setBusy(true);
    try {
      const rec = recordsFromPlan(plan, g.customers, Date.now(), newId);
      await importRecords(uid, rec);
      showToast(`Đã nhập ${rec.licenses.length} license${rec.customers.length ? ` và ${rec.customers.length} khách mới` : ""}`);
      go(to.renewals);
    } catch {
      showToast("Chưa nhập được, kiểm tra mạng rồi thử lại");
      setBusy(false);
    }
  };

  return (
    <>
      <TopBar title="Nhập từ Excel" backTo={to.settings} />
      <div class="card-block">
        <ol class="steps">
          <li>Trong Excel, chọn các ô cần nhập (gồm cả dòng tiêu đề), bấm <strong>Ctrl+C</strong> (iPhone: chép).</li>
          <li>Dán vào ô bên dưới. Hoặc chọn file <strong>CSV</strong>.</li>
          <li>Xem kết quả kiểm tra, rồi bấm <strong>Nhập</strong>.</li>
        </ol>
        <p class="hint">Cột bắt buộc: <strong>{prefs.importColumns.customer}</strong>, <strong>{prefs.importColumns.product}</strong> và <strong>{prefs.importColumns.endDate}</strong> (hoặc ngày bắt đầu + thời hạn). Đổi tên cột ở <Link to={to.settings}>Cài đặt</Link>.</p>
        <div class="row gap">
          <button class="btn ghost grow" onClick={() => void saveFile("mau-nhap-license.csv", "text/csv", templateCsv(prefs.importColumns))}><Icon name="download" size={18} /> File mẫu</button>
          <label class="btn ghost grow file-btn"><Icon name="upload" size={18} /> Chọn file CSV<input type="file" accept=".csv,.tsv,.txt,text/csv,text/plain,.xlsx,.xls" onChange={(e) => void onFile(e)} /></label>
        </div>
        {note && <p class="alert" role="alert">{note}</p>}
        <textarea class="paste" rows={6} placeholder="Dán dữ liệu từ Excel vào đây…" value={text} onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} />
      </div>

      {plan && plan.missing.length > 0 && (
        <p class="alert" role="alert">
          Không tìm thấy cột: {plan.missing.map((f) => `“${FIELD_NAME[f]}”`).join(", ")}.
          Kiểm tra dòng tiêu đề, hoặc sửa tên cột trong <Link to={to.settings}>Cài đặt</Link>.
          {plan.columns.unknown.length > 0 && <> Các cột app chưa hiểu: {plan.columns.unknown.join(", ")}.</>}
        </p>
      )}

      {plan && plan.missing.length === 0 && (
        <>
          <div class="tiles">
            <div class="tile ok"><strong>{ok.length}</strong><span>sẽ được nhập</span></div>
            <div class="tile"><strong>{dup.length}</strong><span>đã có, bỏ qua</span></div>
            <div class={`tile ${bad.length ? "hot" : ""}`}><strong>{bad.length}</strong><span>dòng lỗi</span></div>
          </div>
          <p class="summary">{plan.newCustomers.length} khách mới · {plan.matchedCustomers} khách đã có trong app</p>

          {bad.length > 0 && (
            <Section title="Dòng lỗi (sẽ không nhập)" count={bad.length} tone="warn">
              <ul class="list">{bad.slice(0, 30).map((r) => (
                <li class="row-item" key={r.line}><div class="row-main"><span class="title">Dòng {r.line}: {r.customer || "(trống)"} · {r.product || "(trống)"}</span><span class="meta bad">{r.problems.join("; ")}</span></div></li>
              ))}</ul>
            </Section>
          )}
          {ok.length > 0 && (
            <Section title="Xem trước" count={ok.length}>
              <ul class="list">{ok.slice(0, 50).map((r) => (
                <li class="row-item" key={r.line}><div class="row-main">
                  <span class="title">{r.customer} · {r.product}</span>
                  <span class="meta">{KIND_LABEL[r.kind]} · hết hạn {r.endDate ? formatDate(r.endDate) : ""}{r.value ? ` · ${formatVnd(r.value)}` : ""}</span>
                  {r.notes.length > 0 && <span class="meta">{r.notes.join("; ")}</span>}
                </div></li>
              ))}</ul>
              {ok.length > 50 && <p class="hint">…và {ok.length - 50} dòng nữa.</p>}
            </Section>
          )}
          <div class="save-bar">
            <button class="btn solid grow" disabled={busy || ok.length === 0} onClick={() => void run()}>
              {busy ? "Đang nhập…" : `Nhập ${ok.length} license`}
            </button>
          </div>
        </>
      )}
    </>
  );
}
