import { useEffect } from "preact/hooks";
import { createStore, useStore } from "../lib/store";

interface ToastState {
  id: number;
  message: string;
  action?: { label: string; run: () => void };
}

const toastStore = createStore<ToastState | null>(null);
let counter = 0;

export function showToast(message: string, action?: ToastState["action"]): void {
  toastStore.set({ id: ++counter, message, action });
}

export function Toast() {
  const t = useStore(toastStore);
  useEffect(() => {
    if (!t) return;
    const timer = setTimeout(() => toastStore.set((cur) => (cur?.id === t.id ? null : cur)), 6_000);
    return () => clearTimeout(timer);
  }, [t?.id]);
  if (!t) return null;
  return (
    <div class="toast" role="status" aria-live="polite">
      <span>{t.message}</span>
      {t.action && (
        <button
          class="link"
          onClick={() => {
            t.action?.run();
            toastStore.set(null);
          }}
        >
          {t.action.label}
        </button>
      )}
    </div>
  );
}
