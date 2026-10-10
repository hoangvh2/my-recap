import { useMemo, useState } from "preact/hooks";
import { diffDays, formatDate } from "../../../shared/dates";
import { buildLicenseCalendar } from "../lib/ics";
import { calendarMilestones, daysLeftText, nudgeFor, offsetsFor, termOf } from "../../../shared/renewals";
import { acceptProposal, blankItem, deleteLicense, dismissProposal, proposals as proposalStore, saveLicense, setDone } from "../data";
import { formatVnd } from "../lib/money";
import { stepFor } from "../lib/flow";
import { KIND_LABEL, OPEN_STAGES, STAGE_LABEL, type License } from "../lib/model";
import { go, to } from "../lib/router";
import { useStore } from "../lib/store";
import { useLicenseActions } from "./actions";
import { confirmDialog, CustomerChip, DaysChip, Empty, KindChip, Link, Section, StageChip, TopBar } from "./common";
import { saveFile } from "./download";
import { LicenseForm } from "./forms";
import { useCtx, useGraph, useUid } from "./hooks";
import { openCapture, openEditor } from "./host";
import { Icon } from "./icons";
import { ItemRow } from "./ItemRow";
import { Review } from "./Review";
import { showToast } from "./toast";

/** The steps of a renewal as a ladder: where it is, what is done, what comes next. */
function Ladder({ license }: { license: License }) {
  const here = OPEN_STAGES.indexOf(license.stage);
  const closed = license.stage === "RENEWED" || license.stage === "LOST";
  const steps = ["Chưa liên hệ", "Đã hỏi", "Báo giá", "Hợp đồng"];
  return (
    <ol class="ladder" aria-label="Tiến độ gia hạn">
      {steps.map((label, i) => (
        <li key={label} class={closed ? (license.stage === "RENEWED" ? "done" : "") : i < here ? "done" : i === here ? "here" : ""}>
          <span class="dot-step">{(closed ? license.stage === "RENEWED" : i < here) ? <Icon name="check" size={14} /> : i + 1}</span>
          <span>{label}</span>
        </li>
      ))}
    </ol>
  );
}

