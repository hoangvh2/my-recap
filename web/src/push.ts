import { deleteToken, getMessaging, getToken, isSupported, onMessage } from "firebase/messaging";
import { app } from "./firebase";
import { forgetDevice, saveDevice } from "./share";
import { showToast } from "./ui/toast";

/**
 * Push to this browser (Android Chrome, desktop, and iPhone once the app is on the Home Screen): someone shared
 * something, changed it, or unlinked. Uses FCM with our own tiny service worker (public/sw.js), so no Firebase
 * script is loaded from elsewhere. Off until the person switches it on, because browsers only ask once.
 */

const VAPID = import.meta.env.VITE_FCM_VAPID_KEY as string | undefined;
const KEY = "vhub.push.device";

export type PushState = "unsupported" | "unconfigured" | "denied" | "off" | "on";

export async function pushState(): Promise<PushState> {
  if (!VAPID) return "unconfigured";
  if (typeof Notification === "undefined" || !("serviceWorker" in navigator) || !(await isSupported().catch(() => false))) return "unsupported";
  if (Notification.permission === "denied") return "denied";
  return Notification.permission === "granted" && read() ? "on" : "off";
}

function read(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}
function write(v: string | null): void {
  try {
    if (v) localStorage.setItem(KEY, v);
    else localStorage.removeItem(KEY);
  } catch {
    // Private mode: the token is still saved on the server; this device just will not remember the switch.
  }
}

async function idOf(token: string): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return Array.from(new Uint8Array(hash).slice(0, 20), (b) => b.toString(16).padStart(2, "0")).join("");
}

async function registration(): Promise<ServiceWorkerRegistration> {
  return navigator.serviceWorker.register("/sw.js", { scope: "/" });
}

/** Asks the browser (needs a tap), then saves this browser's token for the signed-in person. */
export async function enablePush(uid: string): Promise<PushState> {
  if (!VAPID) return "unconfigured";
  const permission = await Notification.requestPermission();
  if (permission !== "granted") return permission === "denied" ? "denied" : "off";
  const token = await getToken(getMessaging(app), { vapidKey: VAPID, serviceWorkerRegistration: await registration() });
  const id = await idOf(token);
  await saveDevice(uid, id, token);
  write(id);
  return "on";
}

export async function disablePush(uid: string): Promise<PushState> {
  const id = read();
  if (id) await forgetDevice(uid, id).catch(() => undefined);
  await deleteToken(getMessaging(app)).catch(() => undefined);
  write(null);
  return "off";
}

/** Keeps the saved token fresh after sign-in, and shows pushes that arrive while the app is open as a toast. */
export async function resumePush(uid: string): Promise<void> {
  if ((await pushState()) !== "on") return;
  try {
    const token = await getToken(getMessaging(app), { vapidKey: VAPID, serviceWorkerRegistration: await registration() });
    const id = await idOf(token);
    if (id !== read()) {
      const old = read();
      if (old) await forgetDevice(uid, old).catch(() => undefined);
      write(id);
    }
    await saveDevice(uid, id, token);
    onMessage(getMessaging(app), (p) => {
      const title = p.notification?.title ?? p.data?.title;
      if (title) showToast(title);
    });
  } catch {
    // A refreshed permission or an offline start: the next start tries again.
  }
}
