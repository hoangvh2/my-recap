import { useEffect, useMemo, useState } from "preact/hooks";
import { cleanAlias, formatCode, initial, isShareable, normalizeCode, SHARE_LIMITS, type Contact, type ShareDoc } from "../../../shared/share";
import { blankItem, saveItem } from "../data";
import { whenLabel } from "../lib/format";
import { buildIcs } from "../lib/ics";
import { RECURRENCE_LABEL, TYPE_LABEL, type Item } from "../lib/model";
import { back, go, to } from "../lib/router";
import { OWNER_FILTERS, sharedWithCounts, sharers, type OwnerFilter } from "../lib/sharing";
import { createStore, useStore } from "../lib/store";
import { disablePush, enablePush, pushState, type PushState } from "../push";
import { manageMembers } from "../api";
import {
  asItem, createCode, dismissNotice, joinCode, notices as noticeStore, ownerLabel, previewCode, received as receivedStore,
  renameContact, setAudience, setContactNotify, unlink,
} from "../share";
import { confirmDialog, Empty, Section, Sheet, TopBar } from "./common";
import { saveFile } from "./download";
import { useAudiences, useContacts, useReceivedMap, useUid } from "./hooks";
import { Icon } from "./icons";
import { showToast } from "./toast";

// ---------------------------------------------------------------- small pieces on rows

/** Round badge with a person's first letter, so a shared row reads at a glance. */
export const Avatar = ({ name }: { name: string }) => <span class="avatar" aria-hidden="true">{initial(name)}</span>;

/** On a row someone else shared with me: who, and that it is read-only. */
export function OwnerChip({ share }: { share: ShareDoc }) {
  const list = useContacts();
  const name = ownerLabel(share, list);
  return <span class="chip owner-chip" aria-label={`${name} chia sẻ, chỉ xem`}><Icon name="lock" size={12} />{name}</span>;
}

/** On a row of mine that others can see: how many, tap to change who. */
export function AudienceChip({ item, count }: { item: Item; count: number }) {
  return (
    <button type="button" class="chip audience-chip" aria-label={`Đang chia sẻ với ${count} người, bấm để đổi`} onClick={() => openSharePicker(item)}>
      <Icon name="users" size={13} />{count} người
    </button>
  );
}

// ---------------------------------------------------------------- whose items a list shows

export function OwnerFilterBar({ value, onChange }: { value: OwnerFilter; onChange: (f: OwnerFilter) => void }) {
  const rec = useStore(receivedStore);
  const list = useContacts();
  const people = useMemo(() => sharers(rec), [rec]);
  if (rec.length === 0 && value.who === "all") return null;
  return (
    <div class="owner-filter">
      <div class="seg seg3" role="radiogroup" aria-label="Của ai">
        {OWNER_FILTERS.map((f) => (
          <button type="button" key={f.key} role="radio" aria-checked={value.who === f.key} class={value.who === f.key ? "on" : ""}
            onClick={() => onChange({ who: f.key })}>
            {f.label}{f.key === "shared" && rec.length > 0 ? ` (${rec.length})` : ""}
          </button>
        ))}
      </div>
      {value.who === "shared" && people.length > 1 && (
        <nav class="chips people" aria-label="Lọc theo người">
          <button class={!value.person ? "on" : ""} aria-pressed={!value.person} onClick={() => onChange({ who: "shared" })}>Mọi người</button>
          {people.map((p) => {
            const name = ownerLabel({ ownerUid: p.uid, ownerName: rec.find((d) => d.ownerUid === p.uid)?.ownerName ?? "" }, list);
            return (
              <button key={p.uid} class={value.person === p.uid ? "on" : ""} aria-pressed={value.person === p.uid} onClick={() => onChange({ who: "shared", person: p.uid })}>
                {name}<span class="count">{p.count}</span>
              </button>
            );
          })}
        </nav>
      )}
    </div>
  );
}

