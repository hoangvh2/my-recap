import { useMemo } from "preact/hooks";
import { captures } from "../data";
import type { Item } from "../lib/model";
import { useStore } from "../lib/store";
import { ItemRow } from "./ItemRow";

/** Items the AI proposed from a capture. Nothing counts until the owner saves it. */
export function Drafts(props: {
  drafts: Item[];
  onOpen: (i: Item) => void;
  onSave: (items: Item[]) => void;
  onDiscard: (items: Item[]) => void;
}) {
  const caps = useStore(captures);
  const groups = useMemo(() => {
    const m = new Map<string, Item[]>();
    for (const d of props.drafts) m.set(d.sourceId ?? "", [...(m.get(d.sourceId ?? "") ?? []), d]);
    return [...m];
  }, [props.drafts]);
  if (groups.length === 0) return null;
  return (
    <section class="block drafts">
      <h3>Chờ bạn xem lại</h3>
      {groups.map(([source, list]) => {
        const cap = caps.find((c) => c.id === source);
        return (
          <div class="draft-card" key={source || "none"}>
            {cap && (
              <details>
                <summary>“{cap.transcript.length > 90 ? `${cap.transcript.slice(0, 90)}…` : cap.transcript}”</summary>
                <p class="transcript">{cap.transcript}</p>
              </details>
            )}
            <ul class="list">
              {list.map((d) => <ItemRow key={d.id} item={d} onOpen={props.onOpen} />)}
            </ul>
            <div class="row gap">
              <button class="btn ghost" onClick={() => props.onDiscard(list)}>Bỏ</button>
              <button class="btn solid grow" onClick={() => props.onSave(list)}>Lưu {list.length > 1 ? `tất cả (${list.length})` : ""}</button>
            </div>
          </div>
        );
      })}
    </section>
  );
}
