// Test-only hook, compiled in only when the app is built against the Firebase emulators
// (VITE_USE_EMULATORS=true). Production builds never include this file; CI checks for that.
import { GoogleAuthProvider, signInWithCredential } from "firebase/auth";
import { auth } from "./firebase";

const b64url = (o: object) => btoa(JSON.stringify(o)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");

/** The emulator accepts unsigned tokens as a stand-in for "the user signed in with Google". */
(window as unknown as { __e2eSignIn: (email: string) => Promise<void> }).__e2eSignIn = async (email) => {
  const idToken = `${b64url({ alg: "none", typ: "JWT" })}.${b64url({ sub: `sub-${email}`, email, email_verified: true })}.`;
  await signInWithCredential(auth, GoogleAuthProvider.credential(idToken));
};
