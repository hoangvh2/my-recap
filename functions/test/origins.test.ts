import { describe, expect, it } from "vitest";
import { allowedOrigins } from "../src/config";

describe("allowedOrigins", () => {
  const base = ["https://p.web.app", "https://p.firebaseapp.com"];
  it("always allows the two default hosts", () => {
    expect(allowedOrigins("p", undefined)).toEqual(base);
    expect(allowedOrigins("p", "")).toEqual(base);
  });
  it("adds a custom domain given as a host or an https URL, once", () => {
    expect(allowedOrigins("p", "Recap.Example.com")).toEqual([...base, "https://recap.example.com"]);
    expect(allowedOrigins("p", "https://recap.example.com/, recap.example.com")).toEqual([...base, "https://recap.example.com"]);
  });
  it("drops anything that is not a plain https host", () => {
    expect(allowedOrigins("p", "http://x.com, *.example.com, evil.com/path, localhost, a b, javascript:alert(1)")).toEqual(base);
  });
});
