import { randomUUID, createHash } from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest, type CallableRequest } from "firebase-functions/v2/https";
import { isAllowedToken, parseAllowedEmails } from "./auth";
import { CaptureError, parseCaptureInput, runCapture, type CaptureDeps } from "./capture";
import { allowedOrigins, DEFAULT_GEMINI_MODEL, GEMINI_BASE_URL, REGION } from "./config";
import { FeedHandler, FeedRequestError, FirestoreFeedStore, manageFeed, parseFeedRequest } from "./feed";
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
    // Default Hosting hosts plus the optional custom domain (EXTRA_ORIGINS, set by Terraform).
    cors: inEmulator ? true : allowedOrigins(project, process.env.EXTRA_ORIGINS),
    maxInstances: 3,
    concurrency: 4,
    timeoutSeconds: 180,
    memory: "512MiB",
  },
  async (request) => {
    const uid = requireAllowed(request, "capture");
    const started = Date.now();
    try {
      const input = parseCaptureInput(request.data);
      const result = await runCapture(deps(), uid, input);
      // Counts and timings only: transcripts and item text are personal data and stay out of logs.
      logger.info("capture", { uid: shortHash(uid), kind: input.kind, outcome: result.outcome, items: result.itemCount, customers: result.customerCount, licenses: result.licenseCount, proposals: result.proposalCount, ms: Date.now() - started });
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

/** Signed in with an allow-listed, verified Google account; returns the uid. */
function requireAllowed(request: CallableRequest, what: string): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Cần đăng nhập");
  if (!isAllowedToken(request.auth.token, parseAllowedEmails(ALLOWED_EMAILS.value()))) {
    logger.warn(`${what} denied`, { uid: shortHash(request.auth.uid) });
    throw new HttpsError("permission-denied", "Tài khoản này chưa được cấp quyền");
  }
  return request.auth.uid;
}

/**
 * The owner's calendar subscription link: show it, replace it (the old one stops working) or switch
 * it off. Returns only the secret token; the app builds the address from its own host.
 */
export const calendarlink = onCall(
  {
    region: REGION,
    enforceAppCheck: !inEmulator,
    consumeAppCheckToken: !inEmulator,
    cors: inEmulator ? true : allowedOrigins(project, process.env.EXTRA_ORIGINS),
    maxInstances: 2,
    concurrency: 8,
    timeoutSeconds: 30,
    memory: "256MiB",
  },
  async (request) => {
    const uid = requireAllowed(request, "calendarlink");
    try {
      const req = parseFeedRequest(request.data);
      const result = await manageFeed(new FirestoreFeedStore(getFirestore()), uid, req);
      logger.info("calendarlink", { uid: shortHash(uid), action: req.action });
      return result;
    } catch (e) {
      if (e instanceof FeedRequestError) throw new HttpsError("invalid-argument", e.message);
      logger.error("calendarlink crashed", { uid: shortHash(uid), error: e instanceof Error ? e.name : "unknown" });
      throw new HttpsError("internal", "Không tạo được liên kết lịch");
    }
  },
);

let feedHandler: FeedHandler | undefined;

/**
 * Public by design: phone calendar apps cannot sign in. Access is the secret token in the address
 * (/calendar/<token>.ics); wrong addresses answer 404 and nothing is logged about them.
 */
export const calendarfeed = onRequest(
  { region: REGION, cors: false, maxInstances: 2, concurrency: 20, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    feedHandler ??= new FeedHandler(new FirestoreFeedStore(getFirestore()));
    res.set({ "Cache-Control": "private, no-store", "X-Robots-Tag": "noindex", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff" });
    try {
      const r = await feedHandler.handle(req.method, req.path);
      if (r.status !== 200) {
        res.status(r.status).end();
        return;
      }
      res.status(200).type("text/calendar; charset=utf-8").set("Content-Disposition", 'inline; filename="thu-ky.ics"');
      res.send(req.method === "HEAD" ? undefined : r.body);
    } catch (e) {
      logger.error("calendarfeed crashed", { error: e instanceof Error ? e.name : "unknown" });
      res.status(500).end();
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
      async save(uid, doc, bundle) {
        const batch = db.batch();
        batch.set(db.doc(`users/${uid}/captures/${doc.id}`), doc);
        for (const item of bundle.items) batch.set(db.doc(`users/${uid}/items/${item.id}`), item);
        for (const c of bundle.customers) batch.set(db.doc(`users/${uid}/customers/${c.id}`), c);
        for (const l of bundle.licenses) batch.set(db.doc(`users/${uid}/licenses/${l.id}`), l);
        for (const p of bundle.proposals) batch.set(db.doc(`users/${uid}/proposals/${p.id}`), p);
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
