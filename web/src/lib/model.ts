// Data types shared with the server live in /shared; this file adds the labels the screens use.
export * from "../../../shared/model";
import type { ItemType, Recurrence } from "../../../shared/model";

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
