import { useMemo, useState } from "preact/hooks";
import { blankItem, confirmDrafts, deleteItems, items as itemsStore, saveItem, setDone } from "../data";
import { todayLabel } from "../lib/format";
import { buildIcs } from "../lib/ics";
import { TYPE_LABEL, TYPES, type Item, type ItemType } from "../lib/model";
import { useStore } from "../lib/store";
import { matches } from "../lib/views";
import { signOut } from "../session";
import { Capture } from "./Capture";
import { Drafts } from "./Drafts";
import { Editor } from "./Editor";
import { saveFile } from "./download";
import { Icon } from "./icons";
import { ItemRow } from "./ItemRow";
import { Events, Expenses, Notes, Overview, Tasks } from "./Lists";
import { showToast, Toast } from "./toast";

type Tab = "ALL" | ItemType;
const TABS: { key: Tab; label: string }[] = [{ key: "ALL", label: "Tất cả" }, ...TYPES.map((t) => ({ key: t as Tab, label: TYPE_LABEL[t] }))];

interface Editing {
  item: Item;
  isNew: boolean;
}

export function Home({ uid, email }: { uid: string; email: string }) {
  const all = useStore(itemsStore);
  const [tab, setTab] = useState<Tab>("ALL");
  const [editing, setEditing] = useState<Editing | null>(null);
  const [menu, setMenu] = useState(false);
  const [search, setSearch] = useState<string | null>(null);
  const [addMenu, setAddMenu] = useState(false);

  const drafts = useMemo(() => all.filter((i) => i.status === "DRAFT").sort((a, b) => a.createdAt - b.createdAt), [all]);
  const saved = useMemo(() => all.filter((i) => i.status !== "DRAFT"), [all]);
  const counts = useMemo(() => {
    const open = saved.filter((i) => i.status === "OPEN");
    return { ALL: open.filter((i) => i.type === "TASK" || i.type === "EVENT").length, TASK: open.filter((i) => i.type === "TASK").length, EVENT: open.filter((i) => i.type === "EVENT").length, EXPENSE: 0, NOTE: 0 } as Record<Tab, number>;
  }, [saved]);

  const fail = (e: unknown) => showToast(e instanceof Error && /permission|denied/i.test(e.message) ? "Không có quyền ghi dữ liệu" : "Chưa lưu được, kiểm tra mạng rồi thử lại");

  const open = (item: Item) => setEditing({ item, isNew: false });
  const toggle = (item: Item, done: boolean) => {
    setDone(uid, item, done).catch(fail);
    if (done) showToast(`Đã xong: ${item.title}`, { label: "Hoàn tác", run: () => setDone(uid, item, false).catch(fail) });
  };
  const save = (item: Item) => {
    setEditing(null);
    saveItem(uid, item).catch(fail);
  };
  const remove = (item: Item) => {
    setEditing(null);
    deleteItems(uid, [item.id]).catch(fail);
    showToast(`Đã xoá: ${item.title}`, { label: "Hoàn tác", run: () => saveItem(uid, item).catch(fail) });
  };
  const saveDrafts = (list: Item[]) => confirmDrafts(uid, list).catch(fail);
  const discardDrafts = (list: Item[]) => {
    deleteItems(uid, list.map((i) => i.id)).catch(fail);
    showToast(`Đã bỏ ${list.length} mục nháp`, { label: "Hoàn tác", run: () => list.forEach((i) => saveItem(uid, i).catch(fail)) });
  };

  const exportBackup = async () => {
    setMenu(false);
    const day = new Date().toISOString().slice(0, 10);
    await saveFile(`my-recap-thu-ky-${day}.json`, "application/json", JSON.stringify({ app: "my-recap-web", exportedAt: Date.now(), items: saved }, null, 2));
  };
  const exportCalendar = async () => {
    setMenu(false);
    const dated = saved.filter((i) => i.status === "OPEN" && (i.type === "TASK" || i.type === "EVENT") && i.whenAt !== undefined);
    if (dated.length === 0) return showToast("Chưa có việc hay lịch hẹn nào có ngày");
    await saveFile("my-recap-lich.ics", "text/calendar", buildIcs(dated));
  };

  const searching = search !== null && search.trim() !== "";
  const results = useMemo(() => (searching ? saved.filter((i) => matches(i, search!)).slice(0, 100) : []), [saved, search, searching]);

  return (
    <div class="app">
      <header class="top">
        <div>
          <h1>Thư ký</h1>
          <p class="muted">{todayLabel()}</p>
        </div>
        <div class="row">
          <button class="icon-btn" aria-label="Tìm kiếm" onClick={() => setSearch(search === null ? "" : null)}><Icon name="search" /></button>
          <button class="icon-btn" aria-label="Menu" onClick={() => setMenu(true)}><Icon name="menu" /></button>
        </div>
      </header>

      {search !== null && (
        <input class="search" type="search" placeholder="Tìm việc, lịch, chi tiêu, ghi chú…" value={search} autofocus onInput={(e) => setSearch((e.target as HTMLInputElement).value)} />
      )}

      <main>
        {searching ? (
          <section class="block">
            <h3>Kết quả <span class="count">{results.length}</span></h3>
            {results.length === 0 ? <p class="empty">Không tìm thấy.</p> : <ul class="list">{results.map((i) => <ItemRow key={i.id} item={i} onOpen={open} onToggle={toggle} />)}</ul>}
          </section>
        ) : (
          <>
            <Capture />
            <Drafts drafts={drafts} onOpen={(i) => setEditing({ item: i, isNew: false })} onSave={saveDrafts} onDiscard={discardDrafts} />
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
          </>
        )}
      </main>

      <div class="fab-wrap">
        {addMenu && (
          <div class="fab-menu" role="menu">
            {TYPES.map((t) => (
              <button key={t} role="menuitem" onClick={() => { setAddMenu(false); setEditing({ item: blankItem(t), isNew: true }); }}>{TYPE_LABEL[t]}</button>
            ))}
          </div>
        )}
        <button class="fab" aria-label="Thêm bằng tay" aria-expanded={addMenu} onClick={() => setAddMenu(!addMenu)}><Icon name={addMenu ? "close" : "plus"} size={26} /></button>
      </div>

      {editing && <Editor key={editing.item.id} {...editing} onSave={save} onDelete={remove} onClose={() => setEditing(null)} />}

      {menu && (
        <div class="sheet-backdrop" onClick={(e) => e.target === e.currentTarget && setMenu(false)}>
          <div class="sheet" role="dialog" aria-modal="true" aria-label="Menu">
            <header>
              <h2>Tài khoản</h2>
              <button class="icon-btn" aria-label="Đóng" onClick={() => setMenu(false)}><Icon name="close" /></button>
            </header>
            <p class="muted">{email}</p>
            <p class="note"><Icon name="lock" size={14} /> Dữ liệu lưu trên Google, không lưu trong trình duyệt. Khoá Gemini nằm ở máy chủ.</p>
            <button class="btn ghost wide" onClick={() => void exportCalendar()}><Icon name="calendar" size={18} /> Xuất lịch (.ics) để Lịch iPhone nhắc</button>
            <button class="btn ghost wide" onClick={() => void exportBackup()}><Icon name="download" size={18} /> Sao lưu dữ liệu (JSON)</button>
            <button class="btn danger wide" onClick={() => void signOut()}><Icon name="logout" size={18} /> Đăng xuất</button>
          </div>
        </div>
      )}
      <Toast />
    </div>
  );
}
