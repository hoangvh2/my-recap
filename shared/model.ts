import type { ISODate } from "./dates";

// ---------------------------------------------------------------- secretary items (unchanged shape)

export type ItemType = "TASK" | "EVENT" | "EXPENSE" | "NOTE";
export type ItemStatus = "DRAFT" | "OPEN" | "DONE";
export type Recurrence = "DAILY" | "WEEKDAYS" | "WEEKLY" | "MONTHLY";

/** One thing the secretary keeps. Same shape as the Android app and the Firestore rules. */
export interface Item {
  id: string;
  type: ItemType;
  status: ItemStatus;
  title: string;
  details: string;
  /** Deadline, start or expense date (epoch ms). With `allDay` only the date matters. */
  whenAt?: number;
  allDay: boolean;
  /** Whole VND, expenses only. */
  amount?: number;
  category?: string;
  place?: string;
  person?: string;
  sourceId?: string;
  quote?: string;
  createdAt: number;
  doneAt?: number;
  recurrence?: Recurrence;
  /** The customer this belongs to, and (optionally) the licence it is about. */
  customerId?: string;
  licenseId?: string;
}

export interface Capture {
  id: string;
  kind: "voice" | "text";
  transcript: string;
  createdAt: number;
  itemCount: number;
  customerCount?: number;
  licenseCount?: number;
  proposalCount?: number;
}

// ---------------------------------------------------------------- customers and licences

/** DRAFT = proposed by the AI from a capture, waiting for the owner. */
export type RecordStatus = "DRAFT" | "OPEN";

export interface Customer {
  id: string;
  status: RecordStatus;
  /** The company or the person the licence is sold to: what the owner calls them. */
  name: string;
  /** Who to talk to ("anh Nam"). */
  contact?: string;
  phone?: string;
  email?: string;
  note?: string;
  sourceId?: string;
  createdAt: number;
  updatedAt: number;
}

export type LicenseKind = "LICENSE" | "WARRANTY" | "MAINTENANCE" | "SUBSCRIPTION";
export const LICENSE_KINDS: LicenseKind[] = ["LICENSE", "WARRANTY", "MAINTENANCE", "SUBSCRIPTION"];
export const KIND_LABEL: Record<LicenseKind, string> = {
  LICENSE: "License",
  WARRANTY: "Bảo hành",
  MAINTENANCE: "Bảo trì",
  SUBSCRIPTION: "Thuê bao",
};

/** Where a renewal stands, in the order it normally goes. */
export type Stage = "ACTIVE" | "ASKED" | "QUOTED" | "CONTRACTING" | "RENEWED" | "LOST";
export const STAGES: Stage[] = ["ACTIVE", "ASKED", "QUOTED", "CONTRACTING", "RENEWED", "LOST"];
export const STAGE_LABEL: Record<Stage, string> = {
  ACTIVE: "Chưa liên hệ",
  ASKED: "Đã hỏi khách",
  QUOTED: "Đã báo giá",
  CONTRACTING: "Đang làm hợp đồng",
  RENEWED: "Đã gia hạn",
  LOST: "Không gia hạn",
};
/** The steps still being worked, in order. */
export const OPEN_STAGES: Stage[] = ["ACTIVE", "ASKED", "QUOTED", "CONTRACTING"];
export const stageRank = (s: Stage): number => OPEN_STAGES.indexOf(s);
export const isClosed = (s: Stage): boolean => s === "RENEWED" || s === "LOST";

/** The single button shown for each open stage: what the owner just did. */
export const NEXT_STEP: Record<Exclude<Stage, "RENEWED" | "LOST">, { to: Stage; label: string }> = {
  ACTIVE: { to: "ASKED", label: "Đã hỏi khách" },
  ASKED: { to: "QUOTED", label: "Đã gửi báo giá" },
  QUOTED: { to: "CONTRACTING", label: "Đã gửi hợp đồng" },
  CONTRACTING: { to: "RENEWED", label: "Khách đã ký, gia hạn xong" },
};

export const LOST_REASONS = ["Giá cao", "Chuyển sang đối thủ", "Không còn nhu cầu", "Khác"] as const;

export interface License {
  id: string;
  customerId: string;
  status: RecordStatus;
  product: string;
  kind: LicenseKind;
  startDate?: ISODate;
  /** The last day it is valid. */
  endDate: ISODate;
  termMonths?: number;
  /** Days of notice the contract needs; reminders count back from (end date − notice). */
  noticeDays?: number;
  /** Whole VND. */
  value?: number;
  contractNo?: string;
  note?: string;
  stage: Stage;
  /** When the stage last changed (epoch ms); drives "no news for N days". */
  stageAt: number;
  /** Hide reminders until this date. */
  snoozeUntil?: ISODate;
  lostReason?: string;
  renewedFromId?: string;
  renewedToId?: string;
  sourceId?: string;
  createdAt: number;
  updatedAt: number;
}

