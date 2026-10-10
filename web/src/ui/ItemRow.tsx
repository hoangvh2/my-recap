import { whenLabel } from "../lib/format";
import { formatVnd } from "../lib/money";
import { RECURRENCE_LABEL, type Item } from "../lib/model";
import { CustomerChip, LicenseChip } from "./common";
import { Icon, type IconName } from "./icons";

export const TYPE_ICON: Record<Item["type"], IconName> = { TASK: "task", EVENT: "calendar", EXPENSE: "coin", NOTE: "note" };

export function ItemRow(props: { item: Item; onOpen: (i: Item) => void; onToggle?: (i: Item, done: boolean) => void; showDate?: boolean; hideCustomer?: boolean }) {
  const { item, onOpen, onToggle } = props;
  const done = item.status === "DONE";
  const meta = [
    props.showDate === false ? null : whenLabel(item),
    item.place,
    item.person,
    item.type === "EXPENSE" && item.category ? item.category : null,
  ].filter(Boolean);
  return (
    <li class={`row-item ${done ? "done" : ""}`}>
      {item.type === "TASK" && onToggle ? (
        <button
          class={`check ${done ? "on" : ""}`}
          role="checkbox"
          aria-checked={done}
          aria-label={done ? "Đánh dấu chưa xong" : "Đánh dấu đã xong"}
          onClick={() => onToggle(item, !done)}
        >
          {done && <Icon name="check" size={16} />}
        </button>
      ) : (
        <span class={`badge t-${item.type}`}><Icon name={TYPE_ICON[item.type]} size={18} /></span>
      )}
      <button class="row-main" onClick={() => onOpen(item)}>
        <span class="title">{item.title}</span>
        {(meta.length > 0 || item.recurrence) && (
          <span class="meta">
            {meta.join(" · ")}
            {item.recurrence && <span class="rep"> <Icon name="repeat" size={12} /> {RECURRENCE_LABEL[item.recurrence]}</span>}
          </span>
        )}
      </button>
      {item.type === "EXPENSE" && item.amount !== undefined && <span class="amount">{formatVnd(item.amount)}</span>}
      {((!props.hideCustomer && item.customerId) || item.licenseId) && (
        <div class="links">
          {!props.hideCustomer && <CustomerChip id={item.customerId} />}
          <LicenseChip id={item.licenseId} />
        </div>
      )}
    </li>
  );
}
