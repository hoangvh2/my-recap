import type { ComponentChildren } from "preact";

const paths: Record<string, ComponentChildren> = {
  mic: <><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3" /></>,
  plus: <path d="M12 5v14M5 12h14" />,
  check: <path d="M5 12l5 5 9-10" />,
  trash: <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4M16 3v4" /></>,
  coin: <><circle cx="12" cy="12" r="9" /><path d="M14.5 9.5c-.4-.9-1.400-1.500-2.500-1.500-1.400 0-2.500.8-2.500 2s1 1.700 2.500 2 2.500.8 2.500 2-1.100 2-2.500 2c-1.100 0-2.100-.6-2.500-1.500M12 6v2M12 16v2" /></>,
  note: <><path d="M6 3h8l5 5v13H6z" /><path d="M14 3v5h5M9 13h6M9 17h6" /></>,
  task: <><circle cx="12" cy="12" r="9" /><path d="M8 12l3 3 5-6" /></>,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  search: <><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></>,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  download: <path d="M12 4v11m0 0l-4-4m4 4l4-4M5 20h14" />,
  pencil: <path d="M4 20l1-4L16 5l3 3L8 19z" />,
  repeat: <path d="M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4" />,
  logout: <path d="M10 4H5v16h5M15 8l4 4-4 4M19 12H9" />,
  stop: <rect x="6" y="6" width="12" height="12" rx="2" />,
  chevronLeft: <path d="M15 5l-7 7 7 7" />,
  chevronRight: <path d="M9 5l7 7-7 7" />,
  lock: <><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></>,
};

export type IconName = keyof typeof paths;

export function Icon({ name, size = 22 }: { name: IconName; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}