/**
 * A change to an existing licence that the AI proposes from a capture ("ABC agreed, contract on
 * Thursday"). The owner applies or discards it.
 */
export interface Proposal {
  id: string;
  captureId: string;
  licenseId: string;
  customerId: string;
  patch: { stage?: Stage; endDate?: ISODate; value?: number; note?: string; lostReason?: string };
  summary: string;
  createdAt: number;
}

// ---------------------------------------------------------------- settings

/** Reminder rhythm and import column names. Everything has a default, so nothing has to be set up. */
export interface Prefs {
  /** Days before the deadline for: ask, quote, contract, red alert (strictly descending). */
  milestones: number[];
  /** Same four steps for short terms. */
  shortMilestones: number[];
  /** Terms of this many months or fewer use `shortMilestones`. */
  shortTermMonths: number;
  /** Remind to follow up when asked/quoted this many days ago with no news. */
  staleDays: number;
  /** A customer nobody touched for this many days is "long quiet". */
  quietDays: number;
  /** Excel column titles by field; a title may list synonyms separated by commas. */
  importColumns: Record<ImportField, string>;
}

export type ImportField =
  | "customer" | "contact" | "phone" | "email" | "product" | "kind"
  | "startDate" | "endDate" | "termMonths" | "value" | "contractNo" | "note";

export const IMPORT_FIELDS: ImportField[] = [
  "customer", "contact", "phone", "email", "product", "kind", "startDate", "endDate", "termMonths", "value", "contractNo", "note",
];

/** The titles of the template file; also what a header is matched against first. */
export const DEFAULT_IMPORT_COLUMNS: Record<ImportField, string> = {
  customer: "Khách hàng",
  contact: "Người liên hệ",
  phone: "Số điện thoại",
  email: "Email",
  product: "Sản phẩm",
  kind: "Loại",
  startDate: "Ngày bắt đầu",
  endDate: "Ngày hết hạn",
  termMonths: "Thời hạn (tháng)",
  value: "Giá trị",
  contractNo: "Số hợp đồng",
  note: "Ghi chú",
};

export const DEFAULT_PREFS: Prefs = {
  milestones: [90, 60, 30, 14],
  shortMilestones: [60, 30, 14, 7],
  shortTermMonths: 6,
  staleDays: 5,
  quietDays: 60,
  importColumns: DEFAULT_IMPORT_COLUMNS,
};

/** Titles of the four reminder steps, in the order of `Prefs.milestones`. */
export const MILESTONE_LABELS = ["Hỏi khách có gia hạn không", "Gửi báo giá", "Chốt hợp đồng", "Cảnh báo đỏ"] as const;

const isCount = (n: unknown, max: number): n is number => Number.isInteger(n) && (n as number) >= 1 && (n as number) <= max;

/** Four whole days, each larger than the next (e.g. 90, 60, 30, 14). */
export function validMilestones(v: unknown): v is number[] {
  return Array.isArray(v) && v.length === 4 && v.every((n) => isCount(n, 365)) && v.every((n, i) => i === 0 || (v[i - 1] as number) > (n as number));
}

/** Fills in defaults and drops anything invalid, so a half-saved or hand-edited document never breaks the app. */
export function normalizePrefs(raw: Partial<Prefs> | null | undefined): Prefs {
  const r = raw ?? {};
  const cols = { ...DEFAULT_IMPORT_COLUMNS };
  for (const f of IMPORT_FIELDS) {
    const v = r.importColumns?.[f];
    if (typeof v === "string" && v.trim()) cols[f] = v.trim().slice(0, 200);
  }
  return {
    milestones: validMilestones(r.milestones) ? r.milestones : DEFAULT_PREFS.milestones,
    shortMilestones: validMilestones(r.shortMilestones) ? r.shortMilestones : DEFAULT_PREFS.shortMilestones,
    shortTermMonths: isCount(r.shortTermMonths, 60) ? r.shortTermMonths : DEFAULT_PREFS.shortTermMonths,
    staleDays: isCount(r.staleDays, 60) ? r.staleDays : DEFAULT_PREFS.staleDays,
    quietDays: isCount(r.quietDays, 365) ? r.quietDays : DEFAULT_PREFS.quietDays,
    importColumns: cols,
  };
}

/** What a customer is called in lists: the company, with the contact person when there is one. */
export const customerLabel = (c: Pick<Customer, "name" | "contact">): string => (c.contact ? `${c.name} · ${c.contact}` : c.name);
