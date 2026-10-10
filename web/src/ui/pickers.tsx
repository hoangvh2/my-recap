import { useMemo, useState } from "preact/hooks";
import { blankCustomer, saveCustomer } from "../data";
import { fold } from "../lib/views";
import { isOpenLicense } from "../../../shared/renewals";
import { Icon } from "./icons";
import { useGraph, useUid } from "./hooks";
import { Sheet } from "./common";
import { showToast } from "./toast";

/** Choose a customer from a searchable list, or add a new one by typing its name. */
export function CustomerPicker(props: { value: string | undefined; onChange: (id: string | undefined) => void; optional?: boolean; label?: string }) {
  const g = useGraph();
  const uid = useUid();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const list = useMemo(() => {
    const f = fold(q.trim());
    return g.customers
      .filter((c) => c.status === "OPEN" && (!f || fold(`${c.name} ${c.contact ?? ""}`).includes(f)))
      .sort((a, b) => a.name.localeCompare(b.name, "vi"))
      .slice(0, 60);
  }, [g.customers, q]);
  const exact = list.some((c) => fold(c.name) === fold(q.trim()));
  const current = props.value ? g.customerById.get(props.value) : undefined;

  const choose = (id: string | undefined) => {
    props.onChange(id);
    setOpen(false);
    setQ("");
  };
  const create = async () => {
    const c = blankCustomer(q.trim().slice(0, 120));
    try {
      await saveCustomer(uid, c);
      choose(c.id);
    } catch {
      showToast("Chưa thêm được khách, kiểm tra mạng rồi thử lại");
    }
  };

  return (
    <>
      <button type="button" class={`picker ${current ? "" : "empty"}`} onClick={() => setOpen(true)}>
        <Icon name="building" size={18} />
        <span class="grow">{current ? current.name : props.label ?? "Chọn khách"}</span>
        <Icon name="chevronDown" size={16} />
      </button>
      {open && (
        <Sheet title="Chọn khách" level={2} onClose={() => setOpen(false)}>
          <input type="search" placeholder="Gõ tên khách để tìm…" value={q} autofocus onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
          <ul class="list pick-list">
            {props.optional && (
              <li><button type="button" class="pick-row" onClick={() => choose(undefined)}><span class="muted">Không gắn với khách nào</span></button></li>
            )}
            {list.map((c) => (
              <li key={c.id}>
                <button type="button" class={`pick-row ${c.id === props.value ? "on" : ""}`} onClick={() => choose(c.id)}>
                  <span class="title">{c.name}</span>
                  {c.contact && <span class="meta">{c.contact}</span>}
                </button>
              </li>
            ))}
          </ul>
          {list.length === 0 && !q.trim() && <p class="empty">Chưa có khách nào. Gõ tên để thêm khách đầu tiên.</p>}
          {q.trim() && !exact && (
            <button type="button" class="btn solid wide" onClick={() => void create()}><Icon name="plus" size={18} /> Thêm khách mới “{q.trim()}”</button>
          )}
        </Sheet>
      )}
    </>
  );
}

/** The licences of one customer, or "none". */
export function LicensePicker(props: { customerId: string | undefined; value: string | undefined; onChange: (id: string | undefined) => void }) {
  const g = useGraph();
  if (!props.customerId) return null;
  const options = g.licensesOf(props.customerId).filter((l) => l.status === "OPEN" && (isOpenLicense(l) || l.id === props.value));
  if (options.length === 0) return null;
  return (
    <label>
      License / bảo hành liên quan
      <select value={props.value ?? ""} onChange={(e) => props.onChange((e.target as HTMLSelectElement).value || undefined)}>
        <option value="">Không gắn license nào</option>
        {options.map((l) => <option key={l.id} value={l.id}>{l.product}</option>)}
      </select>
    </label>
  );
}
