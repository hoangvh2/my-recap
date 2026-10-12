import type { Item } from "./model";
import { startOfDay } from "./schedule";

/** Morning review of overdue tasks: one tap moves a task to a new day (or off the calendar) so the list stays clean. */
export type Move = "TODAY" | "TOMORROW" | "NEXT_WEEK" | "SOMEDAY";
export const MOVES: Move[] = ["TODAY", "TOMORROW", "NEXT_WEEK", "SOMEDAY"];
export const MOVE_LABEL: Record<Move, string> = { TODAY: "Hôm nay", TOMORROW: "Mai", NEXT_WEEK: "Tuần sau", SOMEDAY: "Để sau" };

/** Open tasks past their due time or day, oldest first. Appointments are not triaged: a missed one is over. */
export function overdueTasks(items: Item[], now: number): Item[] {
  const today0 = startOfDay(now);
  return items
    .filter((i) => i.type === "TASK" && i.status === "OPEN" && i.whenAt !== undefined && (i.allDay ? i.whenAt < today0 : i.whenAt < now))
    .sort((a, b) => a.whenAt! - b.whenAt! || a.createdAt - b.createdAt);
}

const dayAt = (base: Date, offset: number, h = 0, m = 0) => new Date(base.getFullYear(), base.getMonth(), base.getDate() + offset, h, m).getTime();

/**
 * The task after [move]. The time of day is kept; a timed task moved to today whose time has already
 * passed becomes an all-day task today, so it is not overdue again the moment it is moved. "Để sau"
 * removes the due date (the task stays open under "Chưa có hạn").
 */
export function applyMove(item: Item, move: Move, now: number): Item {
  if (move === "SOMEDAY") {
    const { whenAt: _gone, ...rest } = item;
    return { ...rest, allDay: false };
  }
  const today = new Date(now);
  const offset = move === "TODAY" ? 0 : move === "TOMORROW" ? 1 : ((8 - (today.getDay() || 7)) % 7) || 7; // the coming Monday
  const timed = item.whenAt !== undefined && !item.allDay ? new Date(item.whenAt) : null;
  if (!timed) return { ...item, whenAt: dayAt(today, offset), allDay: true };
  const at = dayAt(today, offset, timed.getHours(), timed.getMinutes());
  return at < now ? { ...item, whenAt: dayAt(today, offset), allDay: true } : { ...item, whenAt: at, allDay: false };
}
