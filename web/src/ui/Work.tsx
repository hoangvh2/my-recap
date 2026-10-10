import { useMemo, useState } from "preact/hooks";
import { blankItem, items as itemsStore, setDone } from "../data";
import { TYPE_LABEL, TYPES, type Item, type ItemType } from "../lib/model";
import { useStore } from "../lib/store";
import { Sheet, TopBar } from "./common";
import { useUid } from "./hooks";
import { openEditor } from "./host";
import { Icon } from "./icons";
import { TYPE_ICON } from "./ItemRow";
import { Events, Expenses, Notes, Overview, Tasks } from "./Lists";
import { showToast } from "./toast";

type Tab = "ALL" | ItemType;
const TABS: { key: Tab; label: string }[] = [{ key: "ALL", label: "Tất cả" }, ...TYPES.map((t) => ({ key: t as Tab, label: TYPE_LABEL[t] }))];

/** Everything that is not a renewal: tasks, appointments, expenses and notes, wherever they belong. */
export function Work() {
  const uid = useUid();
  const all = useStore(itemsStore);
  const [tab, setTab] = useState<Tab>("ALL");
  const [adding, setAdding] = useState(false);
  const saved = useMemo(() => all.filter((i) => i.status !== "DRAFT"), [all]);
  const counts = useMemo(() => {
    const open = saved.filter((i) => i.status === "OPEN");
    return { ALL: open.filter((i) => i.type === "TASK" || i.type === "EVENT").length, TASK: open.filter((i) => i.type === "TASK").length, EVENT: open.filter((i) => i.type === "EVENT").length, EXPENSE: 0, NOTE: 0 } as Record<Tab, number>;
  }, [saved]);

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
      {tab === "ALL" && <Overview items={saved} onOpen={open} onToggle={toggle} />}
      {tab === "TASK" && <Tasks items={saved} onOpen={open} onToggle={toggle} />}
      {tab === "EVENT" && <Events items={saved} onOpen={open} onToggle={toggle} />}
      {tab === "EXPENSE" && <Expenses items={saved} onOpen={open} />}
      {tab === "NOTE" && <Notes items={saved} onOpen={open} onToggle={toggle} />}
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
