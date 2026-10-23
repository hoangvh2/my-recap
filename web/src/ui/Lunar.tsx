import { useEffect, useMemo, useState } from "preact/hooks";
import { addDays, type ISODate } from "../../../shared/dates";
import { bannerFor, bannerKey, holidayOn, type HolidayOverride } from "../../../shared/holidays";
import { lunarMonth, lunarToSolar, solarToLunar } from "../../../shared/lunar";
import { LEAD_CHOICES, holidayData, lunarPrefs, refreshHolidayData, setLunarPrefs } from "../lib/lunarCfg";
import { dm, holidayLine, longDate, lunarAt, lunarChip, lunarFull, monthlyMark } from "../lib/lunarText";
import { dayLabel, toDateInput } from "../lib/format";
import type { Item } from "../lib/model";
import { useStore } from "../lib/store";
import { Icon } from "./icons";
import { ItemRow } from "./ItemRow";
import { showToast } from "./toast";

const pad = (n: number) => String(n).padStart(2, "0");
const isoOfYmd = (y: number, m: number, d: number): ISODate => `${String(y).padStart(4, "0")}-${pad(m)}-${pad(d)}`;

/** The data every lunar/holiday view reads. */
export function useLunarCfg() {
  const prefs = useStore(lunarPrefs);
  const data = useStore(holidayData);
  return { prefs, overrides: data.overrides as readonly HolidayOverride[], updated: data.updated };
}

// ---------------------------------------------------------------- banner on Today

