import { useEffect, useMemo, useState } from "preact/hooks";
import { isoDateIn, type ISODate } from "../../../shared/dates";
import type { Ctx } from "../../../shared/renewals";
import { customers, items, licenses, prefs, proposals } from "../data";
import { buildGraph, type Graph } from "../lib/graph";
import { useStore } from "../lib/store";
import { session } from "../session";

/** Today's date in this device's zone; moves on by itself if the app is left open past midnight. */
export function useToday(): ISODate {
  const [today, setToday] = useState(() => isoDateIn(Date.now()));
  useEffect(() => {
    const tick = () => setToday(isoDateIn(Date.now()));
    const t = setInterval(tick, 60_000);
    document.addEventListener("visibilitychange", tick);
    return () => {
      clearInterval(t);
      document.removeEventListener("visibilitychange", tick);
    };
  }, []);
  return today;
}

/** Every record and the links between them. */
export function useGraph(): Graph {
  const c = useStore(customers);
  const l = useStore(licenses);
  const i = useStore(items);
  return useMemo(() => buildGraph(c, l, i), [c, l, i]);
}

/** What the renewal playbook needs to know: today and the owner's reminder rhythm. */
export function useCtx(): Ctx {
  const p = useStore(prefs);
  const today = useToday();
  return useMemo(() => ({ today, prefs: p }), [today, p]);
}

export function usePending() {
  return useStore(proposals);
}

export function useUid(): string {
  const s = useStore(session);
  return s.status === "in" ? s.uid : "";
}
