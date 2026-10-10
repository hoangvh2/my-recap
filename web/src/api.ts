import { httpsCallable } from "firebase/functions";
import { functions } from "./firebase";

export interface CaptureResult {
  outcome: "saved" | "saved_as_note" | "no_speech";
  captureId?: string;
  itemCount: number;
}

type Payload = { zone: string; text?: string; audioBase64?: string };

// Single-use App Check tokens: a token sniffed from one request cannot be replayed on another.
const call = httpsCallable<Payload, CaptureResult>(functions, "capture", { timeout: 180_000, limitedUseAppCheckTokens: true });

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Ho_Chi_Minh";

export class ApiError extends Error {
  constructor(message: string, readonly retryable: boolean) {
    super(message);
  }
}

/** Sends a typed note or a recorded WAV to the capture function. */
export async function capture(input: { text: string } | { audioBase64: string }): Promise<CaptureResult> {
  try {
    return (await call({ ...input, zone: zone() })).data;
  } catch (e) {
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
