import { expect, type Page, type APIRequestContext } from "@playwright/test";

export const OWNER = "owner@example.com";
export const MEMBER = "member@example.com";
export const STRANGER = "stranger@example.com";

declare global {
  interface Window {
    __e2eSignIn: (email: string) => Promise<void>;
  }
}

/** Collects problems the browser reports, so a test can assert there were none. */
export function watchBrowser(page: Page) {
  const problems: string[] = [];
  page.on("console", (m) => {
    const t = m.text();
    if (/Content Security Policy|Refused to/i.test(t)) problems.push(`CSP: ${t}`);
  });
  page.on("pageerror", (e) => problems.push(`pageerror: ${e.message}`));
  return problems;
}

export async function signIn(page: Page, email: string) {
  await page.goto("/");
  await page.waitForFunction(() => typeof window.__e2eSignIn === "function");
  await page.evaluate((e) => window.__e2eSignIn(e), email);
}

/** Calls Gemini's recorded traffic (what the fake received). */
export async function geminiCalls(request: APIRequestContext) {
  return (await (await request.get("http://127.0.0.1:8788/")).json()) as Array<{
    key?: string; kind: "transcribe" | "extract"; audioMime?: string; audioBytes: number; audioHead: string; userText: string;
  }>;
}

/** Types a note into whichever Ghi nhanh box is open (the one on Today, or the sheet from the microphone). */
export async function textCapture(page: Page, text: string) {
  await page.getByRole("button", { name: "Gõ ghi chú" }).click();
  await page.getByPlaceholder(/gọi anh Nam bên ABC/).fill(text);
  await page.getByRole("button", { name: "Gửi", exact: true }).click();
}

/** Opens a tab of the bottom bar. */
export const tab = (page: Page, name: "Hôm nay" | "Khách" | "Gia hạn" | "Việc") => page.locator(".tabbar").getByText(name, { exact: true }).click();

/** yyyy-mm-dd, `days` from today (local time, like the app). */
export function isoIn(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

type Plain = string | number | boolean | Plain[] | { [k: string]: Plain };
function toValue(v: Plain): unknown {
  if (typeof v === "string") return { stringValue: v };
  if (typeof v === "boolean") return { booleanValue: v };
  if (typeof v === "number") return { integerValue: String(v) };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(toValue) } };
  return { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, toValue(x)])) } };
}

/** Writes one document as the signed-in account, through the same rules the app is under. */
export async function seed(request: APIRequestContext, who: { idToken: string; uid: string }, collection: string, id: string, data: Record<string, Plain>) {
  const res = await request.patch(`http://127.0.0.1:8080/v1/projects/demo-myrecap/databases/(default)/documents/users/${who.uid}/${collection}/${id}`, {
    headers: { authorization: `Bearer ${who.idToken}` },
    data: { fields: Object.fromEntries(Object.entries({ id, ...data }).map(([k, v]) => [k, toValue(v)])) },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
}

export const NOW = () => Date.now();

export const expectNoProblems = (problems: string[]) => expect(problems, problems.join("\n")).toEqual([]);

/** An ID token for a fake Google account, straight from the Auth emulator. */
export async function idTokenFor(request: APIRequestContext, email: string): Promise<{ idToken: string; uid: string }> {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const fake = `${b64({ alg: "none", typ: "JWT" })}.${b64({ sub: `sub-${email}`, email, email_verified: true })}.`;
  const res = await request.post("http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=fake", {
    data: { requestUri: "http://localhost", postBody: `id_token=${fake}&providerId=google.com`, returnSecureToken: true, returnIdpCredential: true },
  });
  expect(res.ok()).toBeTruthy();
  const j = await res.json();
  return { idToken: j.idToken, uid: j.localId };
}
