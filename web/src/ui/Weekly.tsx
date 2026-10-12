import { useMemo, useState } from "preact/hooks";
import { formatVnd } from "../lib/money";
import type { Item } from "../lib/model";
import { startOfDay } from "../lib/schedule";
import { captures } from "../data";
import { useStore } from "../lib/store";
import { addWeeks, buildWeekly, weekStartOf } from "../lib/weekly";
import { compact } from "../lib/spending";
import { Icon } from "./icons";

const dm = (t: number) => `${new Date(t).getDate()}/${new Date(t).getMonth() + 1}`;

function Bars({ values, label, max, on }: { values: number[]; label: (i: number) => string; max: number; on?: number }) {
  return (
    <ul class="trend week" aria-hidden="true">
      {values.map((v, i) => (
        <li key={i} class={i === on ? "on" : ""}>
          <span class="val">{v > 0 ? v : ""}</span>
          <span class="col"><i style={{ height: `${v > 0 ? Math.max(6, Math.round((v / max) * 100)) : 0}%` }} /></span>
          <span class="lbl">{label(i)}</span>
        </li>
      ))}
    </ul>
  );
}

const arrow = (now: number, before: number) => (now === before ? "bằng tuần trước" : now > before ? `nhiều hơn tuần trước ${now - before}` : `ít hơn tuần trước ${before - now}`);

/** "Tuần": what got done, what was spent, what is stuck and how heavy next week is. Computed on this device. */
export function Weekly({ items }: { items: Item[] }) {
  const recordings = useStore(captures);
  const thisWeek = weekStartOf(Date.now());
  const [start, setStart] = useState(thisWeek);
  const r = useMemo(() => buildWeekly(items, recordings.map((c) => c.createdAt), start, Date.now()), [items, recordings, start]);
  const today = startOfDay(Date.now());
  const todayIdx = start === thisWeek ? Math.round((today - start) / 86_400_000) : undefined;
  const label = start === thisWeek ? "Tuần này" : start === addWeeks(thisWeek, -1) ? "Tuần trước" : "Tuần";
  return (
    <>
      <div class="month">
        <button class="icon-btn" aria-label="Tuần trước" onClick={() => setStart(addWeeks(start, -1))}><Icon name="chevronLeft" /></button>
        <div>
          <div class="muted">{label}</div>
          <div class="big small">{dm(start)} – {dm(addWeeks(start, 1) - 86_400_000)}</div>
        </div>
        <button class="icon-btn" aria-label="Tuần sau" disabled={start >= thisWeek} onClick={() => setStart(addWeeks(start, 1))}><Icon name="chevronRight" /></button>
      </div>

      <section class="spending" aria-label="Việc đã xong">
        <div class="stat-row">
          <p><span class="muted">Việc đã xong</span><strong>{r.done}</strong></p>
          <p><span class="muted">So với tuần trước</span><strong>{r.done === r.previousDone ? "Bằng" : r.done > r.previousDone ? `+${r.done - r.previousDone}` : `−${r.previousDone - r.done}`}</strong></p>
        </div>
        <Bars values={r.doneByDay} max={Math.max(1, ...r.doneByDay)} label={(i) => (i === 6 ? "CN" : `T${i + 2}`)} on={todayIdx} />
        <p class="muted small">{r.averageDone !== null ? `Trung bình 4 tuần trước: ${Math.round(r.averageDone * 10) / 10} việc/tuần` : arrow(r.done, r.previousDone)}</p>
      </section>

      <section class="spending" aria-label="Chi tiêu trong tuần">
        <div class="stat-row">
          <p><span class="muted">Chi tiêu</span><strong>{formatVnd(r.spent)}</strong></p>
          <p><span class="muted">Tuần trước</span><strong>{formatVnd(r.previousSpent)}</strong></p>
        </div>
        {r.topCategory && <p class="muted small">Nhiều nhất: {r.topCategory.name} ({compact(r.topCategory.amount)})</p>}
      </section>

      <section class="spending" aria-label="Tuần sau">
        <div class="stat-row">
          <p><span class="muted">Tuần sau có</span><strong>{r.nextWeekTotal} việc/lịch</strong></p>
          <p><span class="muted">Ghi nhanh bằng giọng nói</span><strong>{r.recordings} lần</strong></p>
        </div>
        <Bars values={r.nextWeekByDay} max={Math.max(1, ...r.nextWeekByDay)} label={(i) => (i === 6 ? "CN" : `T${i + 2}`)} />
        <p class="muted small">Quá hạn: {r.overdue} việc{r.stale > 0 ? ` (${r.stale} việc quá hạn hơn một tuần)` : ""}</p>
      </section>

      {r.insights.length > 0 && (
        <ul class="alerts" aria-label="Điều đáng chú ý">
          {r.insights.map((i) => <li key={i.kind}>{i.message}</li>)}
        </ul>
      )}
      {r.insights.length === 0 && <p class="empty">Tuần này chưa có gì bất thường.</p>}
    </>
  );
}
