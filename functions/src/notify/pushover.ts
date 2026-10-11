import type { Message } from "./plan";

export const PUSHOVER_BASE_URL = "https://api.pushover.net";

/** How a send ended: delivered, worth retrying later (network, 5xx, 429), or refused for good (bad key, 4xx). */
export type SendResult = { ok: true; receipt?: string } | { ok: false; retry: boolean; status?: number };

export interface Sender {
  send(user: string, device: string | undefined, m: Message): Promise<SendResult>;
  validate(user: string, device: string | undefined): Promise<SendResult>;
  /** Stops an emergency message from repeating. */
  cancel(receipt: string): Promise<SendResult>;
}

/** Emergency messages repeat every 5 minutes for at most an hour, until acknowledged. */
export const EMERGENCY = { retry: 300, expire: 3600 } as const;

/**
 * Pushover over its REST API (form-encoded POST). The application token lives in Secret Manager;
 * the user key is the owner's own. Message text is never logged.
 */
export class PushoverClient implements Sender {
  constructor(
    private readonly token: string,
    private readonly baseUrl: string = PUSHOVER_BASE_URL,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly timeoutMs = 10_000,
  ) {}

  send(user: string, device: string | undefined, m: Message): Promise<SendResult> {
    const form: Record<string, string> = {
      token: this.token,
      user,
      title: m.title,
      message: m.body,
      html: "1",
      priority: String(m.priority),
      timestamp: String(Math.floor(m.at / 1000)),
    };
    if (device) form.device = device;
    if (m.url) {
      form.url = m.url;
      if (m.urlTitle) form.url_title = m.urlTitle;
    }
    if (m.priority === 2) {
      form.retry = String(EMERGENCY.retry);
      form.expire = String(Math.min(EMERGENCY.expire, Math.max(60, m.expireSec ?? EMERGENCY.expire)));
      form.sound = "persistent";
    } else if (m.ttlSec) {
      form.ttl = String(m.ttlSec);
    }
    return this.post("/1/messages.json", form);
  }

  cancel(receipt: string): Promise<SendResult> {
    if (!/^[A-Za-z0-9]{1,64}$/.test(receipt)) return Promise.resolve({ ok: false, retry: false });
    return this.post(`/1/receipts/${receipt}/cancel.json`, { token: this.token });
  }

  validate(user: string, device: string | undefined): Promise<SendResult> {
    return this.post("/1/users/validate.json", { token: this.token, user, ...(device ? { device } : {}) });
  }

  private async post(path: string, form: Record<string, string>): Promise<SendResult> {
    try {
      const res = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(form).toString(),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      if (res.ok) {
        const body = (await res.json().catch(() => ({}))) as { status?: number; receipt?: unknown };
        if (body.status !== 1) return { ok: false, retry: false, status: res.status };
        return typeof body.receipt === "string" ? { ok: true, receipt: body.receipt } : { ok: true };
      }
      // Pushover: 4xx means the request itself is wrong (do not repeat it); 429 and 5xx are temporary.
      return { ok: false, retry: res.status === 429 || res.status >= 500, status: res.status };
    } catch {
      return { ok: false, retry: true };
    }
  }
}
