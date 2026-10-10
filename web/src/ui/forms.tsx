import { useEffect, useRef, useState } from "preact/hooks";
import { addDays, addMonths, isIsoDate } from "../../../shared/dates";
import { LICENSE_KINDS, KIND_LABEL, LOST_REASONS, type Customer, type License, type LicenseKind } from "../lib/model";
import { formatVnd, parseVnd } from "../lib/money";
import type { RenewalInput } from "../lib/flow";
import { Field, Sheet } from "./common";
import { CustomerPicker } from "./pickers";
import { Icon } from "./icons";

const input = (set: (v: string) => void) => (e: Event) => set((e.target as HTMLInputElement).value);

// ---------------------------------------------------------------- customer

export function CustomerForm(props: { customer: Customer; isNew: boolean; onSave: (c: Customer) => void; onDelete?: (c: Customer) => void; onClose: () => void }) {
  const c = props.customer;
  const [name, setName] = useState(c.name);
  const [contact, setContact] = useState(c.contact ?? "");
  const [phone, setPhone] = useState(c.phone ?? "");
  const [email, setEmail] = useState(c.email ?? "");
  const [note, setNote] = useState(c.note ?? "");
  const [error, setError] = useState("");
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);

  const submit = (e: Event) => {
    e.preventDefault();
    if (!name.trim()) return setError("Cần nhập tên khách (công ty hoặc người mua)");
    const out: Customer = { ...c, name: name.trim().slice(0, MAX_CUSTOMER.name) };
    for (const [k, v, max] of [["contact", contact, 120], ["phone", phone, 40], ["email", email, 120], ["note", note, 2000]] as const) {
      if (v.trim()) out[k] = v.trim().slice(0, max);
      else delete out[k];
    }
    props.onSave(out);
  };

  return (
    <Sheet title={props.isNew ? "Thêm khách" : "Sửa thông tin khách"} onClose={props.onClose}>
      <form class="form" onSubmit={submit}>
        <Field label="Tên khách (công ty hoặc người mua)"><input ref={first} value={name} maxLength={MAX_CUSTOMER.name} placeholder="VD: Công ty ABC" onInput={input(setName)} /></Field>
        <Field label="Người liên hệ"><input value={contact} maxLength={120} placeholder="VD: anh Nam, phòng kế toán" onInput={input(setContact)} /></Field>
        <div class="two">
          <Field label="Số điện thoại"><input type="tel" inputMode="tel" value={phone} maxLength={40} onInput={input(setPhone)} /></Field>
          <Field label="Email"><input type="email" inputMode="email" value={email} maxLength={120} onInput={input(setEmail)} /></Field>
        </div>
        <Field label="Ghi chú về khách"><textarea rows={3} value={note} maxLength={2000} onInput={input(setNote)} /></Field>
        {error && <p class="alert" role="alert">{error}</p>}
        <footer>
          {!props.isNew && props.onDelete && <button type="button" class="btn danger" onClick={() => props.onDelete!(c)}><Icon name="trash" size={18} /> Xoá</button>}
          <button type="submit" class="btn solid grow">Lưu</button>
        </footer>
      </form>
    </Sheet>
  );
}
const MAX_CUSTOMER = { name: 120 } as const;

// ---------------------------------------------------------------- licence

const TERMS = [{ label: "6 tháng", months: 6 }, { label: "1 năm", months: 12 }, { label: "2 năm", months: 24 }, { label: "3 năm", months: 36 }];

/** Dates the form fills in by itself from a start date and a term, until the person types an end date. */
export const endFromTerm = (start: string, months: number): string => (isIsoDate(start) && months >= 1 ? addDays(addMonths(start, months), -1) : "");

