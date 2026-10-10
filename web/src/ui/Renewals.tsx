import { useMemo, useState } from "preact/hooks";
import { addDays, monthKeyOf } from "../../../shared/dates";
import { dueNudges, isOpenLicense, monthlyForecast, openValueOf, quietCustomers } from "../../../shared/renewals";
import { blankLicense, items as itemsStore, saveLicense } from "../data";
import { formatVnd } from "../lib/money";
import { OPEN_STAGES, STAGE_LABEL, type License } from "../lib/model";
import { go, to } from "../lib/router";
import { useStore } from "../lib/store";
import { LicenseRow, NudgeRow, useLicenseActions } from "./actions";
import { Empty, Link, Section, TopBar } from "./common";
import { LicenseForm } from "./forms";
import { useCtx, useGraph, useUid } from "./hooks";
import { Icon } from "./icons";
import { showToast } from "./toast";

type Tab = "due" | "open" | "forecast" | "closed";

const monthLabel = (key: string) => `Tháng ${Number(key.slice(5))}/${key.slice(0, 4)}`;

export function Renewals() {
  const g = useGraph();
  const ctx = useCtx();
  const uid = useUid();
  const all = useStore(itemsStore);
  const actions = useLicenseActions();
  const open = useMemo(() => g.licenses.filter((l) => l.status === "OPEN"), [g.licenses]);
  const tracked = useMemo(() => open.filter(isOpenLicense), [open]);
  const due = useMemo(() => dueNudges(open, ctx), [open, ctx]);
  const [tab, setTab] = useState<Tab | null>(null);
  const [adding, setAdding] = useState<License | null>(null);
  const current: Tab = tab ?? (due.length > 0 ? "due" : "open");

  const within90 = tracked.filter((l) => l.endDate <= addDays(ctx.today, 90));
  const waiting = tracked.filter((l) => l.stage !== "ACTIVE");
  const forecast = useMemo(() => monthlyForecast(open, monthKeyOf(ctx.today), 6), [open, ctx.today]);
  const quiet = useMemo(
    () => quietCustomers(g.customers, open, all.filter((i) => i.status !== "DRAFT"), Date.now(), ctx.prefs.quietDays),
    [g.customers, open, all, ctx.prefs.quietDays],
  );
  const closed = open.filter((l) => !isOpenLicense(l)).sort((a, b) => b.updatedAt - a.updatedAt);
  const maxCount = Math.max(1, ...forecast.map((m) => m.open.count + m.renewed.count + m.lost.count));

  return (
    <>
      <TopBar title="Gia hạn" sub={`${tracked.length} license đang theo dõi`} right={
        <button class="btn solid small" onClick={() => setAdding(blankLicense(""))}><Icon name="plus" size={18} /> Thêm</button>
      } />

      <div class="tiles">
        <button class={`tile ${due.length > 0 ? "hot" : ""}`} onClick={() => setTab("due")}><strong>{due.length}</strong><span>cần xử lý ngay</span></button>
        <button class="tile" onClick={() => setTab("open")}><strong>{within90.length}</strong><span>hết hạn trong 90 ngày{openValueOf(within90) > 0 ? ` · ${formatVnd(openValueOf(within90))}` : ""}</span></button>
        <button class="tile" onClick={() => setTab("open")}><strong>{waiting.length}</strong><span>đang chờ khách</span></button>
      </div>

      <nav class="chips" aria-label="Lọc">
        {([["due", "Cần xử lý"], ["open", "Đang theo dõi"], ["forecast", "Dự báo"], ["closed", "Đã đóng"]] as const).map(([k, label]) => (
          <button key={k} class={current === k ? "on" : ""} aria-pressed={current === k} onClick={() => setTab(k)}>
            {label}{k === "due" && due.length > 0 && <span class="count">{due.length}</span>}
          </button>
        ))}
      </nav>

      {current === "due" && (
        <>
          {due.length > 0 ? (
            <ul class="nudges">{due.map((n) => <NudgeRow key={n.licenseId} nudge={n} actions={actions} />)}</ul>
          ) : (
            <Empty>Không có gia hạn nào cần xử lý lúc này. 🎉</Empty>
          )}
          {quiet.length > 0 && (
            <Section title={`Khách lâu không liên lạc (${ctx.prefs.quietDays}+ ngày)`} count={quiet.length}>
              <ul class="list">
                {quiet.slice(0, 8).map((q) => (
                  <li class="row-item" key={q.customer.id}>
                    <span class="badge"><Icon name="users" size={18} /></span>
                    <Link to={to.customer(q.customer.id)} class="row-main">
                      <span class="title">{q.customer.name}</span>
                      <span class="meta">{q.daysQuiet} ngày chưa có gì mới{q.value > 0 ? ` · ${formatVnd(q.value)}` : ""}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            </Section>
          )}
        </>
      )}

      {current === "open" && (
        tracked.length === 0 ? (
          <Empty>Chưa có license nào. Bấm “Thêm”, hoặc <Link to={to.import}>nhập từ Excel</Link>.</Empty>
        ) : (
          OPEN_STAGES.map((stage) => {
            const list = tracked.filter((l) => l.stage === stage).sort((a, b) => a.endDate.localeCompare(b.endDate));
            return list.length === 0 ? null : (
              <Section key={stage} title={STAGE_LABEL[stage]} count={list.length}>
                <ul class="list">{list.map((l) => <LicenseRow key={l.id} license={l} />)}</ul>
              </Section>
            );
          })
        )
      )}

      {current === "forecast" && (
        <Section title="6 tháng tới: license hết hạn">
          <ul class="forecast">
            {forecast.map((m) => {
              const n = m.open.count + m.renewed.count + m.lost.count;
              return (
                <li key={m.month}>
                  <span class="m">{monthLabel(m.month)}</span>
                  <span class="bar stacked" aria-hidden="true">
                    <i class="f-open" style={{ width: `${(m.open.count / maxCount) * 100}%` }} />
                    <i class="f-renewed" style={{ width: `${(m.renewed.count / maxCount) * 100}%` }} />
                    <i class="f-lost" style={{ width: `${(m.lost.count / maxCount) * 100}%` }} />
                  </span>
                  <span class="n">{n === 0 ? "—" : `${n}`}</span>
                  <span class="meta v">
                    {n === 0 ? "" : [
                      m.open.count && `${m.open.count} chờ${m.open.value ? ` (${formatVnd(m.open.value)})` : ""}`,
                      m.renewed.count && `${m.renewed.count} đã gia hạn`,
                      m.lost.count && `${m.lost.count} mất`,
                    ].filter(Boolean).join(" · ")}
                  </span>
                </li>
              );
            })}
          </ul>
          <p class="legend"><i class="f-open" /> chờ xử lý <i class="f-renewed" /> đã gia hạn <i class="f-lost" /> không gia hạn</p>
        </Section>
      )}

      {current === "closed" && (
        closed.length === 0 ? <Empty>Chưa có license nào đã đóng.</Empty> : <ul class="list">{closed.slice(0, 100).map((l) => <LicenseRow key={l.id} license={l} />)}</ul>
      )}

      {actions.dialogs}
      {adding && (
        <LicenseForm license={adding} isNew onClose={() => setAdding(null)} onSave={(l) => {
          setAdding(null);
          saveLicense(uid, l).then(() => go(to.license(l.id))).catch(() => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại"));
        }} />
      )}
    </>
  );
}
