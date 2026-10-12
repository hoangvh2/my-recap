import { useMemo } from "preact/hooks";
import { saveItem } from "../data";
import { whenLabel } from "../lib/format";
import type { Item } from "../lib/model";
import { MOVES, MOVE_LABEL, applyMove, overdueTasks, type Move } from "../lib/triage";
import { useUid } from "./hooks";
import { showToast } from "./toast";

/**
 * "Rà soát": the oldest overdue task with one tap to move it to today, tomorrow, next Monday or off the
 * calendar, so a long overdue list is cleared one task at a time instead of one by one in the editor.
 */
export function TriageCard({ items, now }: { items: Item[]; now: number }) {
  const uid = useUid();
  const overdue = useMemo(() => overdueTasks(items.filter((i) => i.status !== "DRAFT"), now), [items, now]);
  const first = overdue[0];
  if (!first) return null;
  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
  const move = (m: Move) => {
    saveItem(uid, applyMove(first, m, Date.now())).catch(fail);
    showToast(`${first.title}: ${MOVE_LABEL[m]}`, { label: "Hoàn tác", run: () => saveItem(uid, first).catch(fail) });
  };
  return (
    <div class="triage" role="group" aria-label="Rà soát việc quá hạn">
      <p class="muted">Rà soát · còn {overdue.length} việc</p>
      <p class="title">{first.title}</p>
      <p class="meta">Hạn {whenLabel(first, now)}</p>
      <div class="chips-inline">
        {MOVES.map((m) => <button key={m} type="button" class="pill" onClick={() => move(m)}>{MOVE_LABEL[m]}</button>)}
      </div>
    </div>
  );
}
