import { readFileSync } from "node:fs";
import preact from "@preact/preset-vite";
import { defineConfig } from "vitest/config";

// Dates in tests are read in the zone of the person using the app.
process.env.TZ = "Asia/Ho_Chi_Minh";

const { version } = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as { version: string };

export default defineConfig({
  plugins: [preact()],
  // Shown in the small VHub signature at the bottom of Settings.
  define: { __APP_VERSION__: JSON.stringify(version) },
  build: { target: ["es2022", "safari16"], sourcemap: false, outDir: "dist", emptyOutDir: true },
  test: { include: ["test/**/*.test.{ts,tsx}"], environment: "node" },
});
