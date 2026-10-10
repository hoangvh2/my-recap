import type { Customer, Item, License } from "./model";

/**
 * Everything the screens need to hop between a customer, their licences and the work around them.
 * Built once per data change; every screen reads links from here so a chip can never point at nothing.
 */
export interface Graph {
  customers: Customer[];
  licenses: License[];
  items: Item[];
  customerById: ReadonlyMap<string, Customer>;
  licenseById: ReadonlyMap<string, License>;
  licensesOf(customerId: string): License[];
  itemsOfCustomer(customerId: string): Item[];
  itemsOfLicense(licenseId: string): Item[];
  /** Name for a chip; "Khách đã xoá" if the record is gone. */
  customerName(id: string | undefined): string;
}

const push = <T>(m: Map<string, T[]>, k: string, v: T) => {
  const a = m.get(k);
  if (a) a.push(v);
  else m.set(k, [v]);
};

export function buildGraph(customers: Customer[], licenses: License[], items: Item[]): Graph {
  const customerById = new Map(customers.map((c) => [c.id, c]));
  const licenseById = new Map(licenses.map((l) => [l.id, l]));
  const byCustomer = new Map<string, License[]>();
  for (const l of licenses) push(byCustomer, l.customerId, l);
  for (const list of byCustomer.values()) list.sort((a, b) => a.endDate.localeCompare(b.endDate));
  const itemsByCustomer = new Map<string, Item[]>();
  const itemsByLicense = new Map<string, Item[]>();
  for (const i of items) {
    if (i.customerId) push(itemsByCustomer, i.customerId, i);
    if (i.licenseId) push(itemsByLicense, i.licenseId, i);
  }
  return {
    customers, licenses, items, customerById, licenseById,
    licensesOf: (id) => byCustomer.get(id) ?? [],
    itemsOfCustomer: (id) => itemsByCustomer.get(id) ?? [],
    itemsOfLicense: (id) => itemsByLicense.get(id) ?? [],
    customerName: (id) => (id ? customerById.get(id)?.name ?? "Khách đã xoá" : ""),
  };
}

/** What deleting a customer takes with it: its licences and the work attached to it or to them. */
export function customerCascade(g: Graph, customerId: string): { licenses: License[]; items: Item[] } {
  const licenses = g.licensesOf(customerId);
  const ids = new Set(g.itemsOfCustomer(customerId).map((i) => i.id));
  for (const l of licenses) for (const i of g.itemsOfLicense(l.id)) ids.add(i.id);
  return { licenses, items: g.items.filter((i) => ids.has(i.id)) };
}