const FILTER_KEY = "vhub.ownerFilter";
/** The filter is remembered on this device. */
export function useOwnerFilter(): [OwnerFilter, (f: OwnerFilter) => void] {
  const [f, setF] = useState<OwnerFilter>(() => {
    try {
      const v = JSON.parse(localStorage.getItem(FILTER_KEY) ?? "null") as OwnerFilter | null;
      return v && ["all", "mine", "shared"].includes(v.who) ? v : { who: "all" };
    } catch {
      return { who: "all" };
    }
  });
  const set = (v: OwnerFilter) => {
    setF(v);
    try {
      localStorage.setItem(FILTER_KEY, JSON.stringify(v));
    } catch {
      // Not remembered in private mode; still works for this visit.
    }
  };
  return [f, set];
}

// ---------------------------------------------------------------- the read-only view

const viewing = createStore<string | null>(null);
export const openShared = (shareId: string): void => viewing.set(shareId);

export function SharedHost() {
  const id = useStore(viewing);
  const map = useReceivedMap();
  if (!id) return null;
  const d = map.get(id);
  return <SharedDetail share={d} onClose={() => viewing.set(null)} />;
}

function SharedDetail({ share, onClose }: { share: ShareDoc | undefined; onClose: () => void }) {
  const uid = useUid();
  const list = useContacts();
  if (!share) {
    return (
      <Sheet title="Mục được chia sẻ" onClose={onClose}>
        <Empty>Mục này không còn được chia sẻ với bạn (người tạo đã xoá, bỏ chia sẻ hoặc huỷ liên kết).</Empty>
      </Sheet>
    );
  }
  const name = ownerLabel(share, list);
  const item = asItem(share);
  const updated = new Date(share.updatedAt).toLocaleString("vi-VN", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" });
  const copyToMine = async () => {
    const copy: Item = { ...blankItem(item.type), title: item.title, details: item.details, allDay: item.allDay };
    if (item.whenAt !== undefined) copy.whenAt = item.whenAt;
    if (item.place) copy.place = item.place;
    if (item.recurrence) copy.recurrence = item.recurrence;
    try {
      await saveItem(uid, copy);
      onClose();
      showToast("Đã chép về mục của bạn (bản riêng, không còn đồng bộ)");
    } catch {
      showToast("Chưa chép được, kiểm tra mạng rồi thử lại");
    }
  };
  return (
    <Sheet title={TYPE_LABEL[share.type]} onClose={onClose}>
      <div class="shared-banner" role="note">
        <Avatar name={name} />
        <div><strong>{name} chia sẻ cho bạn</strong><span class="muted">Chỉ xem · cập nhật {updated}</span></div>
        <Icon name="lock" size={18} />
      </div>
      <div class="shared-body">
        <h3 class={share.status === "DONE" ? "done" : ""}>{share.important && "❗ "}{share.title}</h3>
        {share.status === "DONE" && <p class="chip u-done">Đã xong</p>}
        {item.whenAt !== undefined && <p><Icon name="clock" size={16} /> {whenLabel(item)}</p>}
        {share.place && <p>📍 {share.place}</p>}
        {share.recurrence && <p><Icon name="repeat" size={16} /> {RECURRENCE_LABEL[share.recurrence]}</p>}
        {share.details && <p class="details">{share.details}</p>}
      </div>
      <div class="row gap wrap">
        {item.whenAt !== undefined && (
          <button class="btn ghost grow" onClick={() => void saveFile(`${share.title.slice(0, 40).replace(/[^\p{L}\p{N}]+/gu, "-")}.ics`, "text/calendar", buildIcs([item]))}>
            <Icon name="calendar" size={18} /> Thêm vào Lịch
          </button>
        )}
        <button class="btn ghost grow" onClick={() => void copyToMine()}><Icon name="copy" size={18} /> Chép về của tôi</button>
      </div>
      <p class="hint">Chỉ {name} sửa được mục này. Khi {name} sửa, bạn nhận thông báo.</p>
    </Sheet>
  );
}

/** Opened from a push ("#/chia-se/<id>"): show Today with the shared item on top. */
export function useOpenSharedRoute(id: string | undefined) {
  useEffect(() => {
    if (!id) return;
    openShared(id);
    go(to.today, { replace: true });
  }, [id]);
}

// ---------------------------------------------------------------- choosing who sees one item

const picking = createStore<{ item: Item; onPicked?: (uids: string[]) => void; initial?: string[] } | null>(null);

/** Change who sees a saved item (saves at once), or pick people for an item being edited (`onPicked`). */
export const openSharePicker = (item: Item, onPicked?: (uids: string[]) => void, initial?: string[]): void => picking.set({ item, onPicked, initial });

export function SharePickerHost() {
  const p = useStore(picking);
  if (!p) return null;
  return <SharePicker {...p} onClose={() => picking.set(null)} />;
}

function SharePicker({ item, onPicked, initial: start, onClose }: { item: Item; onPicked?: (uids: string[]) => void; initial?: string[]; onClose: () => void }) {
  const list = useContacts();
  const aud = useAudiences();
  const current = start ?? aud.get(item.id) ?? [];
  const [chosen, setChosen] = useState<Set<string>>(new Set(current));
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const shown = list.filter((c) => !q || c.alias.toLowerCase().includes(q.toLowerCase()) || c.googleName.toLowerCase().includes(q.toLowerCase()));
  const toggle = (uid: string) => {
    const next = new Set(chosen);
    if (next.has(uid)) next.delete(uid);
    else if (next.size < SHARE_LIMITS.audience) next.add(uid);
    setChosen(next);
  };
  const done = async () => {
    const uids = [...chosen];
    if (onPicked) {
      onPicked(uids);
      return onClose();
    }
    setBusy(true);
    try {
      await setAudience(item.id, uids);
      showToast(uids.length ? `Đã chia sẻ với ${uids.length} người` : "Đã thôi chia sẻ mục này");
      onClose();
    } catch (e) {
      showToast(e instanceof Error ? e.message : "Chưa chia sẻ được");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet title="Chia sẻ mục này với" onClose={onClose} level={2}>
      <p class="hint">Người được chọn chỉ xem được tiêu đề, giờ, địa điểm và ghi chú. Chỉ bạn sửa được. Khi bạn sửa, họ nhận thông báo.</p>
      {list.length === 0 ? (
        <div class="empty-share">
          <p>Bạn chưa liên kết với ai.</p>
          <button class="btn solid" onClick={() => { onClose(); go(to.contacts); }}>Thêm người để chia sẻ</button>
        </div>
      ) : (
        <>
          {list.length > 6 && <input class="search-in" placeholder="Tìm người" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} aria-label="Tìm người" />}
          <ul class="pick-list">
            {shown.map((c) => (
              <li key={c.uid}>
                <label class="pick">
                  <input type="checkbox" checked={chosen.has(c.uid)} onChange={() => toggle(c.uid)} />
                  <Avatar name={c.alias} />
                  <span class="grow">{c.alias}{c.alias !== c.googleName && <small class="muted"> · {c.googleName}</small>}</span>
                </label>
              </li>
            ))}
          </ul>
          <button class="btn solid wide" disabled={busy} onClick={() => void done()}>
            {chosen.size > 0 ? `Chia sẻ với ${chosen.size} người` : current.length > 0 ? "Thôi chia sẻ" : "Xong"}
          </button>
        </>
      )}
    </Sheet>
  );
}

/** The "Chia sẻ" row of the editor. Shareable types only; expenses never. */
export function EditorShareRow({ item, value, onChange }: { item: Item; value: string[]; onChange: (uids: string[]) => void }) {
  const list = useContacts();
  if (!isShareable(item.type)) return null;
  const names = value.map((u) => list.find((c) => c.uid === u)?.alias).filter(Boolean) as string[];
  return (
    <button type="button" class="share-row" onClick={() => openSharePicker(item, onChange, value)}>
      <Icon name="users" size={18} />
      <span class="grow">
        <strong>{names.length ? `Chia sẻ với ${names.length} người` : "Chia sẻ"}</strong>
        <small class="muted">{names.length ? names.join(", ") : "Riêng tư: chỉ bạn thấy"}</small>
      </span>
      <Icon name="chevronRight" size={18} />
    </button>
  );
}

// ---------------------------------------------------------------- notices ("X unlinked")

export function NoticeBanner() {
  const uid = useUid();
  const list = useStore(noticeStore);
  if (list.length === 0) return null;
  return (
    <div class="notices">
      {list.slice(0, 3).map((n) => (
        <div key={n.id} class={`notice ${n.kind}`} role="status">
          <Icon name={n.kind === "linked" ? "users" : "warn"} size={18} />
          <span class="grow">
            {n.kind === "linked" ? <><strong>{n.fromName}</strong> đã liên kết với bạn. Bạn có thể đặt tên gợi nhớ và chia sẻ từng mục.</> : <><strong>{n.fromName}</strong> đã huỷ liên kết. Các mục hai bạn chia sẻ cho nhau không còn hiện nữa.</>}
          </span>
          {n.kind === "linked" && <button class="btn text" onClick={() => { void dismissNotice(uid, n.id); go(to.contact(n.fromUid)); }}>Đặt tên</button>}
          <button class="icon-btn" aria-label="Đã biết" onClick={() => void dismissNotice(uid, n.id)}><Icon name="close" size={18} /></button>
        </div>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- contacts screens

export function ContactsScreen() {
  const list = useContacts();
  const rec = useStore(receivedStore);
  const aud = useAudiences();
  const mine = useMemo(() => sharedWithCounts(aud), [aud]);
  const theirs = useMemo(() => new Map(sharers(rec).map((p) => [p.uid, p.count])), [rec]);
  const [mode, setMode] = useState<"make" | "enter" | null>(null);
  return (
    <>
      <TopBar title="Liên hệ chia sẻ" sub="Người bạn chia sẻ việc, lịch, ghi chú" backTo={to.settings} />
      <div class="row gap">
        <button class="btn solid grow" onClick={() => setMode("make")}><Icon name="plus" size={18} /> Tạo mã</button>
        <button class="btn ghost grow" onClick={() => setMode("enter")}><Icon name="link" size={18} /> Nhập mã</button>
      </div>
      <p class="hint">Một người tạo mã, người kia nhập mã để liên kết. Sau đó mỗi người tự chọn chia sẻ từng mục cho ai; không ai tự động thấy gì.</p>
      <Section title="Liên hệ" count={list.length}>
        {list.length === 0 ? <Empty>Chưa có ai. Bấm “Tạo mã” rồi gửi mã cho người thân, hoặc nhập mã họ gửi bạn.</Empty> : (
          <ul class="list">
            {list.map((c) => (
              <li key={c.uid} class="row-item">
                <Avatar name={c.alias} />
                <button class="row-main" onClick={() => go(to.contact(c.uid))}>
                  <span class="title">{c.alias}</span>
                  <span class="meta">Bạn chia sẻ {mine.get(c.uid) ?? 0} · Nhận {theirs.get(c.uid) ?? 0}</span>
                </button>
                <Icon name="chevronRight" size={18} />
              </li>
            ))}
          </ul>
        )}
      </Section>
      {mode === "make" && <CodeMaker onClose={() => setMode(null)} />}
      {mode === "enter" && <CodeEnter onClose={() => setMode(null)} />}
    </>
  );
}

function CodeMaker({ onClose }: { onClose: () => void }) {
  const [code, setCode] = useState<{ code: string; expiresAt: number } | null>(null);
  const [error, setError] = useState("");
  const [now, setNow] = useState(Date.now());
  const make = () => {
    setError("");
    createCode().then(setCode, (e: Error) => setError(e.message));
  };
  useEffect(make, []);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  const left = code ? Math.max(0, Math.ceil((code.expiresAt - now) / 1000)) : 0;
  const copy = async () => {
    if (!code) return;
    try {
      await navigator.clipboard.writeText(`Mã liên kết VHub Desk của tôi: ${formatCode(code.code)} (hết hạn sau ${SHARE_LIMITS.codeMinutes} phút)`);
      showToast("Đã sao chép mã");
    } catch {
      showToast("Không sao chép được, hãy đọc mã cho người kia");
    }
  };
  return (
    <Sheet title="Mã liên kết" onClose={onClose}>
      {!code && !error && <p class="muted">Đang tạo mã…</p>}
      {code && left > 0 && (
        <>
          <div class="link-code" aria-label={`Mã ${code.code.split("").join(" ")}`}>{formatCode(code.code)}</div>
          <p class="center-text muted">Hết hạn sau <strong>{Math.floor(left / 60)}:{String(left % 60).padStart(2, "0")}</strong> · dùng một lần</p>
          <button class="btn solid wide" onClick={() => void copy()}><Icon name="copy" size={18} /> Sao chép để gửi</button>
          <p class="hint">Gửi mã qua Zalo, tin nhắn hoặc đọc cho người kia. Họ mở VHub Desk (web hoặc Android) → Liên hệ chia sẻ → Nhập mã.</p>
        </>
      )}
      {code && left === 0 && (
        <>
          <p class="alert">Mã đã hết hạn.</p>
          <button class="btn solid wide" onClick={make}>Tạo mã mới</button>
        </>
      )}
      {error && <p class="alert" role="alert">{error}</p>}
    </Sheet>
  );
}

function CodeEnter({ onClose }: { onClose: () => void }) {
  const [raw, setRaw] = useState("");
  const [who, setWho] = useState<{ name: string; maskedEmail: string; already: boolean } | null>(null);
  const [alias, setAlias] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const code = normalizeCode(raw);
  const check = async () => {
    if (!code) return setError("Mã gồm 8 ký tự chữ và số");
    setBusy(true);
    setError("");
    try {
      const w = await previewCode(code);
      setWho(w);
      setAlias(w.name);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Chưa kiểm tra được mã");
    } finally {
      setBusy(false);
    }
  };
  const join = async () => {
    if (!code) return;
    setBusy(true);
    setError("");
    try {
      const r = await joinCode(code, cleanAlias(alias));
      showToast(`Đã liên kết với ${r.contact.alias}`);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Chưa liên kết được");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet title="Nhập mã liên kết" onClose={onClose}>
      {!who ? (
        <form onSubmit={(e) => { e.preventDefault(); void check(); }}>
          <label>Mã 8 ký tự
            <input class="code-in" autoFocus autoCapitalize="characters" autoComplete="off" spellcheck={false} maxLength={11} value={raw} placeholder="VD: K7M4 QX9D"
              onInput={(e) => setRaw((e.target as HTMLInputElement).value)} />
          </label>
          <button type="submit" class="btn solid wide" disabled={busy || !code}>Kiểm tra mã</button>
        </form>
      ) : who.already ? (
        <>
          <p>Bạn đã liên kết với <strong>{who.name}</strong> rồi.</p>
          <button class="btn solid wide" onClick={onClose}>Đóng</button>
        </>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void join(); }}>
          <div class="who">
            <Avatar name={who.name} />
            <div><small class="muted">Mã này của</small><strong>{who.name}</strong><small class="muted">{who.maskedEmail}</small></div>
          </div>
          <label>Lưu người này với tên (chỉ bạn thấy)
            <input value={alias} maxLength={SHARE_LIMITS.aliasChars} onInput={(e) => setAlias((e.target as HTMLInputElement).value)} />
          </label>
          <p class="hint">Sau khi liên kết, hai bên có thể chia sẻ từng mục cho nhau. Không ai tự động thấy gì.</p>
          <button type="submit" class="btn solid wide" disabled={busy}>Liên kết</button>
          <button type="button" class="btn ghost wide" onClick={onClose}>Huỷ</button>
        </form>
      )}
      {error && <p class="alert" role="alert">{error}</p>}
    </Sheet>
  );
}

export function ContactDetail({ uid: other }: { uid: string }) {
  const me = useUid();
  const list = useContacts();
  const rec = useStore(receivedStore);
  const aud = useAudiences();
  const c = list.find((x) => x.uid === other);
  const [alias, setAlias] = useState(c?.alias ?? "");
  const [typed, setTyped] = useState(false);
  // Follow the stored name (it may load after this screen opens) until the person starts typing their own.
  useEffect(() => { if (c && !typed) setAlias(c.alias); }, [c?.alias]);
  if (!c) {
    return (
      <>
        <TopBar title="Liên hệ" backTo={to.contacts} />
        <Empty>Không còn liên kết với người này.</Empty>
      </>
    );
  }
  const mine = [...aud.values()].filter((l) => l.includes(other)).length;
  const theirs = rec.filter((d) => d.ownerUid === other).length;
  const saveAlias = async () => {
    const v = cleanAlias(alias);
    if (!v) return showToast("Tên gợi nhớ không được để trống");
    try {
      await renameContact(me, c, v);
      showToast("Đã đổi tên gợi nhớ");
    } catch {
      showToast("Chưa lưu được, kiểm tra mạng rồi thử lại");
    }
  };
  const remove = async () => {
    const ok = await confirmDialog({
      title: `Huỷ liên kết với ${c.alias}?`,
      body: `${mine} mục bạn chia sẻ sẽ biến mất khỏi máy ${c.alias}, và ${theirs} mục ${c.alias} chia sẻ sẽ biến mất khỏi máy bạn. ${c.alias} nhận thông báo.`,
      ok: "Huỷ liên kết",
      danger: true,
    });
    if (!ok) return;
    try {
      await unlink(other);
      showToast(`Đã huỷ liên kết với ${c.alias}`);
      back(to.contacts);
    } catch (e) {
      showToast(e instanceof Error ? e.message : "Chưa huỷ được, thử lại");
    }
  };
  return (
    <>
      <TopBar title={c.alias} sub={`Liên kết từ ${new Date(c.linkedAt).toLocaleDateString("vi-VN")}`} backTo={to.contacts} />
      <section class="card-block">
        <form class="row gap" onSubmit={(e) => { e.preventDefault(); void saveAlias(); }}>
          <label class="grow">Tên gợi nhớ (chỉ bạn thấy)
            <input value={alias} maxLength={SHARE_LIMITS.aliasChars} onInput={(e) => { setTyped(true); setAlias((e.target as HTMLInputElement).value); }} />
          </label>
          <button type="submit" class="btn ghost" disabled={cleanAlias(alias) === c.alias}>Lưu</button>
        </form>
        <p class="muted">Tên Google: {c.googleName} · {c.maskedEmail}</p>
      </section>
      <section class="card-block">
        <p>Bạn đang chia sẻ <strong>{mine}</strong> mục với {c.alias}. {c.alias} đang chia sẻ <strong>{theirs}</strong> mục với bạn.</p>
        <label class="check-row"><input type="checkbox" checked={c.notify} onChange={(e) => void setContactNotify(me, c, (e.target as HTMLInputElement).checked).catch(() => showToast("Chưa lưu được"))} /> Báo cho tôi khi {c.alias} chia sẻ hoặc sửa mục</label>
      </section>
      <button class="btn danger wide" onClick={() => void remove()}>Huỷ liên kết</button>
    </>
  );
}

// ---------------------------------------------------------------- push on this browser (Settings)

export function SharePushCard() {
  const uid = useUid();
  const [state, setState] = useState<PushState | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { void pushState().then(setState); }, []);
  if (state === null) return null;
  const on = async () => {
    setBusy(true);
    try {
      setState(await enablePush(uid));
    } catch {
      showToast("Chưa bật được thông báo trên trình duyệt này");
    } finally {
      setBusy(false);
    }
  };
  const off = async () => {
    setBusy(true);
    setState(await disablePush(uid));
    setBusy(false);
  };
  return (
    <div class="push-card">
      {state === "on" && <><p class="status ok">Đang bật trên trình duyệt này</p><button class="btn ghost wide" disabled={busy} onClick={() => void off()}>Tắt trên trình duyệt này</button></>}
      {state === "off" && <><p class="muted">Nhận thông báo khi ai đó chia sẻ, sửa mục đã chia sẻ hoặc huỷ liên kết.</p><button class="btn solid wide" disabled={busy} onClick={() => void on()}>Bật thông báo chia sẻ</button></>}
      {state === "denied" && <p class="muted">Trình duyệt đang chặn thông báo của trang này. Mở cài đặt trang web của trình duyệt để cho phép.</p>}
      {state === "unsupported" && <p class="muted">Trình duyệt này chưa hỗ trợ thông báo. Trên iPhone: Chia sẻ → Thêm vào Màn hình chính, rồi mở app từ màn hình chính.</p>}
      {state === "unconfigured" && <p class="muted">Máy chủ chưa cấu hình thông báo trình duyệt. Nếu đã bật Pushover, thông báo chia sẻ vẫn đến qua Pushover.</p>}
    </div>
  );
}

export type { Contact };

// ---------------------------------------------------------------- who may use the app (administrators only)

export function MembersCard() {
  const [state, setState] = useState<{ admin: boolean; admins: string[]; members: { email: string; addedAt: number }[] } | null>(null);
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { manageMembers({ action: "list" }).then(setState, () => setState(null)); }, []);
  if (!state?.admin) return null;
  const act = async (req: { action: "add" | "remove"; email: string }) => {
    if (req.action === "remove" && !(await confirmDialog({ title: `Bỏ quyền của ${req.email}?`, body: "Người này sẽ không đăng nhập vào app được nữa. Dữ liệu của họ vẫn còn.", ok: "Bỏ quyền", danger: true }))) return;
    setBusy(true);
    setError("");
    try {
      setState(await manageMembers(req));
      if (req.action === "add") {
        setEmail("");
        showToast(`Đã cho phép ${req.email}`);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Chưa làm được");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Section title="Người dùng được phép">
    <div class="card-block members">
      <p class="hint">Thêm Gmail của người thân để họ đăng nhập được (web hoặc Android). Có hiệu lực ngay, không cần cài đặt lại máy chủ.</p>
      <form class="row gap" onSubmit={(e) => { e.preventDefault(); void act({ action: "add", email: email.trim().toLowerCase() }); }}>
        <input class="grow" type="email" inputMode="email" placeholder="ten@gmail.com" value={email} aria-label="Gmail cần thêm" onInput={(e) => setEmail((e.target as HTMLInputElement).value)} />
        <button type="submit" class="btn solid" disabled={busy || !email.includes("@")}>Thêm</button>
      </form>
      {error && <p class="alert" role="alert">{error}</p>}
      <ul class="list plain">
        {state.admins.map((a) => <li key={a} class="row-item"><span class="grow">{a}</span><span class="chip">Quản trị</span></li>)}
        {state.members.map((m) => (
          <li key={m.email} class="row-item"><span class="grow">{m.email}</span>
            <button class="btn text danger-text" disabled={busy} onClick={() => void act({ action: "remove", email: m.email })}>Bỏ</button>
          </li>
        ))}
      </ul>
    </div>
    </Section>
  );
}
