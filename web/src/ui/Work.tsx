import { useMemo, useState } from "preact/hooks";
import { blankItem, items as itemsStore, setDone } from "../data";
import { TYPE_LABEL, TYPES, type Item, type ItemType } from "../lib/model";
import { startOfDay } from "../lib/schedule";
import { useStore } from "../lib/store";
import { agenda } from "../lib/views";
import { Sheet, TopBar } from "./common";
import { useUid } from "./hooks";
import { openEditor } from "./host";
import { Icon } from "./icons";
import { TYPE_ICON } from "./ItemRow";
import { Events, Expenses, Notes, Overview, Tasks } from "./Lists";
import { Weekly } from "./Weekly";
import { showToast } from "./toast";
import { withReceived } from "../lib/sharing";
import { received as receivedStore } from "../share";
import { TagFilterBar, useTagFilter } from "./TagUI";
import { OwnerFilterBar, useOwnerFilter } from "./Sharing";

type Tab = "ALL" | "WEEK" | ItemType;
// "Lịch" shows every dated task and appointment (the calendar), not only appointments.
const TABS: { key: Tab; label: string }[] = [{ key: "ALL", label: "Tất cả" }, ...TYPES.map((t) => ({ key: t as Tab, label: t === "EVENT" ? "Lịch" : TYPE_LABEL[t] })), { key: "WEEK", label: "Tuần" }];

/** Everything that is not a renewal: tasks, appointments, expenses and notes, wherever they belong. */
export function Work() {
  const uid = useUid();
  const all = useStore(itemsStore);
  const [tab, setTab] = useState<Tab>("ALL");
  const [adding, setAdding] = useState(false);
  const rec = useStore(receivedStore);
  const [who, setWho] = useOwnerFilter();
  const mine = useMemo(() => all.filter((i) => i.status !== "DRAFT"), [all]);
  // Expenses and the week view are mine only; the other lists also show what others shared with me.
  const saved = useMemo(() => withReceived(mine, rec, who), [mine, rec, who]);
  // The tag filter only applies to the lists that hold tasks and appointments.
  const tf = useTagFilter(saved);
  const taggable = tab === "ALL" || tab === "TASK" || tab === "EVENT";
  const counts = useMemo(() => {
    const open = mine.filter((i) => i.status === "OPEN");
    // The calendar chip counts what is on it today: overdue tasks and today's entries.
    const now = Date.now();
    const ag = agenda(open, now);
    const today = ag.overdue.length + ag.ahead.filter((i) => i.whenAt! < startOfDay(now) + 86_400_000).length;
    return { ALL: open.filter((i) => i.type === "TASK" || i.type === "EVENT").length, TASK: open.filter((i) => i.type === "TASK").length, EVENT: today, EXPENSE: 0, NOTE: 0, WEEK: 0 } as Record<Tab, number>;
  }, [mine]);

  const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
  const open = (item: Item) => openEditor(item);
  const toggle = (item: Item, done: boolean) => {
    setDone(uid, item, done).catch(fail);
    if (done) showToast(`Đã xong: ${item.title}`, { label: "Hoàn tác", run: () => setDone(uid, item, false).catch(fail) });
  };

  return (
    <>
      <TopBar title="Việc" sub="Việc, lịch hẹn, chi tiêu, ghi chú" right={
        <button class="btn solid small" onClick={() => setAdding(true)}><Icon name="plus" size={18} /> Thêm</button>
      } />
      <nav class="chips" aria-label="Lọc">
        {TABS.map((t) => (
          <button key={t.key} class={tab === t.key ? "on" : ""} aria-pressed={tab === t.key} onClick={() => setTab(t.key)}>
            {t.label}{counts[t.key] > 0 && <span class="count">{counts[t.key]}</span>}
          </button>
        ))}
      </nav>
      {tab !== "EXPENSE" && tab !== "WEEK" && <OwnerFilterBar value={who} onChange={setWho} />}
      {taggable && tf.visible && <TagFilterBar active={tf.active} />}
      {tab === "ALL" && <Overview items={tf.shown} onOpen={open} onToggle={toggle} />}
      {tab === "TASK" && <Tasks items={tf.shown} onOpen={open} onToggle={toggle} />}
      {tab === "EVENT" && <Events items={tf.shown} onOpen={open} onToggle={toggle} />}
      {tab === "EXPENSE" && <Expenses items={mine} onOpen={open} />}
      {tab === "NOTE" && <Notes items={saved} onOpen={open} onToggle={toggle} />}
      {tab === "WEEK" && <Weekly items={mine} />}
      {adding && (
        <Sheet title="Thêm gì?" onClose={() => setAdding(false)}>
          <div class="add-grid">
            {TYPES.map((t) => (
              <button key={t} class="add-tile" onClick={() => { setAdding(false); openEditor(blankItem(t), true); }}>
                <Icon name={TYPE_ICON[t]} size={28} />
                <span>{TYPE_LABEL[t]}</span>
              </button>
            ))}
          </div>
        </Sheet>
      )}
    </>
  );
}
