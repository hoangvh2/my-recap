import { deleteItems, saveItem } from "../data";
import { createStore, useStore } from "../lib/store";
import type { Item } from "../lib/model";
import { Capture } from "./Capture";
import { ConfirmHost, Sheet } from "./common";
import { Editor } from "./Editor";
import { useUid } from "./hooks";
import type { ReviewFilter } from "./Review";
import { SearchHost } from "./Search";
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
          onSave={(item) => {
            editing.set(null);
            saveItem(uid, item).catch(fail);
          }}
          onDelete={(item) => {
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
      <ConfirmHost />
      <Toast />
    </>
  );
}
