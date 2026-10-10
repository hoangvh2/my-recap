// Bundles the functions (and the code shared with the web app in /shared) into one file, lib/index.js.
// Dependencies stay external: Cloud Build installs them from package.json.
import { rmSync } from "node:fs";
import { build } from "esbuild";

rmSync("lib", { recursive: true, force: true });
await build({
  entryPoints: ["src/index.ts"],
  bundle: true,
  platform: "node",
  target: "node22",
  format: "cjs",
  packages: "external",
  outfile: "lib/index.js",
  logLevel: "info",
});