export function LicenseForm(props: {
  license: License;
  isNew: boolean;
  lockCustomer?: boolean;
  onSave: (l: License) => void;
  onDelete?: (l: License) => void;
  onClose: () => void;
}) {
  const l = props.license;
  const [customerId, setCustomerId] = useState<string | undefined>(l.customerId || undefined);
  const [product, setProduct] = useState(l.product);
  const [kind, setKind] = useState<LicenseKind>(l.kind);
  const [start, setStart] = useState(l.startDate ?? "");
  const [term, setTerm] = useState(l.termMonths ? String(l.termMonths) : "");
  const [end, setEnd] = useState(l.endDate);
  const [endTouched, setEndTouched] = useState(Boolean(l.endDate));
  const [value, setValue] = useState(l.value ? String(l.value) : "");
  const [contractNo, setContractNo] = useState(l.contractNo ?? "");
  const [notice, setNotice] = useState(l.noticeDays ? String(l.noticeDays) : "");
  const [note, setNote] = useState(l.note ?? "");
  const [more, setMore] = useState(Boolean(l.noticeDays || l.note));
  const [error, setError] = useState("");
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);

  const months = Number(term);
  const setStartAndRecalc = (v: string) => {
    setStart(v);
    if (!endTouched) setEnd(endFromTerm(v, months));
  };
  const setTermAndRecalc = (v: string) => {
    setTerm(v);
    if (!endTouched) setEnd(endFromTerm(start, Number(v)));
  };

  const submit = (e: Event) => {
    e.preventDefault();
    if (!customerId) return setError("Chọn khách sở hữu license này");
    if (!product.trim()) return setError("Cần nhập tên sản phẩm (VD: Phần mềm kế toán)");
    if (!isIsoDate(end)) return setError("Cần chọn ngày hết hạn (hoặc nhập ngày bắt đầu và thời hạn)");
    if (start && !isIsoDate(start)) return setError("Ngày bắt đầu chưa đúng");
    if (start && start > end) return setError("Ngày bắt đầu phải trước ngày hết hạn");
    const money = value.trim() ? parseVnd(value) : null;
    if (value.trim() && money === null) return setError("Giá trị chưa đúng, VD: 50000000 hoặc 50 triệu");
    const out: License = { ...l, customerId, product: product.trim().slice(0, 120), kind, endDate: end };
    const set = <K extends keyof License>(k: K, v: License[K] | undefined) => (v === undefined ? delete out[k] : (out[k] = v));
    set("startDate", start || undefined);
    set("termMonths", Number.isInteger(months) && months >= 1 && months <= 120 ? months : undefined);
    set("value", money ?? undefined);
    set("contractNo", contractNo.trim().slice(0, 60) || undefined);
    set("noticeDays", Number(notice) >= 1 && Number(notice) <= 365 ? Math.trunc(Number(notice)) : undefined);
    set("note", note.trim().slice(0, 2000) || undefined);
    props.onSave(out);
  };

  return (
    <Sheet title={props.isNew ? "Thêm license / bảo hành" : "Sửa license / bảo hành"} onClose={props.onClose}>
      <form class="form" onSubmit={submit}>
        <Field label="Khách">
          {props.lockCustomer ? <CustomerPicker value={customerId} onChange={() => undefined} /> : <CustomerPicker value={customerId} onChange={setCustomerId} />}
        </Field>
        <Field label="Sản phẩm"><input ref={first} value={product} maxLength={120} placeholder="VD: Phần mềm kế toán" onInput={input(setProduct)} /></Field>
        <div class="seg seg4" role="radiogroup" aria-label="Loại">
          {LICENSE_KINDS.map((k) => (
            <button type="button" key={k} role="radio" aria-checked={kind === k} class={kind === k ? "on" : ""} onClick={() => setKind(k)}>{KIND_LABEL[k]}</button>
          ))}
        </div>
        <div class="two">
          <Field label="Ngày bắt đầu"><input type="date" value={start} onInput={(e) => setStartAndRecalc((e.target as HTMLInputElement).value)} /></Field>
          <Field label="Ngày hết hạn"><input type="date" value={end} onInput={(e) => { setEnd((e.target as HTMLInputElement).value); setEndTouched(true); }} /></Field>
        </div>
        <div class="terms" role="group" aria-label="Thời hạn">
          <span class="muted">Thời hạn:</span>
          {TERMS.map((t) => (
            <button type="button" key={t.months} class={months === t.months ? "on" : ""} onClick={() => setTermAndRecalc(String(t.months))}>{t.label}</button>
          ))}
        </div>
        {!endTouched && end && <p class="hint">Ngày hết hạn tự tính: {end.split("-").reverse().join("/")}. Bạn vẫn có thể sửa.</p>}
        <div class="two">
          <Field label="Giá trị (đ)"><input inputMode="numeric" value={value} placeholder="VD: 50 triệu" onInput={input(setValue)} /></Field>
          <Field label="Số hợp đồng"><input value={contractNo} maxLength={60} onInput={input(setContractNo)} /></Field>
        </div>
        {value.trim() && parseVnd(value) !== null && <p class="hint">= {formatVnd(parseVnd(value)!)}</p>}
        <button type="button" class="btn text" onClick={() => setMore(!more)}>{more ? "Ẩn" : "Hiện"} tuỳ chọn thêm</button>
        {more && (
          <>
            <Field label="Số ngày báo trước theo hợp đồng" hint="Nếu hợp đồng phải báo trước (VD 30 ngày), các lời nhắc sẽ lùi lại tương ứng. Thường để trống.">
              <input inputMode="numeric" value={notice} onInput={input(setNotice)} />
            </Field>
            <Field label="Ghi chú"><textarea rows={3} value={note} maxLength={2000} onInput={input(setNote)} /></Field>
          </>
        )}
        {error && <p class="alert" role="alert">{error}</p>}
        <footer>
          {!props.isNew && props.onDelete && <button type="button" class="btn danger" onClick={() => props.onDelete!(l)}><Icon name="trash" size={18} /> Xoá</button>}
          <button type="submit" class="btn solid grow">Lưu</button>
        </footer>
      </form>
    </Sheet>
  );
}

