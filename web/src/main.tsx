import { render } from "preact";
import "./styles.css";
import { App } from "./ui/App";

if (import.meta.env.VITE_USE_EMULATORS === "true") await import("./e2e");

render(<App />, document.getElementById("app")!);
