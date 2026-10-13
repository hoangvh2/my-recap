import { useEffect } from "preact/hooks";
import { dueNudges } from "../../../shared/renewals";
import { startRouter, route, tabOf, to, go, type Route } from "../lib/router";
import { useStore } from "../lib/store";
import { pendingCount } from "../lib/pending";
import { items as itemsStore, proposals as proposalStore, setDone, syncState } from "../data";
import { CustomerDetail, Customers } from "./Customers";
import { useCtx, useGraph, useUid } from "./hooks";
import { showToast } from "./toast";
import { Hosts, openCapture } from "./host";
import { Icon, type IconName } from "./icons";
import { ImportScreen } from "./Import";
import { LicenseDetail } from "./LicenseDetail";
import { Renewals } from "./Renewals";
import { Settings } from "./Settings";
import { Today } from "./Today";
import { Work } from "./Work";
import { ContactDetail, ContactsScreen, useOpenSharedRoute } from "./Sharing";

function Screen({ r, email }: { r: Route; email: string }) {
  switch (r.name) {
    case "today": return <Today />;
    case "customers": return <Customers />;
    case "customer": return <CustomerDetail id={r.id} key={r.id} />;
    case "renewals": return <Renewals />;
    case "license": return <LicenseDetail id={r.id} key={r.id} />;
    case "work": return <Work />;
    case "settings": return <Settings email={email} />;
    case "import": return <ImportScreen />;
    case "done": return <MarkDone id={r.id} key={r.id} />;
    case "contacts": return <ContactsScreen />;
    case "contact": return <ContactDetail uid={r.uid} key={r.uid} />;
    case "shared": return <OpenShared id={r.id} key={r.id} />;
  }
}

/**
 * The "✅ Đã xong" link of a notification: ticks the item off as soon as the data has loaded (an appointment
 * repeating every week moves to its next date), says so with an undo, and lands on Today. Nothing else to tap.
 */
function MarkDone({ id }: { id: string }) {
  const uid = useUid();
  const all = useStore(itemsStore);
  const sync = useStore(syncState);
  useEffect(() => {
    if (sync !== "ready") return;
    const item = all.find((i) => i.id === id);
    go(to.today, { replace: true });
    if (!item) return void showToast("Mục này không còn nữa (có thể đã bị xoá)");
    if (item.status !== "OPEN") return void showToast(`Đã xong từ trước: ${item.title}`);
    const fail = () => showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
    setDone(uid, item, true).then(
      () => showToast(`Đã xong: ${item.title}`, { label: "Hoàn tác", run: () => setDone(uid, item, false).catch(fail) }),
      fail,
    );
  }, [sync]);
  return <Today />;
}

/** A push about something shared ("#/chia-se/<id>"): Today, with that item open on top. */
function OpenShared({ id }: { id: string }) {
  useOpenSharedRoute(id);
  return <Today />;
}

const TABS: { key: "today" | "customers" | "renewals" | "work"; label: string; icon: IconName; href: string }[] = [
  { key: "today", label: "Hôm nay", icon: "home", href: to.today },
  { key: "customers", label: "Khách", icon: "users", href: to.customers },
  { key: "renewals", label: "Gia hạn", icon: "renew", href: to.renewals },
  { key: "work", label: "Việc", icon: "list", href: to.work },
];

/** The frame of the app: the current screen, and the tab bar with the microphone always in the middle. */
export function Shell({ email }: { email: string }) {
  const r = useStore(route);
  const g = useGraph();
  const ctx = useCtx();
  const props = useStore(proposalStore);
  useEffect(startRouter, []);

  const open = g.licenses.filter((l) => l.status === "OPEN");
  const due = dueNudges(open, ctx).length;
  const pending = pendingCount(g, props);
  const active = tabOf(r);
  const focus = r.name === "customer" ? { customerId: r.id } : r.name === "license" ? { licenseId: r.id, customerId: g.licenseById.get(r.id)?.customerId } : {};
  const badge = (key: string) => (key === "today" ? due + pending : key === "renewals" ? due : 0);

  const tab = (t: (typeof TABS)[number]) => (
    <a
      key={t.key}
      href={t.href}
      class={`tab ${active === t.key ? "on" : ""}`}
      aria-current={active === t.key ? "page" : undefined}
      onClick={(e) => {
        e.preventDefault();
        go(t.href);
      }}
    >
      <span class="ico"><Icon name={t.icon} size={24} />{badge(t.key) > 0 && <b class="dotbadge">{badge(t.key)}</b>}</span>
      <span>{t.label}</span>
    </a>
  );

  return (
    <div class="app">
      <main><Screen r={r} email={email} /></main>
      <nav class="tabbar" aria-label="Điều hướng">
        {tab(TABS[0])}
        {tab(TABS[1])}
        <button class="mic-tab" aria-label="Mở ô ghi nhanh (micro)" onClick={() => openCapture(focus)}><Icon name="mic" size={30} /></button>
        {tab(TABS[2])}
        {tab(TABS[3])}
      </nav>
      <Hosts />
    </div>
  );
}
