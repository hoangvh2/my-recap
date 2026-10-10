import { randomUUID, createHash } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { isAllowedToken, parseAllowedEmails } from "./auth";
import { CaptureError, parseCaptureInput, runCapture, type CaptureDeps } from "./capture";
import { DEFAULT_GEMINI_MODEL, GEMINI_BASE_URL, REGION } from "./config";
import { GeminiClient } from "./gemini";
import { FirestoreQuota } from "./usage";

initializeApp();

/** Comma-separated Google addresses, baked in at deploy time from functions/.env (never committed). */
const ALLOWED_EMAILS = defineString("ALLOWED_EMAILS");
const GEMINI_MODEL = defineString("GEMINI_MODEL", { default: DEFAULT_GEMINI_MODEL });
/** Lives in Secret Manager; only this function's service account can read it. */
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

const inEmulator = process.env.FUNCTIONS_EMULATOR === "true";
const project = process.env.GCLOUD_PROJECT ?? "";

/**
 * Voice or text note → Gemini → draft items saved under the caller's own user document.
 *
 * Gate order: App Check (framework) → signed-in → verified Google account on the allowlist →
 * payload validation → daily quota → Gemini. The function offers two fixed operations only; it is
 * not a general Gemini proxy, and the prompts never leave the server.
 */
export const capture = onCall(
  {
    region: REGION,
    secrets: [GEMINI_API_KEY],
    // The emulator has no App Check attestation; every deployed instance enforces it, with
    // single-use tokens so a captured token cannot be replayed.
    enforceAppCheck: !inEmulator,
    consumeAppCheckToken: !inEmulator,
    cors: inEmulator ? true : [`https://${project}.web.app`, `https://${project}.firebaseapp.com`],
    maxInstances: 3,
    concurrency: 4,
    timeoutSeconds: 180,
    memory: "512MiB",
  },
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Cần đăng nhập");
    if (!isAllowedToken(request.auth.token, parseAllowedEmails(ALLOWED_EMAILS.value()))) {
      logger.warn("capture denied", { uid: shortHash(request.auth.uid) });
      throw new HttpsError("permission-denied", "Tài khoản này chưa được cấp quyền");
    }
    const uid = request.auth.uid;
    const started = Date.now();
    try {
      const input = parseCaptureInput(request.data);
      const result = await runCapture(deps(), uid, input);
      // Counts and timings only: transcripts and item text are personal data and stay out of logs.
      logger.info("capture", { uid: shortHash(uid), kind: input.kind, outcome: result.outcome, items: result.itemCount, ms: Date.now() - started });
      return result;
    } catch (e) {
      if (e instanceof CaptureError) {
        logger.warn("capture failed", { uid: shortHash(uid), code: e.code, ms: Date.now() - started });
        throw new HttpsError(e.code, e.message);
      }
      logger.error("capture crashed", { uid: shortHash(uid), error: e instanceof Error ? e.name : "unknown" });
      throw new HttpsError("internal", "Có lỗi khi xử lý ghi nhanh");
    }
  },
);

function deps(): CaptureDeps {
  const db = getFirestore();
  const override = inEmulator ? process.env.GEMINI_BASE_URL_OVERRIDE : undefined;
  return {
    quota: new FirestoreQuota(db),
    llm: new GeminiClient({ apiKey: GEMINI_API_KEY.value(), model: GEMINI_MODEL.value(), baseUrl: override ?? GEMINI_BASE_URL }),
    sink: {
      async save(uid, doc, items) {
        const batch = db.batch();
        batch.set(db.doc(`users/${uid}/captures/${doc.id}`), doc);
        for (const item of items) batch.set(db.doc(`users/${uid}/items/${item.id}`), item);
        await batch.commit();
      },
    },
    now: () => Date.now(),
    newId: () => randomUUID().replaceAll("-", ""),
  };
}

function shortHash(s: string): string {
  return createHash("sha256").update(s).digest("hex").slice(0, 8);
}

// Optional hardening: stop addresses that are not on the list from even creating a sign-in
// record. Needs Identity Platform (see docs/web-secretary.md); off unless BLOCK_UNLISTED_SIGNUPS=true.
if (process.env.BLOCK_UNLISTED_SIGNUPS === "true") {
  Object.assign(module.exports as object, require("./blocking") as object);
}
