import { getFirestore } from "firebase-admin/firestore";
import { defineString } from "firebase-functions/params";
import { HttpsError } from "firebase-functions/v2/https";
import { beforeUserCreated, beforeUserSignedIn } from "firebase-functions/v2/identity";
import { isAllowedEmail, parseAllowedEmails } from "./auth";
import { REGION } from "./config";
import { FirestoreMembersStore } from "./members";

const ALLOWED_EMAILS = defineString("ALLOWED_EMAILS");

/** On the fixed list, or added by an administrator in the app. */
async function check(email: string | undefined, verified: boolean | undefined): Promise<void> {
  if (isAllowedEmail(email, verified, parseAllowedEmails(ALLOWED_EMAILS.value()))) return;
  const e = email?.trim().toLowerCase();
  if (verified === true && e && (await new FirestoreMembersStore(getFirestore()).has(e))) return;
  throw new HttpsError("permission-denied", "Tài khoản này chưa được cấp quyền");
}

export const beforeusercreated = beforeUserCreated({ region: REGION }, (event) => check(event.data?.email, event.data?.emailVerified));

export const beforeusersignedin = beforeUserSignedIn({ region: REGION }, (event) => check(event.data?.email, event.data?.emailVerified));
