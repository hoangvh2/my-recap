import { useEffect, useState } from "preact/hooks";
import { deviceZone, notify, type NotifyForm } from "../api";
import { notifySettings, type NotifyDoc } from "../data";
import { useStore } from "../lib/store";
import { confirmDialog } from "./common";
import { Icon } from "./icons";
import { showToast } from "./toast";

const LEADS = [5, 10, 15, 30, 60];
const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
const minutes = (v: string): number | null => {
  const m = /^(\d{2}):(\d{2})$/.exec(v);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};

/** What the form starts from: the saved settings, or the recommended defaults. */
function initial(d: NotifyDoc | null) {
  return {
    pushoverUser: d?.pushoverUser ?? "",
    device: d?.device ?? "",
    lead: d?.lead ?? 15,
    morningOn: (d?.morning ?? 420) >= 0,
    morning: hhmm(d?.morning !== undefined && d.morning >= 0 ? d.morning : 420),
    eveningOn: (d?.evening ?? 1200) >= 0,
    evening: hhmm(d?.evening !== undefined && d.evening >= 0 ? d.evening : 1200),
    quietFrom: hhmm(d?.quietFrom ?? 1320),
    quietTo: hhmm(d?.quietTo ?? 390),
    calendarAlarms: d?.calendarAlarms ?? false,
  };
}

/**
 * Reminders on the phone through Pushover: one place to set it up, test it, and read what each level means.
 * Web push on iPhone cannot repeat a reminder until it is acknowledged or break through quiet hours; Pushover can.
 */
