import { addDays, type ISODate } from "../../../shared/dates";
import { nextPeriod } from "../../../shared/renewals";
import type { NudgeKind } from "../../../shared/renewals";
import { NEXT_STEP, stageRank, type License, type Proposal, type Stage } from "./model";

/** Moves a licence to a stage. A new stage restarts the "no news" clock and lifts any snooze. */
export function moveStage(l: License, stage: Stage, now: number, lostReason?: string): License {
  const out: License = { ...l, stage, stageAt: now, updatedAt: now };
  delete out.snoozeUntil;
  if (stage === "LOST") {
    if (lostReason) out.lostReason = lostReason;
    else delete out.lostReason;
  } else delete out.lostReason;
  return out;
}

/** Hides this licence's reminders for a few days. */
export function snooze(l: License, today: ISODate, days: number, now: number): License {
  return { ...l, snoozeUntil: addDays(today, days), updatedAt: now };
}

export interface RenewalInput {
  startDate: ISODate;
  endDate: ISODate;
  termMonths?: number;
  value?: number;
  contractNo?: string;
}

/** Suggested values for the next period, shown for the owner to confirm. */
export function suggestRenewal(l: License): RenewalInput {
  const p = nextPeriod(l);
  return { startDate: p.startDate, endDate: p.endDate, termMonths: p.termMonths, ...(l.value ? { value: l.value } : {}) };
}

/** Closing the old period as renewed and opening the next one, linked both ways. */
export function renew(old: License, newId: string, input: RenewalInput, now: number): { old: License; next: License } {
  const next: License = {
    id: newId, customerId: old.customerId, status: "OPEN", product: old.product, kind: old.kind,
    startDate: input.startDate, endDate: input.endDate, stage: "ACTIVE", stageAt: now,
    renewedFromId: old.id, createdAt: now, updatedAt: now,
  };
  if (input.termMonths) next.termMonths = input.termMonths;
  if (input.value) next.value = input.value;
  if (input.contractNo) next.contractNo = input.contractNo;
  if (old.noticeDays) next.noticeDays = old.noticeDays;
  return { old: { ...moveStage(old, "RENEWED", now), renewedToId: newId }, next };
}

/** Applies what the AI proposed after the owner agreed. */
export function applyProposal(l: License, p: Proposal, now: number): License {
  let out = l;
  if (p.patch.stage) out = moveStage(out, p.patch.stage, now, p.patch.lostReason);
  else if (p.patch.lostReason && l.stage === "LOST") out = { ...out, lostReason: p.patch.lostReason };
  if (p.patch.endDate) out = { ...out, endDate: p.patch.endDate };
  if (p.patch.value) out = { ...out, value: p.patch.value };
  if (p.patch.note) out = { ...out, note: [l.note, p.patch.note].filter(Boolean).join("\n").slice(0, 2000) };
  return { ...out, updatedAt: now };
}

const STEP_LABEL: Partial<Record<Stage, string>> = { ASKED: "Đã hỏi khách", QUOTED: "Đã gửi báo giá", CONTRACTING: "Đã gửi hợp đồng", RENEWED: "Khách đã ký, gia hạn xong" };
const KIND_TARGET: Partial<Record<NudgeKind, Stage>> = { ASK: "ASKED", QUOTE: "QUOTED", CONTRACT: "CONTRACTING" };

/**
 * The one button for a licence: what the owner has just done. It follows the reminder on screen, so
 * "Gửi báo giá" is answered by "Đã gửi báo giá" even if the licence was never marked as asked.
 */
export function stepFor(l: License, kind?: NudgeKind): { to: Stage; label: string } | null {
  if (l.stage === "RENEWED" || l.stage === "LOST") return null;
  if (l.stage === "CONTRACTING") return { to: "RENEWED", label: STEP_LABEL.RENEWED! };
  const target = kind ? KIND_TARGET[kind] : undefined;
  if (target && stageRank(target) > stageRank(l.stage)) return { to: target, label: STEP_LABEL[target]! };
  return NEXT_STEP[l.stage];
}
