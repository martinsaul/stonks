// Device keys for request signing (docs/API.md, "STONKS-V1").
//
// The private key is generated non-extractable and kept in IndexedDB as a CryptoKey,
// so page scripts can use it to sign but can never read it out.

const DB = "stonks";
const STORE = "device";
const KEY_ID = "session-key";
const ALGO = { name: "ECDSA", namedCurve: "P-256" } as const;
const SIGN = { name: "ECDSA", hash: "SHA-256" } as const;

export const encoder = new TextEncoder();

export function base64url(bytes: ArrayBuffer | Uint8Array): string {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(data: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data as BufferSource));
  return Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function randomNonce(): string {
  return base64url(crypto.getRandomValues(new Uint8Array(16)));
}

/** The exact string covered by a request signature. */
export async function canonicalRequest(
  method: string,
  pathAndQuery: string,
  timestamp: number,
  nonce: string,
  body: Uint8Array,
): Promise<string> {
  return `STONKS-V1\n${method.toUpperCase()}\n${pathAndQuery}\n${timestamp}\n${nonce}\n${await sha256Hex(body)}`;
}

export async function generateDeviceKey(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(ALGO, false, ["sign", "verify"]) as Promise<CryptoKeyPair>;
}

export async function exportPublicKey(pair: CryptoKeyPair): Promise<string> {
  return base64url(await crypto.subtle.exportKey("spki", pair.publicKey));
}

export async function signString(pair: CryptoKeyPair, text: string): Promise<string> {
  return base64url(await crypto.subtle.sign(SIGN, pair.privateKey, encoder.encode(text)));
}

// --- IndexedDB persistence ---------------------------------------------------------

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx<T>(mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const req = op(db.transaction(STORE, mode).objectStore(STORE));
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function saveDeviceKey(pair: CryptoKeyPair): Promise<void> {
  await tx("readwrite", (s) => s.put(pair, KEY_ID));
}

export async function loadDeviceKey(): Promise<CryptoKeyPair | undefined> {
  try {
    return (await tx<CryptoKeyPair | undefined>("readonly", (s) => s.get(KEY_ID))) ?? undefined;
  } catch {
    return undefined;
  }
}

export async function clearDeviceKey(): Promise<void> {
  try {
    await tx("readwrite", (s) => s.delete(KEY_ID));
  } catch {
    /* nothing stored */
  }
}
