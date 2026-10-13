import { whenLabel } from "../lib/format";
import { formatVnd } from "../lib/money";
import { RECURRENCE_LABEL, type Item } from "../lib/model";
import { CustomerChip, LicenseChip } from "./common";
import { useAudiences, useReceivedMap } from "./hooks";
import { Icon, type IconName } from "./icons";
import { AudienceChip, openShared, OwnerChip } from "./Sharing";

export const TYPE_ICON: Record<Item["type"], IconName> = { TASK: "task", EVENT: "calendar", EXPENSE: "coin", NOTE: "note" };

export function ItemRow(props: {
  item: Item;
  onOpen: (i: Item) => void;
  onToggle?: (i: Item, done: boolean) => void;
  showDate?: boolean;
  hideCustomer?: boolean;
  /** Timeline layout: the time in a column of its own ("09:30", "Cả ngày"). */
  time?: string;
  /** Already over (a past appointment today): shown dimmed. */
  past?: boolean;
}) {
  const { item, onToggle } = props;
  // Something another person shared with me: read-only, labelled with who, opens the read-only view.
  const share = useReceivedMap().get(item.id);
  const audience = useAudiences().get(item.id)?.length ?? 0;
  const onOpen = share ? () => openShared(share.id) : props.onOpen;
  const done = item.status === "DONE";
  // Tasks and appointments can both be ticked off; expenses and notes cannot (nor anything that is not mine).
  const tickable = (item.type === "TASK" || item.type === "EVENT") && !!onToggle && !share;
  const meta = [
    props.showDate === false ? null : whenLabel(item),
    item.place,
    item.person,
    item.type === "EXPENSE" && item.category ? item.category : null,
  ].filter(Boolean);
  return (
    <li class={`row-item ${done ? "done" : ""} ${props.time !== undefined ? "tl" : ""} ${props.past ? "past" : ""} ${share ? "shared-in" : ""} t-${item.type}`}>
      {props.time !== undefined && <span class="when">{props.time}</span>}
      {tickable ? (
        <button
          type="button"
          class={`check ${done ? "on" : ""} ${item.type === "EVENT" ? "ev" : ""}`}
          role="checkbox"
          aria-checked={done}
          aria-label={done ? "Đánh dấu chưa xong" : "Đánh dấu đã xong"}
          onClick={() => onToggle!(item, !done)}
        >
          {done ? <Icon name="check" size={16} /> : item.type === "EVENT" ? <Icon name="calendar" size={14} /> : null}
        </button>
      ) : (
        <span class={`badge t-${item.type}`}><Icon name={TYPE_ICON[item.type]} size={18} /></span>
      )}
      <button class="row-main" onClick={() => onOpen(item)}>
        <span class="title">{item.important && <span class="flag" aria-label="Quan trọng">❗ </span>}{item.title}</span>
        {(meta.length > 0 || item.recurrence) && (
          <span class="meta">
            {meta.join(" · ")}
            {item.recurrence && <span class="rep"> <Icon name="repeat" size={12} /> {RECURRENCE_LABEL[item.recurrence]}</span>}
          </span>
        )}
      </button>
      {item.type === "EXPENSE" && item.amount !== undefined && <span class="amount">{formatVnd(item.amount)}</span>}
      {((!props.hideCustomer && item.customerId) || item.licenseId || share || audience > 0) && (
        <div class="links">
          {share && <OwnerChip share={share} />}
          {!share && audience > 0 && <AudienceChip item={item} count={audience} />}
          {!props.hideCustomer && <CustomerChip id={item.customerId} />}
          <LicenseChip id={item.licenseId} />
        </div>
      )}
    </li>
  );
}
