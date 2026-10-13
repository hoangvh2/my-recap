import { httpsCallable } from "firebase/functions";
import { functions } from "./firebase";
import type { CaptureContext } from "./lib/context";

export interface CaptureResult {
  outcome: "saved" | "saved_as_note" | "no_speech";
  captureId?: string;
  itemCount: number;
  customerCount: number;
  licenseCount: number;
  proposalCount: number;
}

type Payload = { zone: string; context: CaptureContext; text?: string; audioBase64?: string };

// Single-use App Check tokens: a token sniffed from one request cannot be replayed on another.
const call = httpsCallable<Payload, CaptureResult>(functions, "capture", { timeout: 180_000, limitedUseAppCheckTokens: true });

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Ho_Chi_Minh";

export class ApiError extends Error {
  constructor(message: string, readonly retryable: boolean) {
    super(message);
  }
}

/** Sends a typed note or a recorded WAV to the capture function. */
export async function capture(input: { text: string } | { audioBase64: string }, context: CaptureContext): Promise<CaptureResult> {
  try {
    return (await call({ ...input, zone: zone(), context })).data;
  } catch (e) {
    throw toApiError(e);
  }
}

type FeedAction = "get" | "rotate" | "revoke";
const feedCall = httpsCallable<{ action: FeedAction; zone: string }, { token: string | null }>(functions, "calendarlink", { timeout: 30_000, limitedUseAppCheckTokens: true });

/** The secret calendar link: show it, replace it (the old one stops), or switch it off. Null token = no link. */
export async function calendarToken(action: FeedAction): Promise<string | null> {
  try {
    return (await feedCall({ action, zone: zone() })).data.token;
  } catch (e) {
    throw toApiError(e);
  }
}

/** Address a phone calendar subscribes to. Same host as the app, which Hosting forwards to the feed function. */
export const feedUrl = (token: string, scheme: "https" | "webcal" = "https"): string => `${scheme === "https" ? location.protocol.replace(":", "") : scheme}://${location.host}/calendar/${token}.ics`;

export interface NotifyForm {
  pushoverUser: string;
  device?: string;
  zone: string;
  morning: number;
  evening: number;
  quietFrom: number;
  quietTo: number;
  lead: number;
  calendarAlarms: boolean;
}
type NotifyRequest = { action: "save"; settings: NotifyForm } | { action: "test" } | { action: "off" };
const notifyCall = httpsCallable<NotifyRequest, { enabled: boolean }>(functions, "notifysettings", { timeout: 30_000, limitedUseAppCheckTokens: true });

/** Save notification settings (the server checks the Pushover key first), send a test, or switch off. */
export async function notify(req: NotifyRequest): Promise<{ enabled: boolean }> {
  try {
    return (await notifyCall(req)).data;
  } catch (e) {
    // This function's own refusals ("not set up yet", "Pushover refused") come as failed-precondition with a message.
    const code = (e as { code?: string } | null)?.code ?? "";
    const msg = (e as { message?: string } | null)?.message ?? "";
    if (code === "functions/failed-precondition" && msg && !/app check/i.test(msg) && msg !== "failed-precondition") throw new ApiError(msg, false);
    throw toApiError(e);
  }
}

export const deviceZone = zone;

type MembersRequest = { action: "list" } | { action: "add"; email: string } | { action: "remove"; email: string };
export interface MembersState { admin: boolean; admins: string[]; members: { email: string; addedBy: string; addedAt: number }[] }
const membersCall = httpsCallable<MembersRequest, MembersState>(functions, "members", { timeout: 30_000 });

/** Administrators add or remove who may use the app. Anyone else gets `{ admin: false }` for "list". */
export async function manageMembers(req: MembersRequest): Promise<MembersState> {
  try {
    return (await membersCall(req)).data;
  } catch (e) {
    const msg = (e as { message?: string } | null)?.message ?? "";
    if (msg && !/^[a-z-]+$/.test(msg)) throw new ApiError(msg, false);
    throw toApiError(e);
  }
}

export function toApiError(e: unknown): ApiError {
  const code = (e as { code?: string } | null)?.code ?? "";
  const msg = (e as { message?: string } | null)?.message ?? "";
  switch (code) {
    case "functions/unauthenticated":
      return new ApiError("Phiên đăng nhập đã hết hạn, hãy đăng nhập lại", false);
    case "functions/permission-denied":
      return new ApiError("Tài khoản này chưa được cấp quyền", false);
    case "functions/resource-exhausted":
    case "functions/invalid-argument":
      return new ApiError(msg || "Yêu cầu bị từ chối", false);
    case "functions/failed-precondition":
      return new ApiError("Ứng dụng chưa được xác thực (App Check). Tải lại trang rồi thử lại", true);
    case "functions/unavailable":
      return new ApiError(msg && !/^(unavailable|internal)$/i.test(msg) ? msg : "Không kết nối được, kiểm tra mạng rồi thử lại", true);
    case "functions/deadline-exceeded":
      return new ApiError("Xử lý quá lâu, thử lại", true);
    default:
      return new ApiError("Có lỗi khi xử lý ghi nhanh, thử lại", true);
  }
}
