import { buildItemsCalendar, escapeText, eventForItem, eventsForLicense, fold, renderCalendar, type CalEvent } from "../../../shared/ics";
import type { Ctx } from "../../../shared/renewals";
import type { Graph } from "./graph";
import type { Item, License } from "./model";

export { escapeText, fold, eventForItem, eventsForLicense, renderCalendar, type CalEvent };

/** A calendar file with every dated task/appointment given. Items without a date are skipped. */
export function buildIcs(items: Item[], stamp = Date.now(), names?: ReadonlyMap<string, string>): string {
  return buildItemsCalendar(items, { stamp, names });
}

/** The reminders still ahead for one licence, as a calendar file. Empty text when there are none. */
export function buildLicenseCalendar(l: License, customerName: string, ctx: Ctx, stamp = Date.now()): string {
  const events = eventsForLicense(l, customerName, ctx);
  return events.length > 0 ? renderCalendar(events, { stamp, name: `Gia hạn · ${customerName}` }) : "";
}

/** Every dated task and appointment plus every renewal reminder ahead: a one-off copy of what the subscription shows. */
export function buildEverythingCalendar(g: Graph, ctx: Ctx, stamp = Date.now()): string {
  const events: CalEvent[] = [];
  for (const i of g.items) {
    if (i.status !== "OPEN" || i.type === "EXPENSE") continue;
    const e = eventForItem(i, i.customerId ? g.customerById.get(i.customerId)?.name : undefined);
    if (e) events.push(e);
  }
  for (const l of g.licenses) if (l.status === "OPEN") events.push(...eventsForLicense(l, g.customerName(l.customerId), ctx));
  return events.length > 0 ? renderCalendar(events, { stamp, name: "Thư ký · Việc & Gia hạn" }) : "";
}
