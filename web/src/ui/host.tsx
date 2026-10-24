import { deleteBody } from "../lib/format";
import { deleteItems, saveItem, setDone } from "../data";
import { createStore, useStore } from "../lib/store";
import type { Item } from "../lib/model";
import { Capture } from "./Capture";
import { ConfirmHost, confirmDialog, Sheet } from "./common";
import { Editor } from "./Editor";
import { useUid } from "./hooks";
import type { ReviewFilter } from "./Review";
import { SearchHost } from "./Search";
import { SharedHost, SharePickerHost } from "./Sharing";
import { setAudience } from "../share";
import { showToast, Toast } from "./toast";

/**
 * Things any screen can open: the item editor and the quick-capture sheet. They live here, once, so a
 * customer page, a licence page and the task list all use the same editor and the same microphone.
 */
const editing = createStore<{ item: Item; isNew: boolean } | null>(null);
const capturing = createStore<ReviewFilter | null>(null);

export const openEditor = (item: Item, isNew = false): void => editing.set({ item, isNew });
export const openCapture = (focus: ReviewFilter = {}): void => capturing.set(focus);

export function Hosts() {
  const uid = useUid();
  const edit = useStore(editing);
  const cap = useStore(capturing);
  const fail = (e: unknown) => showToast(e instanceof Error && /permission|denied/i.test(e.message) ? "Không có quyền ghi dữ liệu" : "Chưa lưu được, kiểm tra mạng rồi thử lại");
  return (
    <>
      {edit && (
        <Editor
          key={edit.item.id}
          {...edit}
          onClose={() => editing.set(null)}
          onSave={(item, audience) => {
            editing.set(null);
            const saved = saveItem(uid, item);
            saved.catch(fail);
            // The server reads the saved item to build the shared view, so share only once it is stored.
            if (audience !== undefined) {
              saved.then(() => setAudience(item.id, audience)).then(
                (r) => showToast(r.audience.length ? `Đã lưu và chia sẻ với ${r.audience.length} người` : "Đã lưu, thôi chia sẻ"),
                (e: unknown) => showToast(e instanceof Error ? e.message : "Đã lưu nhưng chưa chia sẻ được"),
              );
            }
          }}
          onDone={(item) => {
            editing.set(null);
            setDone(uid, item, true).catch(fail);
            showToast(`Đã xong: ${item.title}`, { label: "Hoàn tác", run: () => setDone(uid, item, false).catch(fail) });
          }}
          onDelete={async (item) => {
            if (!(await confirmDialog({ title: "Xoá mục này?", body: deleteBody(item), ok: "Xoá", danger: true }))) return;
            editing.set(null);
            deleteItems(uid, [item.id]).catch(fail);
            showToast(`Đã xoá: ${item.title}`, { label: "Hoàn tác", run: () => saveItem(uid, item).catch(fail) });
          }}
        />
      )}
      {cap && (
        <Sheet title="Ghi nhanh" onClose={() => capturing.set(null)}>
          <Capture sheet focus={cap} onDone={() => capturing.set(null)} />
        </Sheet>
      )}
      <SearchHost />
      <SharedHost />
      <SharePickerHost />
      <ConfirmHost />
      <Toast />
    </>
  );
}
