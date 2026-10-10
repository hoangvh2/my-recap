import { useMemo, useState } from "preact/hooks";
import { formatDate } from "../../../shared/dates";
import { go, to } from "../lib/router";
import { createStore, useStore } from "../lib/store";
import { fold, matches } from "../lib/views";
import { Sheet } from "./common";
import { useGraph } from "./hooks";
import { openEditor } from "./host";
import { Icon } from "./icons";

const opened = createStore(false);
export const openSearch = (): void => opened.set(true);

/** One box that finds customers, licences and work, and jumps straight to the one you tap. */
export function SearchHost() {
  const open = useStore(opened);
  const g = useGraph();
  const [q, setQ] = useState("");
  const close = () => {
    opened.set(false);
    setQ("");
  };
  const f = fold(q.trim());
  const found = useMemo(() => {
    if (!f) return null;
    return {
      customers: g.customers.filter((c) => c.status === "OPEN" && fold(`${c.name} ${c.contact ?? ""} ${c.phone ?? ""} ${c.email ?? ""} ${c.note ?? ""}`).includes(f)).slice(0, 8),
      licenses: g.licenses.filter((l) => l.status === "OPEN" && fold(`${l.product} ${l.contractNo ?? ""} ${g.customerName(l.customerId)}`).includes(f)).slice(0, 8),
      items: g.items.filter((i) => i.status !== "DRAFT" && matches(i, q)).slice(0, 12),
    };
  }, [g, f, q]);
  if (!open) return null;
  const none = found && !found.customers.length && !found.licenses.length && !found.items.length;
  const hop = (hash: string) => {
    close();
    go(hash);
  };
  return (
    <Sheet title="Tìm kiếm" onClose={close}>
      <input type="search" placeholder="Tên khách, sản phẩm, số hợp đồng, việc…" value={q} autofocus onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
      {none && <p class="empty">Không tìm thấy.</p>}
      {found && found.customers.length > 0 && (
        <div><h4>Khách</h4><ul class="list">{found.customers.map((c) => (
          <li class="row-item" key={c.id}><span class="badge"><Icon name="building" size={18} /></span>
            <button class="row-main" onClick={() => hop(to.customer(c.id))}><span class="title">{c.name}</span>{c.contact && <span class="meta">{c.contact}</span>}</button></li>
        ))}</ul></div>
      )}
      {found && found.licenses.length > 0 && (
        <div><h4>License / bảo hành</h4><ul class="list">{found.licenses.map((l) => (
          <li class="row-item" key={l.id}><span class="badge"><Icon name="file" size={18} /></span>
            <button class="row-main" onClick={() => hop(to.license(l.id))}><span class="title">{l.product}</span><span class="meta">{g.customerName(l.customerId)} · hết hạn {formatDate(l.endDate)}</span></button></li>
        ))}</ul></div>
      )}
      {found && found.items.length > 0 && (
        <div><h4>Việc, lịch, ghi chú</h4><ul class="list">{found.items.map((i) => (
          <li class="row-item" key={i.id}><span class="badge"><Icon name="task" size={18} /></span>
            <button class="row-main" onClick={() => { close(); openEditor(i); }}><span class="title">{i.title}</span>{i.customerId && <span class="meta">{g.customerName(i.customerId)}</span>}</button></li>
        ))}</ul></div>
      )}
    </Sheet>
  );
}
