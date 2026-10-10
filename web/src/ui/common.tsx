import type { ComponentChildren } from "preact";
import { useEffect } from "preact/hooks";
import { formatDate } from "../../../shared/dates";
import { daysLeftText, urgencyOf, type Ctx, type Urgency } from "../../../shared/renewals";
import { diffDays } from "../../../shared/dates";
import { KIND_LABEL, STAGE_LABEL, type License } from "../lib/model";
import { back, go, to } from "../lib/router";
import { createStore, useStore } from "../lib/store";
import { Icon, type IconName } from "./icons";
import { useGraph } from "./hooks";

/** An anchor that moves inside the app without reloading, and still works with "open in new tab". */
export function Link(props: { to: string; class?: string; children: ComponentChildren; label?: string }) {
  return (
    <a
      href={props.to}
      class={props.class}
      aria-label={props.label}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        go(props.to);
      }}
    >
      {props.children}
    </a>
  );
}

/** A tappable name of a customer: opens their page. */
export function CustomerChip({ id }: { id: string | undefined }) {
  const g = useGraph();
  if (!id) return null;
  const known = g.customerById.has(id);
  return known ? (
    <Link to={to.customer(id)} class="chip link-chip"><Icon name="building" size={14} />{g.customerName(id)}</Link>
  ) : (
    <span class="chip gone"><Icon name="building" size={14} />Khách đã xoá</span>
  );
}

/** A tappable licence: opens its page. */
export function LicenseChip({ id, withCustomer }: { id: string | undefined; withCustomer?: boolean }) {
  const g = useGraph();
  if (!id) return null;
  const l = g.licenseById.get(id);
  if (!l) return <span class="chip gone"><Icon name="file" size={14} />License đã xoá</span>;
  return (
    <Link to={to.license(id)} class="chip link-chip">
      <Icon name="file" size={14} />{withCustomer ? `${g.customerName(l.customerId)} · ` : ""}{l.product}
    </Link>
  );
}

export function StageChip({ stage }: { stage: License["stage"] }) {
  return <span class={`chip stage s-${stage}`}>{STAGE_LABEL[stage]}</span>;
}

export const KindChip = ({ kind }: { kind: License["kind"] }) => <span class="chip kind">{KIND_LABEL[kind]}</span>;

const URGENCY_CLASS: Record<Urgency, string> = { danger: "u-danger", warn: "u-warn", info: "u-info", calm: "u-calm", done: "u-done" };

/** "Còn 12 ngày" in the colour that says how worried to be. */
export function DaysChip({ license, ctx }: { license: License; ctx: Ctx }) {
  if (license.stage === "RENEWED" || license.stage === "LOST") return null;
  const days = diffDays(ctx.today, license.endDate);
  return <span class={`chip days ${URGENCY_CLASS[urgencyOf(license, ctx)]}`}>{daysLeftText(days)}</span>;
}

export const dateText = (d: string | undefined) => (d ? formatDate(d) : "");

export function TopBar(props: { title: string; sub?: string; backTo?: string; right?: ComponentChildren }) {
  return (
    <header class="top">
      <div class="row gap">
        {props.backTo && (
          <button class="icon-btn back" aria-label="Quay lại" onClick={() => back(props.backTo!)}><Icon name="chevronLeft" /></button>
        )}
        <div class="grow">
          <h1>{props.title}</h1>
          {props.sub && <p class="muted">{props.sub}</p>}
        </div>
      </div>
      <div class="row">{props.right}</div>
    </header>
  );
}

export function Section(props: { title: string; count?: number; tone?: "warn" | "danger"; children: ComponentChildren; action?: ComponentChildren }) {
  return (
    <section class="block">
      <h3 class={props.tone ?? ""}>
        <span>{props.title}</span>
        {props.count !== undefined && <span class="count">{props.count}</span>}
        {props.action && <span class="push">{props.action}</span>}
      </h3>
      {props.children}
    </section>
  );
}

export const Empty = ({ children }: { children: ComponentChildren }) => <p class="empty">{children}</p>;

export function Field(props: { label: string; hint?: string; children: ComponentChildren }) {
  return (
    <label>
      {props.label}
      {props.children}
      {props.hint && <small class="hint">{props.hint}</small>}
    </label>
  );
}

export function IconButton(props: { icon: IconName; label: string; onClick: () => void; small?: boolean }) {
  return <button class="icon-btn" aria-label={props.label} onClick={props.onClick}><Icon name={props.icon} size={props.small ? 18 : 22} /></button>;
}

/** Bottom sheet. `level` 2 sits above another sheet (a picker opened from a form). */
export function Sheet(props: { title: string; onClose: () => void; children: ComponentChildren; level?: 1 | 2 }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && props.onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [props.onClose]);
  return (
    <div class={`sheet-backdrop ${props.level === 2 ? "l2" : ""}`} onClick={(e) => e.target === e.currentTarget && props.onClose()}>
      <div class="sheet" role="dialog" aria-modal="true" aria-label={props.title}>
        <header>
          <h2>{props.title}</h2>
          <button class="icon-btn" aria-label="Đóng" onClick={props.onClose}><Icon name="close" /></button>
        </header>
        {props.children}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- confirmation

interface Ask {
  title: string;
  body?: string;
  ok: string;
  danger?: boolean;
  done: (yes: boolean) => void;
}
const asking = createStore<Ask | null>(null);

/** "Bạn chắc chứ?" in plain words. Resolves true only when the person confirms. */
export function confirmDialog(o: { title: string; body?: string; ok: string; danger?: boolean }): Promise<boolean> {
  return new Promise((resolve) => asking.set({ ...o, done: resolve }));
}

export function ConfirmHost() {
  const a = useStore(asking);
  if (!a) return null;
  const close = (yes: boolean) => {
    asking.set(null);
    a.done(yes);
  };
  return (
    <div class="sheet-backdrop l3" onClick={(e) => e.target === e.currentTarget && close(false)}>
      <div class="sheet confirm" role="alertdialog" aria-modal="true" aria-label={a.title}>
        <h2>{a.title}</h2>
        {a.body && <p class="muted">{a.body}</p>}
        <div class="row gap">
          <button class="btn ghost grow" onClick={() => close(false)}>Không</button>
          <button class={`btn grow ${a.danger ? "danger-solid" : "solid"}`} onClick={() => close(true)}>{a.ok}</button>
        </div>
      </div>
    </div>
  );
}
