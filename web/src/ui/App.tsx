import { useEffect } from "preact/hooks";
import { startSync, syncState } from "../data";
import { useStore } from "../lib/store";
import { session, signInWithGoogle, signOut, startSession } from "../session";
import { Home } from "./Home";
import { Icon } from "./icons";

export function App() {
  const s = useStore(session);
  useEffect(startSession, []);
  if (s.status === "loading") return <Splash />;
  if (s.status === "out") return <Login error={s.error} />;
  return <Signed uid={s.uid} email={s.email} />;
}

function Splash() {
  return <div class="center"><span class="spinner big" aria-label="Đang tải" /></div>;
}

function Login({ error }: { error?: string }) {
  return (
    <div class="center">
      <div class="card login">
        <img src="/icons/icon-192.png" width="72" height="72" alt="" />
        <h1>My Recap · Thư ký</h1>
        <p class="muted">Ghi nhanh việc, lịch hẹn và chi tiêu bằng giọng nói.</p>
        <button class="btn solid wide" onClick={() => void signInWithGoogle()}>Đăng nhập bằng Google</button>
        {error && <p class="alert" role="alert">{error}</p>}
        <p class="note"><Icon name="lock" size={14} /> Chỉ các tài khoản được cấp quyền mới dùng được.</p>
      </div>
    </div>
  );
}

function Signed({ uid, email }: { uid: string; email: string }) {
  useEffect(() => startSync(uid), [uid]);
  const sync = useStore(syncState);
  if (sync === "denied") {
    return (
      <div class="center">
        <div class="card login">
          <Icon name="lock" size={36} />
          <h1>Chưa được cấp quyền</h1>
          <p class="muted">Tài khoản <strong>{email}</strong> chưa có quyền dùng ứng dụng này. Hãy đăng nhập bằng tài khoản Google khác hoặc liên hệ chủ ứng dụng.</p>
          <button class="btn solid wide" onClick={() => void signOut()}>Đăng xuất</button>
        </div>
      </div>
    );
  }
  return (
    <>
      {sync === "error" && <div class="banner" role="alert">Không tải được dữ liệu, kiểm tra kết nối mạng.</div>}
      {sync === "connecting" && <div class="banner info" role="status">Đang đồng bộ…</div>}
      <Home uid={uid} email={email} />
    </>
  );
}
