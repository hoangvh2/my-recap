import { isClosed } from "../../../shared/model";
import type { Graph } from "./graph";

/** What the capture function is told about existing customers and licences. Mirrors functions/src/sales.ts. */
export interface CaptureContext {
  customers: { id: string; name: string; contact?: string }[];
  licenses: { id: string; customerId: string; product: string; endDate: string; stage: string }[];
  focus?: { customerId?: string; licenseId?: string };
}

export const CONTEXT_LIMITS = { customers: 300, licenses: 500 } as const;

/**
 * The customers and licences the AI may refer to. Licences still open come first (soonest end date),
 * and the customers they belong to; the customer or licence on screen is always included.
 */
export function buildContext(g: Graph, focus?: { customerId?: string; licenseId?: string }): CaptureContext {
  const open = g.licenses
    .filter((l) => l.status === "OPEN" && !isClosed(l.stage))
    .sort((a, b) => a.endDate.localeCompare(b.endDate));
  const focusLicense = focus?.licenseId ? g.licenseById.get(focus.licenseId) : undefined;
  const licenses = [...(focusLicense && !open.includes(focusLicense) ? [focusLicense] : []), ...open].slice(0, CONTEXT_LIMITS.licenses);

  const wanted = new Set<string>();
  if (focus?.customerId) wanted.add(focus.customerId);
  if (focusLicense) wanted.add(focusLicense.customerId);
  for (const l of licenses) wanted.add(l.customerId);
  const ordered = [
    ...g.customers.filter((c) => wanted.has(c.id)),
    ...g.customers.filter((c) => !wanted.has(c.id)).sort((a, b) => b.updatedAt - a.updatedAt),
  ].filter((c) => c.status === "OPEN" && c.name.trim());
  const customers = ordered.slice(0, CONTEXT_LIMITS.customers);
  const known = new Set(customers.map((c) => c.id));

  return {
    customers: customers.map((c) => ({ id: c.id, name: c.name.slice(0, 120), ...(c.contact ? { contact: c.contact.slice(0, 120) } : {}) })),
    licenses: licenses
      .filter((l) => l.status === "OPEN" && known.has(l.customerId))
      .map((l) => ({ id: l.id, customerId: l.customerId, product: l.product.slice(0, 120), endDate: l.endDate, stage: l.stage })),
    ...(focus && (focus.customerId || focus.licenseId)
      ? { focus: { ...(focus.customerId && known.has(focus.customerId) ? { customerId: focus.customerId } : {}), ...(focus.licenseId ? { licenseId: focus.licenseId } : {}) } }
      : {}),
  };
}
