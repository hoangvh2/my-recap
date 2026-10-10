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
}

export interface Capture {
  id: string;
  kind: "voice" | "text";
  transcript: string;
  createdAt: number;
  itemCount: number;
}

export const TYPE_LABEL: Record<ItemType, string> = { TASK: "Việc", EVENT: "Lịch hẹn", EXPENSE: "Chi tiêu", NOTE: "Ghi chú" };
export const TYPES: ItemType[] = ["TASK", "EVENT", "EXPENSE", "NOTE"];

export const RECURRENCE_LABEL: Record<Recurrence, string> = {
  DAILY: "Hằng ngày",
  WEEKDAYS: "Ngày làm việc",
  WEEKLY: "Hằng tuần",
  MONTHLY: "Hằng tháng",
};
export const RECURRENCES: Recurrence[] = ["DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY"];

export const EXPENSE_CATEGORIES = [
  "Ăn uống", "Di chuyển", "Mua sắm", "Hoá đơn", "Sức khoẻ", "Giải trí", "Giáo dục", "Gia đình", "Công việc", "Khác",
];

/** Same limits as the Firestore rules, so the form can say what is wrong before the server does. */
export const MAX = { title: 120, details: 2_000, place: 120, person: 120, category: 40 } as const;
