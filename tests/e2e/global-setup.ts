import { join } from "node:path";
import { startFakeGemini } from "./fake-gemini.mjs";
import { productionHeaders, startStatic } from "./static-server.mjs";

const root = join(__dirname, "..", "..");

export default async function globalSetup() {
  const gemini = await startFakeGemini(8787);
  const web = await startStatic(join(root, "web", "dist-e2e"), 4173, productionHeaders(join(root, "firebase.json")));
  (globalThis as unknown as { __gemini: typeof gemini }).__gemini = gemini;
  // Tests read the recorded calls over HTTP-free shared memory via a tiny JSON endpoint on a side port.
  const { createServer } = await import("node:http");
  const side = createServer((req, res) => {
    if (req.url === "/reset") gemini.calls.length = 0;
    res.writeHead(200, { "content-type": "application/json", "access-control-allow-origin": "*" });
    res.end(JSON.stringify(gemini.calls));
  }).listen(8788, "127.0.0.1");
  return async () => {
    side.close();
    await gemini.close();
    await web.close();
  };
}
