#!/usr/bin/env node
// Builds the two deploy-time files that contain the allowlist, from one source of truth:
//   firestore.rules      (from firestore.rules.tmpl)
//   functions/.env       (ALLOWED_EMAILS, BLOCK_UNLISTED_SIGNUPS)
// The list comes from the ALLOWED_EMAILS environment variable (a GitHub Actions secret in CI) or,
// locally, from config/allowed-emails.local (gitignored). It is never committed: the repo is public.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

export function parseEmails(raw) {
  const emails = [...new Set(raw.split(/[\s,;]+/).map((e) => e.trim().toLowerCase()).filter(Boolean))];
  // Strict on purpose: whatever passes here is pasted inside a string literal of the rules file.
  const re = /^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$/;
  const bad = emails.filter((e) => !re.test(e));
  if (bad.length) throw new Error(`Not a valid email address: ${bad.join(", ")}`);
  if (emails.length === 0) throw new Error("ALLOWED_EMAILS is empty: refusing to generate a config that admits nobody by accident.");
  return emails;
}

export function renderRules(template, emails) {
  if (!template.includes("__ALLOWED_EMAILS__")) throw new Error("Template has no __ALLOWED_EMAILS__ placeholder");
  return template.replace("__ALLOWED_EMAILS__", emails.map((e) => `'${e}'`).join(", "));
}

export function renderEnv(emails, blockSignups) {
  return `ALLOWED_EMAILS=${emails.join(",")}\nBLOCK_UNLISTED_SIGNUPS=${blockSignups ? "true" : "false"}\n`;
}

function main() {
  const localFile = join(root, "config", "allowed-emails.local");
  const raw = process.env.ALLOWED_EMAILS ?? (existsSync(localFile) ? readFileSync(localFile, "utf8") : "");
  const emails = parseEmails(raw);
  const template = readFileSync(join(root, "firestore.rules.tmpl"), "utf8");
  writeFileSync(join(root, "firestore.rules"), renderRules(template, emails));
  writeFileSync(join(root, "functions", ".env"), renderEnv(emails, process.env.BLOCK_UNLISTED_SIGNUPS === "true"));
  console.log(`Configured ${emails.length} allowed account(s).`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (e) {
    console.error(`configure: ${e.message}`);
    process.exit(1);
  }
}
