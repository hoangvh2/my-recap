import { render } from "preact";
import "./styles.css";
import { App } from "./ui/App";

if (import.meta.env.VITE_USE_EMULATORS === "true") await import("./e2e");

// The app signs in against one canonical host. Opening it on another host of the same site (for
// example <project>.web.app) would send Google a redirect URI it does not know, so move to the canonical one.
const canonical = import.meta.env.VITE_FIREBASE_AUTH_DOMAIN;
if (canonical && location.host !== canonical) {
  location.replace(`https://${canonical}${location.pathname}${location.search}${location.hash}`);
} else {
  render(<App />, document.getElementById("app")!);
}
