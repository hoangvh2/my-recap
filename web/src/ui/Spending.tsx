import { useMemo } from "preact/hooks";
import { formatVnd } from "../lib/money";
import type { Item } from "../lib/model";
import { compact, spendingReport, type SpendingReport } from "../lib/spending";

const W = 320;
const H = 120;

/** This month's running total (solid) against last month's (dashed), same scale, day by day. */
function PaceChart({ r }: { r: SpendingReport }) {
  const max = Math.max(1, ...r.cumulative, ...r.previousCumulative);
  const x = (day: number) => 8 + ((W - 16) * day) / Math.max(1, Math.max(r.daysInMonth, r.previousCumulative.length) - 1);
  const y = (v: number) => H - 8 - ((H - 20) * v) / max;
  const line = (xs: number[]) => xs.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(" ");
  const last = r.cumulative.length - 1;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} class="chart" role="img" aria-label={`Chi tiêu cộng dồn tháng này ${formatVnd(r.total)}, tháng trước cả tháng ${formatVnd(r.previousCumulative.at(-1) ?? 0)}`}>
      <path d={line(r.previousCumulative)} class="prev" />
      {r.cumulative.length > 0 && <path d={line(r.cumulative)} class="cur" />}
      {last >= 0 && <circle cx={x(last)} cy={y(r.cumulative[last])} r="4" class="dot-now" />}
    </svg>
  );
}

/** Six months side by side; the selected month is the strong bar. */
function Trend({ r }: { r: SpendingReport }) {
  const max = Math.max(1, ...r.months.map((m) => m.total));
  return (
    <ul class="trend" aria-label="Chi tiêu 6 tháng gần nhất">
      {r.months.map((m, i) => (
        <li key={m.key} class={i === r.months.length - 1 ? "on" : ""}>
          <span class="val">{m.total > 0 ? compact(m.total).replace(" triệu", "tr").replace(" nghìn", "k") : ""}</span>
          <span class="col"><i style={{ height: `${m.total > 0 ? Math.max(4, Math.round((m.total / max) * 100)) : 0}%` }} /></span>
          <span class="lbl">T{Number(m.key.slice(5))}</span>
        </li>
      ))}
    </ul>
  );
}

/**
 * The spending dashboard for one month: pace against last month, the month-end forecast, a six-month trend and
 * what looks off. Everything is computed on this device from the saved expenses (no AI, no cost).
 */
export function SpendingPanel({ items, month, onOpen }: { items: Item[]; month: string; onOpen: (i: Item) => void }) {
  const r = useMemo(() => spendingReport(items, month, Date.now()), [items, month]);
  if (r.total === 0 && r.months.every((m) => m.total === 0)) return null;
  const diff = r.previousToDate !== null ? r.total - r.previousToDate : null;
  return (
    <section class="spending" aria-label="Thống kê chi tiêu">
      <div class="stat-row">
        {r.forecast !== null && <p><span class="muted">Dự báo cuối tháng</span><strong>{formatVnd(r.forecast)}</strong></p>}
        {diff !== null && (
          <p>
            <span class="muted">So với tháng trước cùng kỳ</span>
            <strong class={diff > 0 ? "up" : "down"}>{diff === 0 ? "Bằng nhau" : `${diff > 0 ? "+" : "−"}${compact(Math.abs(diff))}`}</strong>
          </p>
        )}
      </div>
      {r.cumulative.length > 0 && (
        <>
          <PaceChart r={r} />
          <p class="legend"><span class="key cur" /> Tháng này <span class="key prev" /> Tháng trước</p>
        </>
      )}
      <Trend r={r} />
      {r.alerts.length > 0 && (
        <ul class="alerts" aria-label="Điều đáng chú ý">
          {r.alerts.map((a) => {
            const item = a.itemId ? items.find((i) => i.id === a.itemId) : undefined;
            return (
              <li key={a.kind + a.message}>
                {item ? <button type="button" onClick={() => onOpen(item)}>{a.message}</button> : a.message}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
