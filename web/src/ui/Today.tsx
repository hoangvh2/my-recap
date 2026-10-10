import { useMemo } from "preact/hooks";
import { diffDays } from "../../../shared/dates";
import { dueNudges, upcomingNudges } from "../../../shared/renewals";
import { items as itemsStore, setDone } from "../data";
import { todayLabel } from "../lib/format";
import type { Item } from "../lib/model";
import { go, to } from "../lib/router";
import { useStore } from "../lib/store";
import { overview } from "../lib/views";
import { NudgeRow, useLicenseActions } from "./actions";
import { Capture } from "./Capture";
import { Empty, Link, Section, TopBar } from "./common";
import { useCtx, useGraph, useUid } from "./hooks";
import { Icon } from "./icons";
import { openEditor } from "./host";
import { ItemRow } from "./ItemRow";
import { Review } from "./Review";
import { openSearch } from "./Search";
import { showToast } from "./toast";

/** The first screen: what to do today, in order of how much it matters. */
export function Today() {
  const g = useGraph();
  const ctx = useCtx();
  const uid = useUid();
  const all = useStore(itemsStore);
  const actions = useLicenseActions();
  const open = useMemo(() => g.licenses.filter((l) => l.status === "OPEN"), [g.licenses]);
  const due = useMemo(() => dueNudges(open, ctx), [open, ctx]);
  const soon = useMemo(() => upcomingNudges(open, ctx, 14), [open, ctx]);
  const o = useMemo(() => overview(all.filter((i) => i.status !== "DRAFT"), Date.now()), [all]);

  const openItem = (i: Item) => openEditor(i);
  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
  const toggle = (item: Item, done: boolean) => {
    setDone(uid, item, done).catch(fail);
    if (done) showToast(`Đã xong: ${item.title}`, { label: "Hoàn tác", run: () => setDone(uid, item, false).catch(fail) });
  };
  const nothing = due.length === 0 && o.overdue.length === 0 && o.today.length === 0;
  const hasData = g.customers.some((c) => c.status === "OPEN") || all.length > 0;

  return (
    <>
      <TopBar title="Hôm nay" sub={todayLabel()} right={<><button class="icon-btn" aria-label="Tìm kiếm" onClick={openSearch}><Icon name="search" /></button><Link to={to.settings} class="icon-btn" label="Cài đặt"><Icon name="cog" /></Link></>} />
      <Capture />
      <Review />

      {due.length > 0 && (
        <Section title="Gia hạn cần xử lý" count={due.length} tone={due.some((n) => n.severity === "danger") ? "danger" : "warn"}>
          <ul class="nudges">{due.map((n) => <NudgeRow key={n.licenseId} nudge={n} actions={actions} />)}</ul>
        </Section>
      )}

      {o.overdue.length > 0 && (
        <Section title="Việc quá hạn" count={o.overdue.length} tone="warn">
          <ul class="list">{o.overdue.map((i) => <ItemRow key={i.id} item={i} onOpen={openItem} onToggle={toggle} />)}</ul>
        </Section>
      )}
      {o.today.length > 0 && (
        <Section title="Việc và lịch hôm nay" count={o.today.length}>
          <ul class="list">{o.today.map((i) => <ItemRow key={i.id} item={i} onOpen={openItem} onToggle={toggle} showDate={false} />)}</ul>
        </Section>
      )}

      {nothing && hasData && <Empty>Hôm nay không có gì gấp. Bạn có thể nghỉ một chút hoặc gọi thăm khách lâu không liên lạc.</Empty>}
      {!hasData && (
        <section class="welcome">
          <h3>Bắt đầu thế nào?</h3>
          <ol>
            <li><strong>Nói hoặc gõ</strong> vào ô “Ghi nhanh” ở trên, ví dụ: “Công ty ABC mua phần mềm kế toán, hết hạn 31/12/2027”.</li>
            <li>Hoặc <Link to={to.import}>nhập danh sách khách và license từ Excel</Link>.</li>
            <li>App sẽ tự nhắc bạn hỏi gia hạn <strong>trước khi hết hạn</strong> (mặc định 90, 60, 30, 14 ngày).</li>
          </ol>
        </section>
      )}

      {soon.length > 0 && (
        <Section title="Sắp đến hạn xử lý" count={soon.length} action={<Link to={to.renewals} class="more">Xem tất cả</Link>}>
          <ul class="list">
            {soon.slice(0, 5).map((n) => {
              const l = g.licenseById.get(n.licenseId);
              if (!l) return null;
              const days = diffDays(ctx.today, n.dueDate);
              return (
                <li class="row-item" key={n.licenseId}>
                  <span class="badge"><Icon name="clock" size={18} /></span>
                  <button class="row-main" onClick={() => go(to.license(l.id))}>
                    <span class="title">{g.customerName(l.customerId)} · {l.product}</span>
                    <span class="meta">{n.action} · {days <= 1 ? "ngày mai" : `sau ${days} ngày`}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </Section>
      )}

      {o.upcoming.length > 0 && (
        <Section title="Sắp tới" action={<Link to={to.work} class="more">Xem tất cả</Link>}>
          <ul class="list">{o.upcoming.slice(0, 5).map((i) => <ItemRow key={i.id} item={i} onOpen={openItem} onToggle={toggle} />)}</ul>
        </Section>
      )}
      {actions.dialogs}
    </>
  );
}