export function NotifySettingsCard() {
  const saved = useStore(notifySettings);
  const [f, setF] = useState(() => initial(saved));
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { if (!dirty) setF(initial(saved)); }, [saved, dirty]);
  const edit = (p: Partial<typeof f>) => { setF({ ...f, ...p }); setDirty(true); };
  const on = saved?.enabled === true;

  const run = async (what: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError("");
    try {
      await what();
      showToast(ok);
      return true;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Chưa làm được, thử lại");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    const times = [minutes(f.morning), minutes(f.evening), minutes(f.quietFrom), minutes(f.quietTo)];
    if (times.some((t) => t === null)) return setError("Giờ chưa đúng, VD 07:00");
    const user = f.pushoverUser.trim();
    if (!/^[A-Za-z0-9]{30}$/.test(user)) return setError("User Key gồm 30 chữ và số. Mở app Pushover, chép dòng “Your User Key”.");
    const form: NotifyForm = {
      pushoverUser: user,
      ...(f.device.trim() ? { device: f.device.trim() } : {}),
      zone: deviceZone(),
      morning: f.morningOn ? times[0]! : -1,
      evening: f.eveningOn ? times[1]! : -1,
      quietFrom: times[2]!,
      quietTo: times[3]!,
      lead: f.lead,
      calendarAlarms: f.calendarAlarms,
    };
    if (await run(() => notify({ action: "save", settings: form }), "Đã bật thông báo. Bấm “Gửi thử” để kiểm tra.")) setDirty(false);
  };

  const off = async () => {
    const ok = await confirmDialog({ title: "Tắt thông báo?", body: "Điện thoại sẽ không nhận nhắc việc qua Pushover nữa.", ok: "Tắt", danger: true });
    if (ok) await run(() => notify({ action: "off" }), "Đã tắt thông báo");
  };

  return (
    <div class="notify">
      <p class={`status ${on ? "on" : ""}`}>
        <Icon name={on ? "check" : "clock"} size={16} /> {on ? `Đang bật · nhắc trước lịch hẹn ${saved?.lead ?? 15} phút` : "Chưa bật thông báo"}
      </p>

      <details class="howto" open={!on}>
        <summary>Cách bật (làm một lần, khoảng 3 phút)</summary>
        <ol>
          <li>Cài app <strong>Pushover</strong> từ App Store và đăng ký tài khoản (dùng thử 30 ngày, sau đó trả một lần khoảng 5 USD).</li>
          <li>Mở Pushover, chép dòng <strong>Your User Key</strong> (30 ký tự) rồi dán vào ô dưới.</li>
          <li>Bấm <strong>Lưu và bật</strong>, rồi <strong>Gửi thử</strong>.</li>
          <li>Trong Cài đặt iPhone → Thông báo → Pushover: bật <strong>Thông báo nhạy cảm thời gian</strong> để tin quan trọng vẫn đến khi đang bật Tập trung (Focus).</li>
        </ol>
      </details>

      <label>User Key của Pushover
        <input value={f.pushoverUser} maxLength={30} autoComplete="off" autoCapitalize="off" spellcheck={false} placeholder="30 chữ và số"
          onInput={(e) => edit({ pushoverUser: (e.target as HTMLInputElement).value })} />
      </label>

      <label>Nhắc trước lịch hẹn
        <select value={f.lead} onChange={(e) => edit({ lead: Number((e.target as HTMLSelectElement).value) })}>
          {LEADS.map((m) => <option key={m} value={m}>{m >= 60 ? "1 giờ" : `${m} phút`}</option>)}
        </select>
      </label>

      <div class="pair">
        <label class="check-row"><input type="checkbox" checked={f.morningOn} onChange={(e) => edit({ morningOn: (e.target as HTMLInputElement).checked })} /> Tóm tắt buổi sáng</label>
        <input type="time" aria-label="Giờ tóm tắt buổi sáng" value={f.morning} disabled={!f.morningOn} onInput={(e) => edit({ morning: (e.target as HTMLInputElement).value })} />
      </div>
      <div class="pair">
        <label class="check-row"><input type="checkbox" checked={f.eveningOn} onChange={(e) => edit({ eveningOn: (e.target as HTMLInputElement).checked })} /> Xem trước ngày mai (buổi tối)</label>
        <input type="time" aria-label="Giờ xem trước ngày mai" value={f.evening} disabled={!f.eveningOn} onInput={(e) => edit({ evening: (e.target as HTMLInputElement).value })} />
      </div>
      <div class="pair">
        <span>Giờ yên lặng</span>
        <span class="times">
          <input type="time" aria-label="Yên lặng từ" value={f.quietFrom} onInput={(e) => edit({ quietFrom: (e.target as HTMLInputElement).value })} />
          <span>đến</span>
          <input type="time" aria-label="Yên lặng đến" value={f.quietTo} onInput={(e) => edit({ quietTo: (e.target as HTMLInputElement).value })} />
        </span>
      </div>
      <p class="hint">Giờ yên lặng chỉ hoãn tin nhắc lại. Lịch hẹn và việc có giờ trong khoảng này vẫn được nhắc đúng giờ.</p>

      <details>
        <summary>Tuỳ chọn thêm</summary>
        <label>Chỉ gửi tới thiết bị (để trống: mọi thiết bị)
          <input value={f.device} maxLength={25} autoComplete="off" autoCapitalize="off" spellcheck={false} placeholder="VD iphone" onInput={(e) => edit({ device: (e.target as HTMLInputElement).value })} />
        </label>
        <label class="check-row"><input type="checkbox" checked={f.calendarAlarms} onChange={(e) => edit({ calendarAlarms: (e.target as HTMLInputElement).checked })} /> Lịch iPhone cũng đổ chuông (có thể bị nhắc 2 lần)</label>
      </details>

      {error && <p class="alert" role="alert">{error}</p>}
      <div class="row gap wrap">
        <button class="btn solid grow" disabled={busy || (on && !dirty)} onClick={() => void save()}>{on ? "Lưu thay đổi" : "Lưu và bật"}</button>
        <button class="btn ghost" disabled={busy || !on} onClick={() => void run(() => notify({ action: "test" }), "Đã gửi tin thử, xem điện thoại")}>Gửi thử</button>
        {on && <button class="btn danger" disabled={busy} onClick={() => void off()}>Tắt</button>}
      </div>

      <h4>Các mức thông báo</h4>
      <ul class="levels">
        <li><b>🔴 KHẨN</b> Mục đánh dấu “Quan trọng”: nhắc lại mỗi 5 phút đến khi bấm Xác nhận (tối đa 1 giờ).</li>
        <li><b>🟠 Nhắc đúng giờ</b> Trước lịch hẹn và đúng giờ của việc. Vẫn kêu khi Pushover đang yên lặng.</li>
        <li><b>🔵 Bình thường</b> Việc chưa xong sau 2 giờ, tóm tắt buổi sáng.</li>
        <li><b>⚪ Nhẹ</b> Xem trước ngày mai: không chuông, mở ra là thấy.</li>
      </ul>
      <p class="hint">Nhiều nhắc cùng lúc được gộp thành một tin. Ngày không có gì thì không gửi tóm tắt.</p>
    </div>
  );
}
