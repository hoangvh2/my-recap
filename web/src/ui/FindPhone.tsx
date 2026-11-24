import { useEffect, useState } from "preact/hooks";
import { findPhone, type PhoneInfo } from "../api";
import { dayLabel, clock } from "../lib/format";
import { showToast } from "./toast";

/** "hôm nay 14:05" */
const lastSeen = (t: number) => (t > 0 ? `${dayLabel(t)} ${clock(t)}` : "chưa rõ");

/**
 * Cài đặt → Tìm điện thoại. Lists my Android phones that are signed in and allow it; "Đổ chuông" makes one ring
 * (at most 30 seconds, with a Stop button on the phone), "Dừng" stops it. Nothing here deletes anything.
 */
export function FindPhoneCard() {
  const [phones, setPhones] = useState<PhoneInfo[] | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const load = async () => {
    setError("");
    try {
      setPhones((await findPhone({ action: "list" })).devices ?? []);
    } catch (e) {
      setPhones(null);
      setError(e instanceof Error ? e.message : "Chưa tải được danh sách điện thoại");
    }
  };
  useEffect(() => { void load(); }, []);

  const send = async (p: PhoneInfo, action: "ring" | "stop") => {
    setBusy(`${action}:${p.id}`);
    try {
      await findPhone({ action, id: p.id });
      showToast(action === "ring" ? `Đã gửi lệnh đổ chuông tới ${p.name}` : `Đã gửi lệnh dừng tới ${p.name}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : "Chưa gửi được lệnh");
    } finally {
      setBusy("");
    }
  };

  return (
    <div class="card-block find-phone">
      <p class="hint">Làm điện thoại Android của bạn đổ chuông để tìm (tối đa 30 giây, kể cả khi đang im lặng). Máy cần có mạng và đã đăng nhập app Vhub Desk. Trên điện thoại có nút Dừng.</p>
      {error && <p class="alert" role="alert">{error}</p>}
      {phones !== null && (
        <ul class="list" aria-label="Điện thoại Android của bạn">
          {phones.map((p) => (
            <li class="row-item" key={p.id}>
              <div class="row-main">
                <span class="title">{p.name}</span>
                <span class="meta">Hoạt động lần cuối: {lastSeen(p.updatedAt)}</span>
              </div>
              <button type="button" class="btn solid" disabled={busy !== ""} aria-label={`Đổ chuông ${p.name}`} onClick={() => void send(p, "ring")}>Đổ chuông</button>
              <button type="button" class="btn ghost" disabled={busy !== ""} aria-label={`Dừng chuông ${p.name}`} onClick={() => void send(p, "stop")}>Dừng</button>
            </li>
          ))}
          {phones.length === 0 && <li class="empty">Chưa có điện thoại nào. Đăng nhập app Android bằng cùng tài khoản và bật "Cho phép tìm máy từ web" trong Cài đặt của app.</li>}
        </ul>
      )}
      <button type="button" class="btn ghost wide" disabled={busy !== ""} onClick={() => void load()}>Tải lại danh sách</button>
    </div>
  );
}
