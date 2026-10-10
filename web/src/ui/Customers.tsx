import { useMemo, useState } from "preact/hooks";
import { nextExpiryOf, openValueOf, urgencyOf, isOpenLicense, type Urgency } from "../../../shared/renewals";
import {
  blankCustomer, blankItem, blankLicense, deleteCustomer, restore, saveCustomer, saveLicense, setDone,
} from "../data";
import { formatVnd } from "../lib/money";
import { customerCascade } from "../lib/graph";
import type { Customer, License } from "../lib/model";
import { go, to } from "../lib/router";
import { fold } from "../lib/views";
import { LicenseRow } from "./actions";
import { confirmDialog, DaysChip, Empty, Link, Section, TopBar } from "./common";
import { CustomerForm, LicenseForm } from "./forms";
import { useCtx, useGraph, useUid } from "./hooks";
import { openCapture, openEditor } from "./host";
import { Icon } from "./icons";
import { ItemRow } from "./ItemRow";
import { Review } from "./Review";
import { showToast } from "./toast";

const RANK: Record<Urgency | "none", number> = { danger: 0, warn: 1, info: 2, calm: 3, done: 4, none: 5 };

export function Customers() {
  const g = useGraph();
  const ctx = useCtx();
  const uid = useUid();
  const [q, setQ] = useState("");
  const [adding, setAdding] = useState<Customer | null>(null);

  const rows = useMemo(() => {
    const f = fold(q.trim());
    return g.customers
      .filter((c) => c.status === "OPEN" && (!f || fold(`${c.name} ${c.contact ?? ""} ${c.phone ?? ""} ${c.email ?? ""}`).includes(f)))
      .map((c) => {
        const open = g.licensesOf(c.id).filter((l) => l.status === "OPEN" && isOpenLicense(l));
        const soonest = open[0];
        const worst = open.reduce<Urgency | "none">((w, l) => (RANK[urgencyOf(l, ctx)] < RANK[w] ? urgencyOf(l, ctx) : w), "none");
        return { c, open, soonest, rank: RANK[worst], total: g.licensesOf(c.id).filter((l) => l.status === "OPEN").length };
      })
      .sort((a, b) => a.rank - b.rank || (a.soonest?.endDate ?? "9").localeCompare(b.soonest?.endDate ?? "9") || a.c.name.localeCompare(b.c.name, "vi"));
  }, [g, ctx, q]);

  return (
    <>
      <TopBar title="Khách hàng" sub={`${rows.length} khách`} right={
        <button class="btn solid small" onClick={() => setAdding(blankCustomer())}><Icon name="plus" size={18} /> Thêm khách</button>
      } />
      <input class="search" type="search" placeholder="Tìm tên khách, người liên hệ, số điện thoại…" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
      {rows.length === 0 ? (
        q.trim() ? <Empty>Không tìm thấy khách nào.</Empty> : (
          <Empty>Chưa có khách nào. Bấm “Thêm khách”, hoặc <Link to={to.import}>nhập từ Excel</Link>, hoặc nói vào Ghi nhanh.</Empty>
        )
      ) : (
        <ul class="list cust-list">
          {rows.map(({ c, soonest, total }) => (
            <li class="cust-row" key={c.id}>
              <Link to={to.customer(c.id)} class="cust-main">
                <span class="title">{c.name}</span>
                {(c.contact || c.phone) && <span class="meta">{[c.contact, c.phone].filter(Boolean).join(" · ")}</span>}
                <span class="meta">{total === 0 ? "Chưa có license" : `${total} license${soonest ? ` · gần nhất: ${soonest.product}` : ""}`}</span>
              </Link>
              {soonest && <div class="lic-side"><DaysChip license={soonest} ctx={ctx} /></div>}
            </li>
          ))}
        </ul>
      )}
      {adding && (
        <CustomerForm customer={adding} isNew onClose={() => setAdding(null)} onSave={(c) => {
          setAdding(null);
          saveCustomer(uid, c).then(() => go(to.customer(c.id))).catch(() => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại"));
        }} />
      )}
    </>
  );
}

export function CustomerDetail({ id }: { id: string }) {
  const g = useGraph();
  const uid = useUid();
  const [editing, setEditing] = useState(false);
  const [newLicense, setNewLicense] = useState<License | null>(null);
  const [showClosed, setShowClosed] = useState(false);
  const [showDone, setShowDone] = useState(false);
  const c = g.customerById.get(id);
  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");

  if (!c) {
    return (
      <>
        <TopBar title="Khách hàng" backTo={to.customers} />
        <Empty>Không tìm thấy khách này. Có thể đã bị xoá.</Empty>
      </>
    );
  }
  const licenses = g.licensesOf(id).filter((l) => l.status === "OPEN");
  const openLic = licenses.filter(isOpenLicense);
  const closedLic = licenses.filter((l) => !isOpenLicense(l));
  const work = g.itemsOfCustomer(id).filter((i) => i.status !== "DRAFT");
  const openWork = work.filter((i) => i.status === "OPEN" && (i.type === "TASK" || i.type === "EVENT")).sort((a, b) => (a.whenAt ?? Infinity) - (b.whenAt ?? Infinity));
  const notes = work.filter((i) => i.type === "NOTE").sort((a, b) => b.createdAt - a.createdAt);
  const history = work.filter((i) => i.status === "DONE").sort((a, b) => (b.doneAt ?? 0) - (a.doneAt ?? 0));
  const value = openValueOf(licenses);
  const nextEnd = nextExpiryOf(licenses);

  const remove = async () => {
    const { licenses: ls, items: is } = customerCascade(g, id);
    const ok = await confirmDialog({
      title: `Xoá khách “${c.name}”?`,
      body: ls.length || is.length ? `Sẽ xoá luôn ${ls.length} license và ${is.length} việc/ghi chú liên quan.` : "Khách này chưa có license hay việc nào.",
      ok: "Xoá",
      danger: true,
    });
    if (!ok) return;
    setEditing(false);
    try {
      const gone = await deleteCustomer(uid, g, id);
      go(to.customers, { replace: true });
      showToast(`Đã xoá ${c.name}`, { label: "Hoàn tác", run: () => void restore(uid, gone).catch(fail) });
    } catch {
      fail();
    }
  };

  return (
    <>
      <TopBar title={c.name} sub={c.contact} backTo={to.customers} right={
        <button class="icon-btn" aria-label="Sửa thông tin khách" onClick={() => setEditing(true)}><Icon name="pencil" /></button>
      } />

      {(c.phone || c.email || c.note) && (
        <div class="card-block">
          {c.phone && <a class="contact-line" href={`tel:${c.phone.replace(/[^\d+]/g, "")}`}><Icon name="phone" size={20} /> {c.phone}</a>}
          {c.email && <a class="contact-line" href={`mailto:${c.email}`}><Icon name="mail" size={20} /> {c.email}</a>}
          {c.note && <p class="note-text">{c.note}</p>}
        </div>
      )}

      <div class="actions">
        {c.phone && <a class="btn solid" href={`tel:${c.phone.replace(/[^\d+]/g, "")}`}><Icon name="phone" size={18} /> Gọi</a>}
        <button class="btn ghost" onClick={() => openCapture({ customerId: id })}><Icon name="mic" size={18} /> Ghi nhanh</button>
        <button class="btn ghost" onClick={() => openEditor(blankItem("TASK", { customerId: id }), true)}><Icon name="plus" size={18} /> Việc</button>
        <button class="btn ghost" onClick={() => setNewLicense({ ...blankLicense(id) })}><Icon name="plus" size={18} /> License</button>
      </div>

      {licenses.length > 0 && (
        <p class="summary">
          {openLic.length} license đang theo dõi{value > 0 ? ` · ${formatVnd(value)}` : ""}{nextEnd ? ` · hết hạn gần nhất ${nextEnd.split("-").reverse().join("/")}` : ""}
        </p>
      )}

      <Review filter={{ customerId: id }} />

      <Section title="License / bảo hành" count={openLic.length}>
        {openLic.length > 0 ? (
          <ul class="list">{openLic.map((l) => <LicenseRow key={l.id} license={l} showCustomer={false} />)}</ul>
        ) : (
          <Empty>Chưa có license nào đang theo dõi. Bấm “+ License” để thêm.</Empty>
        )}
        {closedLic.length > 0 && (
          <>
            <button class="btn text" onClick={() => setShowClosed(!showClosed)}>{showClosed ? "Ẩn" : "Hiện"} license đã đóng ({closedLic.length})</button>
            {showClosed && <ul class="list">{closedLic.map((l) => <LicenseRow key={l.id} license={l} showCustomer={false} />)}</ul>}
          </>
        )}
      </Section>

      <Section title="Việc và lịch hẹn" count={openWork.length}>
        {openWork.length > 0 ? (
          <ul class="list">{openWork.map((i) => <ItemRow key={i.id} item={i} hideCustomer onOpen={(x) => openEditor(x)} onToggle={(x, d) => void setDone(uid, x, d).catch(fail)} />)}</ul>
        ) : (
          <Empty>Chưa có việc nào cho khách này.</Empty>
        )}
      </Section>

      {notes.length > 0 && (
        <Section title="Ghi chú" count={notes.length}>
          <ul class="list">{notes.map((i) => <ItemRow key={i.id} item={i} hideCustomer showDate={false} onOpen={(x) => openEditor(x)} />)}</ul>
        </Section>
      )}

      {history.length > 0 && (
        <>
          <button class="btn text" onClick={() => setShowDone(!showDone)}>{showDone ? "Ẩn" : "Hiện"} việc đã xong ({history.length})</button>
          {showDone && <ul class="list">{history.slice(0, 50).map((i) => <ItemRow key={i.id} item={i} hideCustomer onOpen={(x) => openEditor(x)} onToggle={(x, d) => void setDone(uid, x, d).catch(fail)} />)}</ul>}
        </>
      )}

      {editing && <CustomerForm customer={c} isNew={false} onClose={() => setEditing(false)} onDelete={() => void remove()}
        onSave={(next) => { setEditing(false); saveCustomer(uid, next).catch(fail); }} />}
      {newLicense && <LicenseForm license={newLicense} isNew lockCustomer onClose={() => setNewLicense(null)}
        onSave={(l) => { setNewLicense(null); saveLicense(uid, l).then(() => go(to.license(l.id))).catch(fail); }} />}
    </>
  );
}

