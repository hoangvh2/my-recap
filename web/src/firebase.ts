import { initializeApp, type FirebaseOptions } from "firebase/app";
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "firebase/app-check";
import { connectAuthEmulator, getAuth } from "firebase/auth";
import { connectFirestoreEmulator, initializeFirestore, memoryLocalCache } from "firebase/firestore";
import { connectFunctionsEmulator, getFunctions } from "firebase/functions";

const env = import.meta.env;

/**
 * These values identify the project; they are not secrets (Firebase security comes from the
 * rules, the allowlist and App Check, not from hiding them). The Gemini key is NOT here: it lives
 * in Secret Manager and only the capture function can read it.
 */
const options: FirebaseOptions = {
  apiKey: env.VITE_FIREBASE_API_KEY,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  appId: env.VITE_FIREBASE_APP_ID,
  // Same origin as the app, so the sign-in redirect keeps working on Safari/iOS, which blocks the
  // third-party storage a different authDomain would need.
  authDomain: location.host,
};

export const region: string = env.VITE_FUNCTIONS_REGION || "asia-southeast1";

export const app = initializeApp(options);

if (env.VITE_APPCHECK_SITE_KEY) {
  if (env.DEV) (self as unknown as { FIREBASE_APPCHECK_DEBUG_TOKEN: boolean }).FIREBASE_APPCHECK_DEBUG_TOKEN = true;
  initializeAppCheck(app, {
    provider: new ReCaptchaEnterpriseProvider(env.VITE_APPCHECK_SITE_KEY),
    isTokenAutoRefreshEnabled: true,
  });
}

export const auth = getAuth(app);
// Memory cache only: nothing the secretary knows is written to IndexedDB/localStorage by Firestore.
export const db = initializeFirestore(app, { localCache: memoryLocalCache() });
export const functions = getFunctions(app, region);

// Written as a literal comparison so the build removes this block entirely from production bundles.
if (import.meta.env.VITE_USE_EMULATORS === "true") {
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
}
