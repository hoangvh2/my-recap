import preact from "@preact/preset-vite";
import { defineConfig } from "vitest/config";

// Dates in tests are read in the zone of the person using the app.
process.env.TZ = "Asia/Ho_Chi_Minh";

export default defineConfig({
  plugins: [preact()],
  build: { target: ["es2022", "safari16"], sourcemap: false, outDir: "dist", emptyOutDir: true },
  test: { include: ["test/**/*.test.{ts,tsx}"], environment: "node" },
});
