#!/usr/bin/env node
// Renders the PWA icons from one SVG with headless Chromium (Playwright from tests/).
// Usage: CHROMIUM=/path/to/chrome node scripts/render-icons.mjs
import { mkdirSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(join(root, "tests", "package.json"));
const { chromium } = require("@playwright/test");

const svg = (size) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 512 512">
  <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1a237e"/><stop offset="1" stop-color="#3949ab"/></linearGradient></defs>
  <rect width="512" height="512" fill="url(#g)"/>
  <g fill="none" stroke="#fff" stroke-width="28" stroke-linecap="round" stroke-linejoin="round">
    <rect x="203" y="110" width="106" height="170" rx="53"/>
    <path d="M148 244a108 108 0 0 0 216 0M256 352v50M206 402h100"/>
  </g>
</svg>`;

const out = join(root, "web", "public", "icons");
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined });
const page = await browser.newPage();
for (const [name, size] of [["icon-192.png", 192], ["icon-512.png", 512], ["apple-touch-icon.png", 180]]) {
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(`<body style="margin:0">${svg(size)}</body>`);
  writeFileSync(join(out, name), await page.screenshot({ omitBackground: false }));
}
await browser.close();
console.log("icons written to", out);
