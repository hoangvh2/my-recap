import { useState } from "preact/hooks";
import { diffDays } from "../../../shared/dates";
import { daysLeftText, type Nudge, type NudgeKind } from "../../../shared/renewals";
import { renew, saveLicense } from "../data";
import { moveStage, snooze, stepFor, suggestRenewal } from "../lib/flow";
import type { License } from "../lib/model";
import { go, to } from "../lib/router";
import { Link, CustomerChip, DaysChip, StageChip } from "./common";
import { Icon } from "./icons";
import { useCtx, useGraph, useUid } from "./hooks";
import { LostSheet, RenewSheet, SnoozeSheet } from "./forms";
import { showToast } from "./toast";

type Dialog = { kind: "renew" | "lost" | "snooze"; license: License } | null;

/** The things you do to a licence while working a renewal, each one tap, each with a way back. */
export function useLicenseActions() {
  const uid = useUid();
  const ctx = useCtx();
  const [dialog, setDialog] = useState<Dialog>(null);
  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
  const write = (next: License, message: string, before: License) => {
    saveLicense(uid, next)
      .then(() => showToast(message, { label: "Hoàn tác", run: () => void saveLicense(uid, before).catch(fail) }))
      .catch(fail);
  };

  /** "Đã hỏi khách", "Đã gửi báo giá"…: moves to the next stage; the last step opens the renewal form. */
  const progress = (l: License, kind?: NudgeKind) => {
    const step = stepFor(l, kind);
    if (!step) return;
    if (step.to === "RENEWED") return setDialog({ kind: "renew", license: l });
    write(moveStage(l, step.to, Date.now()), `Đã ghi: ${step.label.toLowerCase()}`, l);
  };
  /** Asked again and still waiting: restart the clock without changing the stage. */
  const askedAgain = (l: License) => write(moveStage(l, l.stage, Date.now()), "Đã ghi: hỏi lại, chờ khách", l);
  const markLost = (l: License) => setDialog({ kind: "lost", license: l });
  const later = (l: License) => setDialog({ kind: "snooze", license: l });
  const reopen = (l: License) => write(moveStage(l, "ACTIVE", Date.now()), "Đã mở lại để theo dõi", l);

  const dialogs = dialog && (
    <>
      {dialog.kind === "renew" && (
        <RenewSheet
          license={dialog.license}
          suggested={suggestRenewal(dialog.license)}
          onClose={() => setDialog(null)}
          onSave={(input) => {
            const l = dialog.license;
            setDialog(null);
            renew(uid, l, input)
              .then((r) => showToast("Đã gia hạn. Kỳ mới đã được theo dõi", { label: "Xem kỳ mới", run: () => go(to.license(r.next.id)) }))
              .catch(fail);
          }}
        />
      )}
      {dialog.kind === "lost" && (
        <LostSheet
          onClose={() => setDialog(null)}
          onSave={(reason) => {
            const l = dialog.license;
            setDialog(null);
            write(moveStage(l, "LOST", Date.now(), reason), "Đã đóng: khách không gia hạn", l);
          }}
        />
      )}
      {dialog.kind === "snooze" && (
        <SnoozeSheet
          onClose={() => setDialog(null)}
          onPick={(days) => {
            const l = dialog.license;
            setDialog(null);
            write(snooze(l, ctx.today, days, Date.now()), `Sẽ nhắc lại sau ${days === 1 ? "1 ngày" : `${days} ngày`}`, l);
          }}
        />
      )}
    </>
  );
  return { progress, askedAgain, markLost, later, reopen, dialogs };
}

export type LicenseActions = ReturnType<typeof useLicenseActions>;

/** One renewal that needs attention: what to do, how long is left, and the buttons to record it. */
export function NudgeRow({ nudge, actions }: { nudge: Nudge; actions: LicenseActions }) {
  const g = useGraph();
  const ctx = useCtx();
  const l = g.licenseById.get(nudge.licenseId);
  if (!l) return null;
  const c = g.customerById.get(l.customerId);
  const primary = nudge.kind === "FOLLOW_UP" ? "Đã hỏi lại" : (stepFor(l, nudge.kind)?.label ?? "Xong");
  return (
    <li class={`nudge sev-${nudge.severity}`}>
      <div class="nudge-head">
        <Link to={to.license(l.id)} class="nudge-main">
          <span class="title">{l.product}</span>
          <span class="action">{nudge.action}</span>
          <span class="meta">{daysLeftText(diffDays(ctx.today, l.endDate))}</span>
        </Link>
        {c?.phone && <a class="call-btn" href={`tel:${c.phone.replace(/[^\d+]/g, "")}`} aria-label={`Gọi ${c.name}`}><Icon name="phone" size={20} /></a>}
      </div>
      <div class="links"><CustomerChip id={l.customerId} /><StageChip stage={l.stage} /></div>
      <div class="row gap nudge-actions">
        <button class="btn solid grow" onClick={() => (nudge.kind === "FOLLOW_UP" ? actions.askedAgain(l) : actions.progress(l, nudge.kind))}>
          <Icon name="check" size={18} /> {primary}
        </button>
        <button class="btn ghost" onClick={() => actions.later(l)}>Để sau</button>
      </div>
    </li>
  );
}

/** A licence in a list: product, who it belongs to, when it ends and where the renewal stands. */
export function LicenseRow({ license, showCustomer = true }: { license: License; showCustomer?: boolean }) {
  const ctx = useCtx();
  return (
    <li class="lic-row">
      <Link to={to.license(license.id)} class="lic-main">
        <span class="title">{license.product}</span>
        <span class="meta">Hết hạn {license.endDate.split("-").reverse().join("/")}</span>
      </Link>
      <div class="lic-side">
        <DaysChip license={license} ctx={ctx} />
        <StageChip stage={license.stage} />
      </div>
      {showCustomer && <div class="links"><CustomerChip id={license.customerId} /></div>}
    </li>
  );
}
