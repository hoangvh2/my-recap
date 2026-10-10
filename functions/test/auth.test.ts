import { describe, expect, it } from "vitest";
import { isAllowedEmail, isAllowedToken, parseAllowedEmails } from "../src/auth";

const allowed = parseAllowedEmails("Me@Gmail.com, wife@gmail.com ; junk, a@b");
const good = { email: "me@gmail.com", email_verified: true, firebase: { sign_in_provider: "google.com" } };

describe("allowlist", () => {
  it("normalises the configured list and drops non-addresses", () => {
    expect(allowed).toEqual(["me@gmail.com", "wife@gmail.com"]);
    expect(parseAllowedEmails(undefined)).toEqual([]);
    expect(parseAllowedEmails("")).toEqual([]);
  });
  it("admits a verified Google account on the list, whatever its letter case", () => {
    expect(isAllowedToken(good, allowed)).toBe(true);
    expect(isAllowedToken({ ...good, email: "WIFE@gmail.COM" }, allowed)).toBe(true);
  });
  it("rejects everyone else", () => {
    expect(isAllowedToken(undefined, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, email: "stranger@gmail.com" }, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, email_verified: false }, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, email_verified: undefined }, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, firebase: { sign_in_provider: "password" } }, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, firebase: undefined }, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, email: undefined }, allowed)).toBe(false);
  });
  it("fails closed when nothing is configured", () => {
    expect(isAllowedToken(good, [])).toBe(false);
    expect(isAllowedEmail("me@gmail.com", true, [])).toBe(false);
  });
  it("does not match on substrings or look-alikes", () => {
    expect(isAllowedToken({ ...good, email: "me@gmail.com.evil.io" }, allowed)).toBe(false);
    expect(isAllowedToken({ ...good, email: "xme@gmail.com" }, allowed)).toBe(false);
    expect(isAllowedEmail("me@gmail.com", false, allowed)).toBe(false);
    expect(isAllowedEmail("me@gmail.com", true, allowed)).toBe(true);
  });
});
