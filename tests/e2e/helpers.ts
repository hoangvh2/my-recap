import { expect, type Page, type APIRequestContext } from "@playwright/test";

export const OWNER = "owner@example.com";
export const WIFE = "wife@example.com";
export const STRANGER = "stranger@gmail.com";

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

export async function textCapture(page: Page, text: string) {
  await page.getByRole("button", { name: "Gõ ghi chú" }).click();
  await page.getByPlaceholder(/họp anh Nam/).fill(text);
  await page.getByRole("button", { name: "Gửi" }).click();
}

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
