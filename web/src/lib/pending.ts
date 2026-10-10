import type { Proposal } from "./model";
import type { Graph } from "./graph";

/** How many captures are waiting for the owner to look at them. */
export function pendingCount(g: Graph, proposals: readonly Proposal[]): number {
  const ids = new Set<string>();
  for (const i of g.items) if (i.status === "DRAFT") ids.add(i.sourceId ?? i.id);
  for (const c of g.customers) if (c.status === "DRAFT") ids.add(c.sourceId ?? c.id);
  for (const l of g.licenses) if (l.status === "DRAFT") ids.add(l.sourceId ?? l.id);
  for (const p of proposals) ids.add(p.captureId);
  return ids.size;
}