// ---------------------------------------------------------------- renew / lost / snooze

export function RenewSheet(props: { license: License; suggested: RenewalInput; onSave: (r: RenewalInput) => void; onClose: () => void }) {
  const s = props.suggested;
  const [start, setStart] = useState(s.startDate);
  const [end, setEnd] = useState(s.endDate);
  const [value, setValue] = useState(s.value ? String(s.value) : "");
  const [contractNo, setContractNo] = useState("");
  const [error, setError] = useState("");
  const submit = (e: Event) => {
    e.preventDefault();
    if (!isIsoDate(start) || !isIsoDate(end)) return setError("Chọn đủ ngày bắt đầu và ngày hết hạn của kỳ mới");
    if (start > end) return setError("Ngày bắt đầu phải trước ngày hết hạn");
    const money = value.trim() ? parseVnd(value) : null;
    if (value.trim() && money === null) return setError("Giá trị chưa đúng, VD: 50 triệu");
    props.onSave({ startDate: start, endDate: end, ...(s.termMonths ? { termMonths: s.termMonths } : {}), ...(money ? { value: money } : {}), ...(contractNo.trim() ? { contractNo: contractNo.trim().slice(0, 60) } : {}) });
  };
  return (
    <Sheet title="Gia hạn xong! Kỳ tiếp theo" onClose={props.onClose}>
      <form class="form" onSubmit={submit}>
        <p class="muted">Chỉ cần kiểm tra lại các ngày của kỳ mới. App sẽ tự theo dõi kỳ này và nhắc bạn trước khi hết hạn.</p>
        <div class="two">
          <Field label="Bắt đầu"><input type="date" value={start} onInput={input(setStart)} /></Field>
          <Field label="Hết hạn"><input type="date" value={end} onInput={input(setEnd)} /></Field>
        </div>
        <div class="two">
          <Field label="Giá trị (đ)"><input inputMode="numeric" value={value} onInput={input(setValue)} /></Field>
          <Field label="Số hợp đồng mới"><input value={contractNo} maxLength={60} onInput={input(setContractNo)} /></Field>
        </div>
        {error && <p class="alert" role="alert">{error}</p>}
        <footer><button type="submit" class="btn solid grow">Lưu kỳ mới</button></footer>
      </form>
    </Sheet>
  );
}

export function LostSheet(props: { onSave: (reason: string | undefined) => void; onClose: () => void }) {
  const [reason, setReason] = useState<string>("");
  const [other, setOther] = useState("");
  const text = reason === "Khác" ? other.trim() || "Khác" : reason;
  return (
    <Sheet title="Khách không gia hạn" onClose={props.onClose}>
      <p class="muted">Chọn lý do để sau này biết vì sao mất khách (không bắt buộc).</p>
      <div class="reasons" role="radiogroup" aria-label="Lý do">
        {LOST_REASONS.map((r) => <button type="button" key={r} role="radio" aria-checked={reason === r} class={reason === r ? "on" : ""} onClick={() => setReason(reason === r ? "" : r)}>{r}</button>)}
      </div>
      {reason === "Khác" && <input value={other} maxLength={200} placeholder="Lý do khác" autofocus onInput={input(setOther)} />}
      <button type="button" class="btn danger-solid wide" onClick={() => props.onSave(text || undefined)}>Đóng lại: không gia hạn</button>
    </Sheet>
  );
}

export function SnoozeSheet(props: { onPick: (days: number) => void; onClose: () => void }) {
  return (
    <Sheet title="Nhắc lại sau" onClose={props.onClose}>
      <p class="muted">Ẩn lời nhắc này một thời gian. Mốc hết hạn vẫn được giữ nguyên.</p>
      <div class="stack">
        {[[1, "Ngày mai"], [3, "3 ngày nữa"], [7, "1 tuần nữa"], [14, "2 tuần nữa"]].map(([d, label]) => (
          <button key={d} class="btn ghost wide" onClick={() => props.onPick(d as number)}>{label}</button>
        ))}
      </div>
    </Sheet>
  );
}
