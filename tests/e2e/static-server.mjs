// Serves the built web app with the same security headers firebase.json gives it in production
// (CSP included, plus localhost allowances for the emulators), so the run proves the app works under them.
import { readFileSync, existsSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";

const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".webmanifest": "application/manifest+json", ".png": "image/png", ".txt": "text/plain" };

export function productionHeaders(firebaseJsonPath, extraConnect = "http://127.0.0.1:* ws://127.0.0.1:*") {
  const cfg = JSON.parse(readFileSync(firebaseJsonPath, "utf8"));
  const rules = cfg.hosting.headers;
  const all = Object.fromEntries(rules.find((r) => r.source === "**").headers.map((h) => [h.key, h.value]));
  const csp = rules.find((r) => r.source === "/index.html").headers.find((h) => h.key === "Content-Security-Policy").value;
  return { all, csp: csp.replace("connect-src 'self'", `connect-src 'self' ${extraConnect}`) };
}

export function startStatic(dir, port, headers) {
  const server = createServer((req, res) => {
    const url = new URL(req.url, "http://x");
    let path = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, "");
    let file = join(dir, path);
    if (!existsSync(file) || statSync(file).isDirectory()) file = join(dir, "index.html");
    const isHtml = file.endsWith("index.html");
    res.writeHead(200, {
      "content-type": TYPES[extname(file)] ?? "application/octet-stream",
      ...headers.all,
      ...(isHtml ? { "content-security-policy": headers.csp, "cache-control": "no-cache" } : {}),
    });
    res.end(readFileSync(file));
  });
  return new Promise((resolve) => server.listen(port, "127.0.0.1", () => resolve({ close: () => new Promise((r) => server.close(r)) })));
}