/** "Sắp nghỉ" for a state holiday, and (when chosen) the day-before note to prepare offerings on mùng 1 and rằm. */
export function HolidayBanner({ now }: { now: number }) {
  const { prefs, overrides } = useLunarCfg();
  const today = toDateInput(now);
  const ahead = prefs.banner && prefs.holidays ? bannerFor(today, prefs.lead, prefs.dismissed, overrides) : null;
  const tomorrow = prefs.monthly ? monthlyMark(solarToLunar(addDays(today, 1))) : null;
  if (!ahead && !tomorrow) return null;
  const hide = () => {
    if (!ahead) return;
    const key = bannerKey(ahead.holiday);
    setLunarPrefs({ dismissed: [...prefs.dismissed, key] });
    showToast("Đã ẩn thông báo kỳ nghỉ này", { label: "Hoàn tác", run: () => setLunarPrefs({ dismissed: lunarPrefs.get().dismissed.filter((k) => k !== key) }) });
  };
  return (
    <div class="notices">
      {ahead && (
        <div class="notice holiday" role="status">
          <Icon name="calendar" size={18} />
          <span class="grow">
            <strong>{ahead.daysUntil < 0 ? "Đang nghỉ" : "Sắp nghỉ"} · {ahead.holiday.name}</strong>
            <br />
            {holidayLine(ahead.holiday, ahead.daysUntil)}
            {ahead.holiday.workdays.length > 0 && <> Làm bù: {ahead.holiday.workdays.map(longDate).join(", ")}.</>}
            <small class="status">
              {ahead.holiday.status === "announced" ? `Lịch nghỉ đã công bố${ahead.holiday.source ? `: ${ahead.holiday.source}` : ""}` : "Tính theo Bộ luật Lao động, chưa có thông báo riêng. Cơ quan, công ty có thể khác."}
            </small>
          </span>
          <button class="btn text" onClick={hide}>Ẩn</button>
        </div>
      )}
      {tomorrow && (
        <div class="notice holiday" role="status">
          <Icon name="clock" size={18} />
          <span class="grow">
            <strong>Mai là {tomorrow === "Rằm" ? "Rằm" : "Mùng 1"} {lunarFull(solarToLunar(addDays(today, 1))!).replace(/^(Rằm|Mùng 1) /, "")}</strong>
            <br />
            Chuẩn bị đồ cúng.
          </span>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- agenda day header

/** "· 10/3 Âm · Giỗ Tổ Hùng Vương" to add after a day's name, honouring the settings. */
export function dayExtras(t: number, prefs = lunarPrefs.get(), overrides: readonly HolidayOverride[] = holidayData.get().overrides): string {
  const iso = toDateInput(t);
  const parts: string[] = [];
  const l = prefs.show ? lunarAt(t) : null;
  if (l) parts.push(lunarChip(l));
  if (prefs.holidays) {
    const h = holidayOn(iso, overrides);
    if (h && h.kind !== "weekend") parts.push(h.holiday.name);
  }
  if (prefs.monthly) {
    const m = monthlyMark(lunarAt(t));
    if (m && !parts.some((p) => p.includes(m))) parts.push(m);
  }
  return parts.length ? ` · ${parts.join(" · ")}` : "";
}

// ---------------------------------------------------------------- month calendar

const DOW = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"];

/** Month grid: solar number, lunar day under it, holidays marked with text, the chosen day's items underneath. */
export function MonthCalendar({ items, onOpen, onToggle }: { items: Item[]; onOpen: (i: Item) => void; onToggle: (i: Item, done: boolean) => void }) {
  const { prefs, overrides } = useLunarCfg();
  const todayIso = toDateInput(Date.now());
  const [ym, setYm] = useState<[number, number]>([Number(todayIso.slice(0, 4)), Number(todayIso.slice(5, 7))]);
  const [sel, setSel] = useState<ISODate>(todayIso);
  const [y, m] = ym;
  const shift = (n: number) => {
    const t = y * 12 + (m - 1) + n;
    setYm([Math.floor(t / 12), (t % 12) + 1]);
  };
  const first = new Date(y, m - 1, 1);
  const lead = (first.getDay() + 6) % 7;
  const days = new Date(y, m, 0).getDate();

  // Open dated items by day.
  const byDay = useMemo(() => {
    const map = new Map<ISODate, Item[]>();
    for (const i of items) {
      if (i.status !== "OPEN" || i.whenAt === undefined || (i.type !== "TASK" && i.type !== "EVENT")) continue;
      const k = toDateInput(i.whenAt);
      map.set(k, [...(map.get(k) ?? []), i]);
    }
    return map;
  }, [items]);

  const cells: (ISODate | null)[] = [...Array(lead).fill(null), ...Array.from({ length: days }, (_, i) => isoOfYmd(y, m, i + 1))];
  const selItems = (byDay.get(sel) ?? []).sort((a, b) => (a.whenAt ?? 0) - (b.whenAt ?? 0));
  const selT = new Date(Number(sel.slice(0, 4)), Number(sel.slice(5, 7)) - 1, Number(sel.slice(8, 10))).getTime();
  const monthL = prefs.show ? solarToLunar(isoOfYmd(y, m, 15)) : null;

  return (
    <section class="block month-cal" aria-label={`Lịch tháng ${m}/${y}`}>
      <div class="month">
        <button class="icon-btn" aria-label="Tháng trước" onClick={() => shift(-1)}><Icon name="chevronLeft" /></button>
        <div>
          <div class="muted">Tháng {m}/{y}</div>
          {monthL && <div class="hint">{lunarMonthSpan(y, m)}</div>}
        </div>
        <button class="icon-btn" aria-label="Tháng sau" onClick={() => shift(1)}><Icon name="chevronRight" /></button>
      </div>
      <div class="cal-grid" role="grid">
        {DOW.map((d) => <span key={d} class="dow" role="columnheader">{d}</span>)}
        {cells.map((iso, i) => {
          if (!iso) return <i key={`b${i}`} />;
          const l = prefs.show || prefs.monthly ? solarToLunar(iso) : null;
          const h = prefs.holidays ? holidayOn(iso, overrides) : null;
          const mark = prefs.monthly ? monthlyMark(l) : l?.day === 1 ? "Mùng 1" : null;
          const sub = !l ? "" : l.day === 1 ? `1/${l.month}${l.leap ? "n" : ""}` : mark === "Rằm" ? "Rằm" : prefs.show ? String(l.day) : "";
          const n = byDay.get(iso)?.length ?? 0;
          const cls = ["cal-day", iso === todayIso ? "today" : "", iso === sel ? "sel" : "", h?.kind === "legal" ? "holiday" : h ? "off" : "", mark ? "lunar-mark" : ""].filter(Boolean).join(" ");
          return (
            <button key={iso} type="button" class={cls} role="gridcell" aria-selected={iso === sel} aria-label={dayAria(iso, l ? lunarFull(l) : "", h?.holiday.name, n)} onClick={() => setSel(iso)}>
              <span class="num">{Number(iso.slice(8, 10))}</span>
              {sub && <small>{sub}</small>}
              {h?.kind === "legal" ? <em>Lễ</em> : h?.kind === "extra" ? <em>Nghỉ</em> : null}
              {n > 0 && <i class="dotc" aria-hidden="true">{n}</i>}
            </button>
          );
        })}
      </div>
      <div class="cal-sel">
        <h3>{dayLabel(selT)} · {dm(sel)}{dayExtras(selT, prefs, overrides)}</h3>
        {selItems.length > 0 ? (
          <ul class="list">{selItems.map((i) => <ItemRow key={i.id} item={i} onOpen={onOpen} onToggle={onToggle} showDate={false} />)}</ul>
        ) : (
          <p class="empty">Không có việc hay lịch hẹn ngày này.</p>
        )}
      </div>
    </section>
  );
}

function dayAria(iso: ISODate, lunar: string, holiday: string | undefined, n: number): string {
  return [longDate(iso), lunar, holiday ? `nghỉ lễ ${holiday}` : "", n ? `${n} mục` : ""].filter(Boolean).join(", ");
}

/** "Âm: 24/2 đến 25/3": the lunar days the solar month spans. */
function lunarMonthSpan(y: number, m: number): string {
  const a = solarToLunar(isoOfYmd(y, m, 1));
  const b = solarToLunar(isoOfYmd(y, m, new Date(y, m, 0).getDate()));
  if (!a || !b) return "";
  return `Âm: ${a.day}/${a.month}${a.leap ? "n" : ""} đến ${b.day}/${b.month}${b.leap ? "n" : ""}`;
}

// ---------------------------------------------------------------- editor: pick the date on either calendar

export interface DateFieldProps {
  label: string;
  date: string;
  onDate: (iso: string) => void;
  /** Lunar mode is on when the item already repeats by the lunar calendar. */
  startLunar: boolean;
}

/** The date input with a Dương/Âm switch. In Âm mode the person types day, month and year of the lunar calendar. */
export function DateField({ label, date, onDate, startLunar }: DateFieldProps) {
  const { prefs } = useLunarCfg();
  const [lunar, setLunar] = useState(startLunar);
  const base = date ? solarToLunar(date) : null;
  const [ld, setLd] = useState(() => base ?? solarToLunar(toDateInput(Date.now()))!);
  // Keep the lunar fields in step when the solar date is changed from outside (a template, the switch).
  useEffect(() => {
    const l = date ? solarToLunar(date) : null;
    if (l) setLd(l);
  }, [date]);

  const m = lunarMonth(ld.year, ld.month, ld.leap);
  const hasLeap = lunarMonth(ld.year, ld.month, true) !== null;
  const change = (patch: Partial<typeof ld>) => {
    const n = { ...ld, ...patch };
    if (!lunarMonth(n.year, n.month, true)) n.leap = false;
    setLd(n);
    const iso = lunarToSolar(n.year, n.month, n.day, n.leap);
    if (iso) onDate(iso);
  };
  const invalid = lunar && m && ld.day > m.days;

  return (
    <div class="date-field">
      <div class="seg seg2 small" role="radiogroup" aria-label="Chọn ngày theo lịch">
        <button type="button" role="radio" aria-checked={!lunar} class={!lunar ? "on" : ""} onClick={() => setLunar(false)}>Dương</button>
        <button type="button" role="radio" aria-checked={lunar} class={lunar ? "on" : ""} onClick={() => setLunar(true)}>Âm</button>
      </div>
      {!lunar ? (
        <label>{label}<input type="date" value={date} onInput={(e) => onDate((e.target as HTMLInputElement).value)} /></label>
      ) : (
        <>
          <div class="lunar-inputs">
            <label>Ngày Âm
              <select value={ld.day} onChange={(e) => change({ day: Number((e.target as HTMLSelectElement).value) })}>
                {Array.from({ length: 30 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{d}</option>)}
              </select>
            </label>
            <label>Tháng
              <select value={ld.month} onChange={(e) => change({ month: Number((e.target as HTMLSelectElement).value) })}>
                {Array.from({ length: 12 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{d}</option>)}
              </select>
            </label>
            <label>Năm Âm
              <input inputMode="numeric" value={ld.year} onInput={(e) => { const y = Number((e.target as HTMLInputElement).value); if (y >= 1900 && y <= 2199) change({ year: y }); }} />
            </label>
          </div>
          {hasLeap && (
            <label class="check-row"><input type="checkbox" checked={ld.leap} onChange={(e) => change({ leap: (e.target as HTMLInputElement).checked })} /> Tháng nhuận</label>
          )}
        </>
      )}
      {invalid ? (
        <small class="alert-text" role="alert">Tháng {ld.month}{ld.leap ? " nhuận" : ""} năm {ld.year} Âm chỉ có {m!.days} ngày.</small>
      ) : date ? (
        <small class="lunar-eq">{lunar ? `= ${longDate(date)} dương` : prefs.show || startLunar ? `= ${lunarFull(solarToLunar(date) ?? ld)}` : ""}</small>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------- settings

export function LunarSettings() {
  const { prefs, updated } = useLunarCfg();
  const [busy, setBusy] = useState(false);
  const check = async () => {
    setBusy(true);
    const found = await refreshHolidayData();
    setBusy(false);
    showToast(found ? "Đã cập nhật lịch nghỉ mới" : "Lịch nghỉ đang là bản mới nhất");
  };
  const toggle = (key: "show" | "holidays" | "banner" | "monthly", label: string, hint?: string) => (
    <label class="check-row" key={key}>
      <input type="checkbox" checked={prefs[key]} onChange={(e) => setLunarPrefs({ [key]: (e.target as HTMLInputElement).checked })} />
      <span>{label}{hint && <small class="hint block">{hint}</small>}</span>
    </label>
  );
  return (
    <div class="card-block lunar-settings">
      {toggle("show", "Hiện ngày Âm", "Ngày Âm nhỏ cạnh ngày Dương ở Hôm nay, Lịch và khi sửa việc.")}
      {toggle("holidays", "Đánh dấu ngày lễ & nghỉ nhà nước", "Tết, Giỗ Tổ, 30/4, 1/5, Quốc khánh, cả ngày nghỉ bù.")}
      {toggle("banner", "Banner “Sắp nghỉ” ở Hôm nay")}
      <label>Báo trước
        <select value={prefs.lead} onChange={(e) => setLunarPrefs({ lead: Number((e.target as HTMLSelectElement).value) })}>
          {LEAD_CHOICES.map((n) => <option key={n} value={n}>{n} ngày (Tết luôn ít nhất 14 ngày)</option>)}
        </select>
      </label>
      {toggle("monthly", "Mùng 1 và Rằm hằng tháng", "Đánh dấu trên lịch và nhắc ở Hôm nay từ hôm trước để chuẩn bị đồ cúng.")}
      <div class="row-between">
        <small class="hint">Lịch nghỉ cập nhật ngày {updated ? updated.split("-").reverse().join("/") : "?"}. Ngày nghỉ bù và hoán đổi do nhà nước công bố từng năm.</small>
        <button type="button" class="btn ghost small" disabled={busy} onClick={() => void check()}>{busy ? "Đang kiểm tra…" : "Kiểm tra bản mới"}</button>
      </div>
    </div>
  );
}
