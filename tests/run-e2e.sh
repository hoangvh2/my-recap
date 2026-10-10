#!/usr/bin/env bash
# Builds everything against the Firebase emulators and runs the end-to-end suite:
# Auth + Firestore + Functions emulators, a fake Gemini, the real web build under production headers.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"

export ALLOWED_EMAILS="owner@example.com,wife@example.com"
node "$ROOT/scripts/configure.mjs"
(cd "$ROOT/functions" && npm run build >/dev/null)
printf 'GEMINI_API_KEY=fake-e2e-key\n' > "$ROOT/functions/.secret.local"

VITE_USE_EMULATORS=true VITE_FIREBASE_API_KEY=fake-key VITE_FIREBASE_PROJECT_ID=demo-myrecap VITE_FIREBASE_APP_ID=1:1:web:e2e \
  npm --prefix "$ROOT/web" run build -- --outDir dist-e2e >/dev/null

export GEMINI_BASE_URL_OVERRIDE="http://127.0.0.1:8787"
npx firebase emulators:exec --config "$ROOT/firebase.json" --only auth,firestore,functions --project demo-myrecap "npx playwright test $*"
