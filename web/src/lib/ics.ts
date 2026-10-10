import { buildItemsCalendar, escapeText, fold } from "../../../shared/ics";
import type { Item } from "./model";

export { escapeText, fold };
export { eventForItem, eventsForLicense, renderCalendar, type CalEvent } from "../../../shared/ics";

/** A calendar file with every dated task/appointment given. Items without a date are skipped. */
export function buildIcs(items: Item[], stamp = Date.now(), names?: ReadonlyMap<string, string>): string {
  return buildItemsCalendar(items, { stamp, names });
}
