/** Hard limits that keep one capture cheap and bounded, whoever calls it. */
export const LIMITS = {
  /** Captures (voice or text) one account may make per UTC day. */
  dailyCaptures: 60,
  /** Largest WAV the function accepts (16 kHz, 16-bit, mono ≈ 32 kB/s → 100 s ≈ 3.2 MB). */
  maxAudioBytes: 3_600_000,
  maxAudioSeconds: 100,
  minAudioSeconds: 0.3,
  maxTextChars: 2_000,
  maxTranscriptChars: 4_000,
  maxItems: 20,
} as const;

export const EXPENSE_CATEGORIES = [
  "Ăn uống", "Di chuyển", "Mua sắm", "Hoá đơn", "Sức khoẻ", "Giải trí", "Giáo dục", "Gia đình", "Công việc", "Khác",
] as const;

export const NO_SPEECH = "[không có lời nói]";

/** Same default as the Android app; override with the GEMINI_MODEL parameter. */
export const DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite";
export const GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

/** Region of the callable. Must match the web app's `getFunctions(app, region)`. */
export const REGION = "asia-southeast1";

/**
 * Browser origins allowed to call the function: the two default Firebase Hosting hosts plus an
 * optional custom domain. Entries may be bare hostnames or https URLs; anything else is dropped.
 */
export function allowedOrigins(project: string, extra: string | undefined): string[] {
  const origins = [`https://${project}.web.app`, `https://${project}.firebaseapp.com`];
  for (const raw of (extra ?? "").split(/[\s,;]+/)) {
    const host = raw.trim().toLowerCase().replace(/^https:\/\//, "").replace(/\/$/, "");
    if (/^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(host)) origins.push(`https://${host}`);
  }
  return [...new Set(origins)];
}