export function LicenseDetail({ id }: { id: string }) {
  const g = useGraph();
  const ctx = useCtx();
  const uid = useUid();
  const actions = useLicenseActions();
  const proposals = useStore(proposalStore);
  const [editing, setEditing] = useState(false);
  const l = g.licenseById.get(id);
  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
  const mine = useMemo(() => proposals.filter((p) => p.licenseId === id), [proposals, id]);

  if (!l || l.status === "DRAFT") {
    return (
      <>
        <TopBar title="License" backTo={to.renewals} />
        <Empty>{l ? "License này đang chờ bạn xem lại ở màn hình Hôm nay." : "Không tìm thấy license này. Có thể đã bị xoá."}</Empty>
        {l && <Review filter={{ licenseId: id, customerId: l.customerId }} />}
      </>
    );
  }
  const c = g.customerById.get(l.customerId);
  const nudge = nudgeFor(l, ctx);
  const open = l.stage !== "RENEWED" && l.stage !== "LOST";
  const days = diffDays(ctx.today, l.endDate);
  const marks = calendarMilestones(l, ctx);
  const offsets = offsetsFor(l, ctx.prefs);
  const term = termOf(l);
  const work = g.itemsOfLicense(id).filter((i) => i.status !== "DRAFT");
  const openWork = work.filter((i) => i.status === "OPEN").sort((a, b) => (a.whenAt ?? Infinity) - (b.whenAt ?? Infinity));
  const done = work.filter((i) => i.status === "DONE");
  const prev = l.renewedFromId ? g.licenseById.get(l.renewedFromId) : undefined;
  const next = l.renewedToId ? g.licenseById.get(l.renewedToId) : undefined;
  const primary = open ? stepFor(l, nudge?.kind) : null;

  const remove = async () => {
    const ok = await confirmDialog({
      title: `Xoá license “${l.product}”?`,
      body: work.length ? `${work.length} việc/ghi chú liên quan vẫn giữ lại ở khách, chỉ bỏ liên kết với license này.` : undefined,
      ok: "Xoá",
      danger: true,
    });
    if (!ok) return;
    setEditing(false);
    try {
      const undo = await deleteLicense(uid, g, l);
      go(to.customer(l.customerId), { replace: true });
      showToast("Đã xoá license", { label: "Hoàn tác", run: () => void undo().catch(fail) });
    } catch {
      fail();
    }
  };

  const exportIcs = async () => {
    const text = buildLicenseCalendar(l, c?.name ?? "Khách", ctx);
    if (!text) return showToast("License này không còn mốc nhắc nào phía trước");
    await saveFile(`gia-han-${l.product.slice(0, 30).replace(/[^\p{L}\p{N}]+/gu, "-")}.ics`, "text/calendar", text);
  };

  return (
    <>
      <TopBar title={l.product} backTo={to.renewals} right={<button class="icon-btn" aria-label="Sửa license" onClick={() => setEditing(true)}><Icon name="pencil" /></button>} />
      <div class="links big-links">
        <CustomerChip id={l.customerId} />
        <KindChip kind={l.kind} />
        <StageChip stage={l.stage} />
      </div>

      <div class="hero-card">
        <div class="when">
          <span class="muted">Hết hạn</span>
          <strong>{formatDate(l.endDate)}</strong>
          {open ? <DaysChip license={l} ctx={ctx} /> : <span class={`chip ${l.stage === "RENEWED" ? "u-calm" : "u-done"}`}>{STAGE_LABEL[l.stage]}</span>}
        </div>
        {open && days < 0 && <p class="alert">License đã hết hạn {-days} ngày mà chưa chốt.</p>}
        <Ladder license={l} />
        {open && (
          <div class="next-step">
            {nudge ? <p class="next-title"><Icon name="warn" size={18} /> {nudge.action}</p> : <p class="next-title ok"><Icon name="check" size={18} /> Chưa đến lúc xử lý. Hệ thống sẽ nhắc bạn.</p>}
            <div class="stack">
              {primary && (
                <button class="btn solid wide" onClick={() => actions.progress(l, nudge?.kind)}>
                  <Icon name="check" size={18} /> {primary.label}
                </button>
              )}
              <div class="row gap">
                {c?.phone && <a class="btn ghost grow" href={`tel:${c.phone.replace(/[^\d+]/g, "")}`}><Icon name="phone" size={18} /> Gọi khách</a>}
                <button class="btn ghost grow" onClick={() => actions.later(l)}>Để sau</button>
                <button class="btn danger grow" onClick={() => actions.markLost(l)}>Không gia hạn</button>
              </div>
            </div>
          </div>
        )}
        {!open && l.stage === "LOST" && (
          <div class="next-step">
            {l.lostReason && <p class="muted">Lý do: {l.lostReason}</p>}
            <button class="btn ghost wide" onClick={() => actions.reopen(l)}>Mở lại để theo dõi</button>
          </div>
        )}
        {!open && l.stage === "RENEWED" && next && (
          <div class="next-step"><Link to={to.license(next.id)} class="btn solid wide">Xem kỳ mới (đến {formatDate(next.endDate)})</Link></div>
        )}
        {l.snoozeUntil && l.snoozeUntil > ctx.today && <p class="hint">Đang ẩn lời nhắc đến {formatDate(l.snoozeUntil)}.</p>}
      </div>

      {mine.length > 0 && (
        <Section title="Gợi ý từ ghi chú" count={mine.length}>
          <ul class="list">
            {mine.map((p) => (
              <li class="row-item" key={p.id}>
                <span class="badge"><Icon name="renew" size={18} /></span>
                <div class="row-main"><span class="title">{p.summary}</span></div>
                <button class="btn solid small" onClick={() => void acceptProposal(uid, g, p).then(() => showToast("Đã cập nhật")).catch(fail)}>Áp dụng</button>
                <button class="icon-btn" aria-label="Bỏ gợi ý" onClick={() => void dismissProposal(uid, p).catch(fail)}><Icon name="close" size={18} /></button>
              </li>
            ))}
          </ul>
        </Section>
      )}
      <Review filter={{ licenseId: id, customerId: l.customerId }} />

      <div class="actions">
        <button class="btn ghost" onClick={() => openCapture({ licenseId: id, customerId: l.customerId })}><Icon name="mic" size={18} /> Ghi nhanh</button>
        <button class="btn ghost" onClick={() => openEditor(blankItem("TASK", { customerId: l.customerId, licenseId: id }), true)}><Icon name="plus" size={18} /> Việc</button>
        <button class="btn ghost" onClick={() => void exportIcs()}><Icon name="calendar" size={18} /> Thêm vào Lịch</button>
      </div>

      <Section title="Việc liên quan" count={openWork.length}>
        {openWork.length > 0 ? (
          <ul class="list">{openWork.map((i) => <ItemRow key={i.id} item={i} hideCustomer onOpen={(x) => openEditor(x)} onToggle={(x, d) => void setDone(uid, x, d).catch(fail)} />)}</ul>
        ) : (
          <Empty>Chưa có việc nào cho license này. Nói vào Ghi nhanh, ví dụ: “mai gọi anh Nam hỏi gia hạn”.</Empty>
        )}
        {done.length > 0 && <ul class="list">{done.slice(0, 20).map((i) => <ItemRow key={i.id} item={i} hideCustomer onOpen={(x) => openEditor(x)} onToggle={(x, d) => void setDone(uid, x, d).catch(fail)} />)}</ul>}
      </Section>

      {open && marks.length > 0 && (
        <Section title="Lịch nhắc phía trước">
          <ul class="list plain">
            {marks.map((m) => (
              <li class="row-item" key={m.kind}>
                <span class="badge"><Icon name="clock" size={18} /></span>
                <div class="row-main"><span class="title">{m.action}</span><span class="meta">{formatDate(m.date)} · {daysLeftText(diffDays(ctx.today, m.date)).replace("Còn", "sau")}</span></div>
              </li>
            ))}
          </ul>
          <p class="hint">Mốc nhắc: {offsets.join(" / ")} ngày trước hạn{term ? ` (kỳ ${term} tháng)` : ""}. Đổi ở Cài đặt.</p>
        </Section>
      )}

      <Section title="Chi tiết">
        <dl class="facts">
          <dt>Loại</dt><dd>{KIND_LABEL[l.kind]}</dd>
          {l.startDate && <><dt>Bắt đầu</dt><dd>{formatDate(l.startDate)}</dd></>}
          <dt>Hết hạn</dt><dd>{formatDate(l.endDate)}</dd>
          {term && <><dt>Thời hạn</dt><dd>{term} tháng</dd></>}
          {l.value ? <><dt>Giá trị</dt><dd>{formatVnd(l.value)}</dd></> : null}
          {l.contractNo && <><dt>Số hợp đồng</dt><dd>{l.contractNo}</dd></>}
          {l.noticeDays ? <><dt>Báo trước</dt><dd>{l.noticeDays} ngày</dd></> : null}
          {prev && <><dt>Kỳ trước</dt><dd><Link to={to.license(prev.id)}>đến {formatDate(prev.endDate)}</Link></dd></>}
          {next && <><dt>Kỳ tiếp</dt><dd><Link to={to.license(next.id)}>đến {formatDate(next.endDate)}</Link></dd></>}
        </dl>
        {l.note && <p class="note-text">{l.note}</p>}
      </Section>

      {actions.dialogs}
      {editing && <LicenseForm license={l} isNew={false} onClose={() => setEditing(false)} onDelete={() => void remove()}
        onSave={(next2) => { setEditing(false); saveLicense(uid, next2).catch(fail); }} />}
    </>
  );
}
