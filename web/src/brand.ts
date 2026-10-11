/**
 * The VHub signature, kept in one place. VHub is the family of personal apps under vhub.id.vn; VHub Desk
 * (desk.vhub.id.vn) is the work desk: tasks, appointments, reminders, customers and licence renewals.
 */
declare const __APP_VERSION__: string;

export const BRAND = {
  family: "VHub",
  app: "VHub Desk",
  tagline: "Việc, lịch hẹn, nhắc việc và gia hạn license",
  domain: "vhub.id.vn",
  version: typeof __APP_VERSION__ === "string" ? __APP_VERSION__ : "dev",
  year: 2026,
} as const;

/** "© 2026 VHub" (or "© 2026–2027 VHub" in later years). */
export function copyright(now = new Date()): string {
  const y = now.getFullYear();
  return `© ${y > BRAND.year ? `${BRAND.year}–${y}` : BRAND.year} ${BRAND.family}`;
}
