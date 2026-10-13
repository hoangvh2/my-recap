// Service worker for push only (no caching): shows what the server sent and opens the app where it points.
// Kept dependency-free on purpose so nothing is loaded from another host.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let p = {};
  try {
    p = event.data ? event.data.json() : {};
  } catch {
    p = {};
  }
  const n = p.notification || {};
  const d = p.data || {};
  const title = n.title || d.title || "VHub Desk";
  const url = (p.fcmOptions && p.fcmOptions.link) || d.url || "/";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: n.body || d.body || "",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      tag: n.tag || d.shareId || d.kind || "vhub",
      data: { url },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL((event.notification.data && event.notification.data.url) || "/", self.location.origin);
  if (target.origin !== self.location.origin) return;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const c of list) {
        if (new URL(c.url).origin === target.origin && "focus" in c) {
          c.navigate(target.href);
          return c.focus();
        }
      }
      return self.clients.openWindow(target.href);
    }),
  );
});
