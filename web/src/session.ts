import { GoogleAuthProvider, getRedirectResult, onAuthStateChanged, signInWithRedirect, signOut as fbSignOut } from "firebase/auth";
import { auth } from "./firebase";
import { createStore } from "./lib/store";

export type Session =
  | { status: "loading" }
  | { status: "out"; error?: string }
  | { status: "in"; uid: string; email: string };

export const session = createStore<Session>({ status: "loading" });

let started = false;

/** Follows the auth state and reports a failed redirect sign-in. Call once at startup. */
export function startSession(): void {
  if (started) return;
  started = true;
  let redirectError: string | undefined;
  // The SDK itself finishes a pending redirect before the first auth event, so this only reports failures.
  getRedirectResult(auth).catch((e: unknown) => {
    redirectError = describeAuthError(e);
    if (session.get().status === "out") session.set({ status: "out", error: redirectError });
  });
  onAuthStateChanged(auth, (user) => {
    if (user) session.set({ status: "in", uid: user.uid, email: user.email ?? "" });
    else session.set({ status: "out", error: redirectError });
  });
}

export function signInWithGoogle(): Promise<never> {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  return signInWithRedirect(auth, provider) as Promise<never>;
}

export function signOut(): Promise<void> {
  return fbSignOut(auth);
}

function describeAuthError(e: unknown): string {
  const code = (e as { code?: string } | null)?.code ?? "";
  if (code === "auth/network-request-failed") return "Không có mạng, thử lại sau";
  if (code === "auth/unauthorized-domain") return "Tên miền này chưa được khai báo trong Firebase Authentication";
  if (code === "auth/user-cancelled" || code === "auth/popup-closed-by-user") return "Đã huỷ đăng nhập";
  if (code === "auth/internal-error" && /blocked|permission/i.test((e as Error).message ?? "")) return "Tài khoản này chưa được cấp quyền";
  return "Đăng nhập không thành công, thử lại";
}
