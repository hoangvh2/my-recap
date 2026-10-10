import { fold } from "../../../shared/text";
import type { Customer, License } from "./model";
import type { ImportPlan } from "./table";

const cut = (s: string | undefined, n: number) => (s ? s.trim().slice(0, n) : "");

/**
 * The records an import creates. Only rows marked ok are used. Customers are matched by name exactly
 * the way the preview did (so what was promised is what is saved) and created once.
 */
export function recordsFromPlan(
  plan: ImportPlan,
  existing: readonly Pick<Customer, "id" | "name">[],
  now: number,
  newId: () => string,
): { customers: Customer[]; licenses: License[] } {
  const byName = new Map(existing.map((c) => [fold(c.name), c.id]));
  const customers: Customer[] = [];
  const licenses: License[] = [];
  for (const r of plan.rows) {
    if (r.status !== "ok" || !r.endDate) continue;
    const key = fold(r.customer);
    let customerId = byName.get(key);
    if (!customerId) {
      const c: Customer = { id: newId(), status: "OPEN", name: cut(r.customer, 120), createdAt: now, updatedAt: now };
      if (cut(r.contact, 120)) c.contact = cut(r.contact, 120);
      if (cut(r.phone, 40)) c.phone = cut(r.phone, 40);
      if (cut(r.email, 120)) c.email = cut(r.email, 120);
      customers.push(c);
      byName.set(key, c.id);
      customerId = c.id;
    }
    const l: License = {
      id: newId(), customerId, status: "OPEN", product: cut(r.product, 120), kind: r.kind, endDate: r.endDate,
      stage: "ACTIVE", stageAt: now, createdAt: now, updatedAt: now,
    };
    if (r.startDate) l.startDate = r.startDate;
    if (r.termMonths) l.termMonths = r.termMonths;
    if (r.value) l.value = r.value;
    if (cut(r.contractNo, 60)) l.contractNo = cut(r.contractNo, 60);
    if (cut(r.note, 2000)) l.note = cut(r.note, 2000);
    licenses.push(l);
  }
  return { customers, licenses };
}
