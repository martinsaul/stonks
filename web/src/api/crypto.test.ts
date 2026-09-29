// @vitest-environment node
import { describe, expect, it } from "vitest";
import { base64url, canonicalRequest, encoder, exportPublicKey, generateDeviceKey, signString } from "./crypto";

describe("STONKS-V1 signing", () => {
  it("builds the canonical string the server expects", async () => {
    const c = await canonicalRequest("get", "/api/v1/quotes/FOOF/candles?res=1d", 1790661706012, "abcdefghijklmnop", new Uint8Array());
    expect(c).toBe(
      "STONKS-V1\nGET\n/api/v1/quotes/FOOF/candles?res=1d\n1790661706012\nabcdefghijklmnop\n" +
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    );
  });

  it("produces 64-byte P1363 signatures that verify with the exported key", async () => {
    const pair = await generateDeviceKey();
    expect(pair.privateKey.extractable).toBe(false);
    const sig = await signString(pair, "hello");
    const raw = Uint8Array.from(atob(sig.replace(/-/g, "+").replace(/_/g, "/")), (ch) => ch.charCodeAt(0));
    expect(raw.length).toBe(64);

    const spki = await exportPublicKey(pair);
    const der = Uint8Array.from(atob(spki.replace(/-/g, "+").replace(/_/g, "/") + "==".slice((spki.length * 3) % 4 ? 0 : 2)), (ch) => ch.charCodeAt(0));
    const pub = await crypto.subtle.importKey("spki", der, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    expect(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pub, raw, encoder.encode("hello"))).toBe(true);
  });

  it("base64url has no padding or unsafe characters", () => {
    expect(base64url(new Uint8Array([251, 255, 191]))).toBe("-_-_");
  });
});
