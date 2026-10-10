import { useMemo, useState } from "preact/hooks";
import { formatDate } from "../../../shared/dates";
import {
  captures, confirmCapture, discardCapture, dismissProposal, dropDraftCustomer, dropDraftItem, dropDraftLicense,
  pendingOf, proposals as proposalStore, saveCustomer, saveItem, saveLicense,
} from "../data";
import { formatVnd } from "../lib/money";
import { KIND_LABEL, type Customer, type Item, type License } from "../lib/model";
import { useStore } from "../lib/store";
import { Icon } from "./icons";
import { useGraph, useUid } from "./hooks";
import { Editor } from "./Editor";
import { CustomerForm, LicenseForm } from "./forms";
import { ItemRow } from "./ItemRow";
import { Section } from "./common";
import { showToast } from "./toast";

export interface ReviewFilter {
  customerId?: string;
  licenseId?: string;
}

function DraftRow(props: { icon: "building" | "file" | "renew"; title: string; meta?: string; onOpen?: () => void; onDrop: () => void; dropLabel: string }) {
  return (
    <li class="row-item">
      <span class="badge"><Icon name={props.icon} size={18} /></span>
      {props.onOpen ? (
        <button class="row-main" onClick={props.onOpen}>
          <span class="title">{props.title}</span>
          {props.meta && <span class="meta">{props.meta}</span>}
        </button>
      ) : (
        <div class="row-main"><span class="title">{props.title}</span>{props.meta && <span class="meta">{props.meta}</span>}</div>
      )}
      <button class="icon-btn" aria-label={props.dropLabel} onClick={props.onDrop}><Icon name="close" size={18} /></button>
    </li>
  );
}

/**
 * What the AI understood from a note, waiting for a yes: new customers, new licences, work, and
 * changes to renewals. Nothing is kept until "Lưu tất cả"; every line can be fixed or dropped first.
 */
export function Review({ filter }: { filter?: ReviewFilter }) {
  const g = useGraph();
  const uid = useUid();
  const caps = useStore(captures);
  const props = useStore(proposalStore);
  const [editCustomer, setEditCustomer] = useState<Customer | null>(null);
  const [editLicense, setEditLicense] = useState<License | null>(null);
  const [editItem, setEditItem] = useState<Item | null>(null);

  const groups = useMemo(() => {
    const ids = new Set<string>();
    for (const i of g.items) if (i.status === "DRAFT" && i.sourceId) ids.add(i.sourceId);
    for (const c of g.customers) if (c.status === "DRAFT" && c.sourceId) ids.add(c.sourceId);
    for (const l of g.licenses) if (l.status === "DRAFT" && l.sourceId) ids.add(l.sourceId);
    for (const p of props) ids.add(p.captureId);
    return [...ids]
      .map((id) => ({ id, ...pendingOf(id, g, props), cap: caps.find((c) => c.id === id) }))
      .filter((p) => {
        if (!filter?.customerId && !filter?.licenseId) return true;
        return (
          p.items.some((i) => i.customerId === filter.customerId || (filter.licenseId && i.licenseId === filter.licenseId)) ||
          p.licenses.some((l) => l.customerId === filter.customerId) ||
          p.customers.some((c) => c.id === filter.customerId) ||
          p.proposals.some((x) => x.customerId === filter.customerId || x.licenseId === filter.licenseId)
        );
      })
      .sort((a, b) => (a.cap?.createdAt ?? 0) - (b.cap?.createdAt ?? 0));
  }, [g, props, caps, filter?.customerId, filter?.licenseId]);

  if (groups.length === 0) return null;
  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");

  return (
    <Section title="Chờ bạn xem lại" count={groups.length}>
      {groups.map((p) => (
        <div class="draft-card" key={p.id}>
          {p.cap && (
            <details>
              <summary>“{p.cap.transcript.length > 90 ? `${p.cap.transcript.slice(0, 90)}…` : p.cap.transcript}”</summary>
              <p class="transcript">{p.cap.transcript}</p>
            </details>
          )}
          {p.customers.length > 0 && (
            <div>
              <h4>Khách mới</h4>
              <ul class="list">
                {p.customers.map((c) => (
                  <DraftRow key={c.id} icon="building" title={c.name} meta={[c.contact, c.phone].filter(Boolean).join(" · ")} dropLabel={`Bỏ khách ${c.name}`}
                    onOpen={() => setEditCustomer(c)} onDrop={() => void dropDraftCustomer(uid, g, c).catch(fail)} />
                ))}
              </ul>
            </div>
          )}
          {p.licenses.length > 0 && (
            <div>
              <h4>License / bảo hành mới</h4>
              <ul class="list">
                {p.licenses.map((l) => (
                  <DraftRow key={l.id} icon="file" title={`${g.customerName(l.customerId)} · ${l.product}`}
                    meta={`${KIND_LABEL[l.kind]} · hết hạn ${formatDate(l.endDate)}${l.value ? ` · ${formatVnd(l.value)}` : ""}`} dropLabel={`Bỏ ${l.product}`}
                    onOpen={() => setEditLicense(l)} onDrop={() => void dropDraftLicense(uid, g, l).catch(fail)} />
                ))}
              </ul>
            </div>
          )}
          {p.items.length > 0 && (
            <div>
              <h4>Việc, lịch hẹn, ghi chú</h4>
              <ul class="list">
                {p.items.map((i) => (
                  <ItemRow key={i.id} item={i} onOpen={setEditItem} />
                ))}
              </ul>
            </div>
          )}
          {p.proposals.length > 0 && (
            <div>
              <h4>Cập nhật gia hạn</h4>
              <ul class="list">
                {p.proposals.map((x) => (
                  <DraftRow key={x.id} icon="renew" title={x.summary} dropLabel="Bỏ gợi ý này" onDrop={() => void dismissProposal(uid, x).catch(fail)} />
                ))}
              </ul>
            </div>
          )}
          <div class="row gap">
            <button class="btn ghost" onClick={async () => {
              const undo = await discardCapture(uid, g, p.id, props).catch(() => { fail(); return null; });
              if (undo) showToast("Đã bỏ ghi chú nháp", { label: "Hoàn tác", run: () => void undo().catch(fail) });
            }}>Bỏ hết</button>
            <button class="btn solid grow" onClick={() => void confirmCapture(uid, g, p.id, props).then(() => showToast("Đã lưu")).catch(fail)}>Lưu tất cả</button>
          </div>
        </div>
      ))}

      {editCustomer && <CustomerForm customer={editCustomer} isNew={false} onClose={() => setEditCustomer(null)}
        onSave={(c) => { setEditCustomer(null); void saveCustomer(uid, c).catch(fail); }} onDelete={async (c) => { setEditCustomer(null); await dropDraftCustomer(uid, g, c).catch(fail); }} />}
      {editLicense && <LicenseForm license={editLicense} isNew={false} onClose={() => setEditLicense(null)}
        onSave={(l) => { setEditLicense(null); void saveLicense(uid, l).catch(fail); }} onDelete={async (l) => { setEditLicense(null); await dropDraftLicense(uid, g, l).catch(fail); }} />}
      {editItem && <Editor key={editItem.id} item={editItem} isNew={false} onClose={() => setEditItem(null)}
        onSave={(i) => { setEditItem(null); void saveItem(uid, i).catch(fail); }} onDelete={(i) => { setEditItem(null); void dropDraftItem(uid, i).catch(fail); }} />}
    </Section>
  );
}

