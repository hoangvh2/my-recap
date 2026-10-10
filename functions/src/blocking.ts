import { defineString } from "firebase-functions/params";
import { HttpsError } from "firebase-functions/v2/https";
import { beforeUserCreated, beforeUserSignedIn } from "firebase-functions/v2/identity";
import { isAllowedEmail, parseAllowedEmails } from "./auth";
import { REGION } from "./config";

const ALLOWED_EMAILS = defineString("ALLOWED_EMAILS");

function check(email: string | undefined, verified: boolean | undefined): void {
  if (!isAllowedEmail(email, verified, parseAllowedEmails(ALLOWED_EMAILS.value()))) {
    throw new HttpsError("permission-denied", "Tài khoản này chưa được cấp quyền");
  }
}

export const beforeusercreated = beforeUserCreated({ region: REGION }, (event) => {
  check(event.data?.email, event.data?.emailVerified);
});

export const beforeusersignedin = beforeUserSignedIn({ region: REGION }, (event) => {
  check(event.data?.email, event.data?.emailVerified);
});
