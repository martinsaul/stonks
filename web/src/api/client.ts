import {
  canonicalRequest,
  clearDeviceKey,
  encoder,
  exportPublicKey,
  generateDeviceKey,
  loadDeviceKey,
  randomNonce,
  saveDeviceKey,
  signString,
} from "./crypto";
import type { LoginResponse, OtpRequested } from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfter?: number,
  ) {
    super(message);
  }
}

interface StoredSession {
  sessionId: string;
  accountId: number;
  expiresAt: string;
}

const SESSION_KEY = "stonks.session";
const OFFSET_KEY = "stonks.clockOffset";

/**
 * Signs every request with the device key (docs/API.md). Keeps an estimate of the
 * server clock so timestamps stay inside the server's window even if the local clock
 * drifts.
 */
class ApiClient {
  private key?: CryptoKeyPair;
  private session?: StoredSession;
  private clockOffset = Number(localStorage.getItem(OFFSET_KEY)) || 0;
  private listeners = new Set<() => void>();

  /** Server time estimate in epoch milliseconds. */
  now(): number {
    return Date.now() + this.clockOffset;
  }

  get signedIn(): boolean {
    return !!this.session && !!this.key;
  }

  get accountId(): number | undefined {
    return this.session?.accountId;
  }

  /** Called when the session ends (sign-out, expiry, revocation). */
  onSignedOut(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async restore(): Promise<boolean> {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return false;
    const session = JSON.parse(raw) as StoredSession;
    if (new Date(session.expiresAt).getTime() < Date.now()) return this.forget();
    const key = await loadDeviceKey();
    if (!key) return this.forget();
    this.session = session;
    this.key = key;
    return true;
  }

  async requestCode(email: string): Promise<OtpRequested> {
    return this.unsigned("/api/v1/auth/otp/request", { email });
  }

  async verifyCode(email: string, code: string): Promise<LoginResponse> {
    const key = await generateDeviceKey();
    const publicKey = await exportPublicKey(key);
    const res = await this.unsigned<LoginResponse>("/api/v1/auth/otp/verify", { email, code, publicKey });
    this.setClockOffset(res.serverTime - Date.now());
    await saveDeviceKey(key);
    this.key = key;
    this.session = { sessionId: res.sessionId, accountId: res.accountId, expiresAt: res.expiresAt };
    localStorage.setItem(SESSION_KEY, JSON.stringify(this.session));
    return res;
  }

  async signOut(): Promise<void> {
    try {
      if (this.signedIn) await this.post("/api/v1/auth/logout");
    } catch {
      /* already invalid */
    }
    await this.forget();
  }

  get<T>(path: string): Promise<T> {
    return this.signed<T>("GET", path);
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.signed<T>("POST", path, body);
  }

  /** Re-syncs the clock estimate with a cheap signed call (WebSockets can't report skew). */
  async calibrate(): Promise<void> {
    await this.get("/api/v1/me");
  }

  private setClockOffset(offset: number) {
    this.clockOffset = offset;
    localStorage.setItem(OFFSET_KEY, String(offset));
  }

  /** Signed query parameters for the WebSocket handshake. */
  async socketUrl(path = "/api/v1/ws"): Promise<string> {
    if (!this.key || !this.session) throw new ApiError(401, "auth_required", "Not signed in");
    const ts = Math.round(this.now());
    const nonce = randomNonce();
    const sig = await signString(this.key, await canonicalRequest("GET", path, ts, nonce, new Uint8Array()));
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const q = new URLSearchParams({ session: this.session.sessionId, ts: String(ts), nonce, sig });
    return `${proto}//${location.host}${path}?${q}`;
  }

  private async signed<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
    if (!this.key || !this.session) throw new ApiError(401, "auth_required", "Not signed in");
    const bytes = body === undefined ? new Uint8Array() : encoder.encode(JSON.stringify(body));
    const ts = Math.round(this.now());
    const nonce = randomNonce();
    const signature = await signString(this.key, await canonicalRequest(method, path, ts, nonce, bytes));
    const res = await fetch(path, {
      method,
      body: body === undefined ? undefined : (bytes as BodyInit),
      headers: {
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        "X-Stonks-Session": this.session.sessionId,
        "X-Stonks-Timestamp": String(ts),
        "X-Stonks-Nonce": nonce,
        "X-Stonks-Signature": signature,
      },
    });
    if (res.ok) return (res.status === 204 ? undefined : await res.json()) as T;

    const err = await toError(res);
    if (err.code === "clock_skew" && !retried) {
      const serverTime = Number(res.headers.get("X-Stonks-Server-Time"));
      if (serverTime) this.setClockOffset(serverTime - Date.now());
      return this.signed<T>(method, path, body, true);
    }
    if (err.code === "session_invalid") await this.forget();
    throw err;
  }

  private async unsigned<T>(path: string, body: unknown): Promise<T> {
    const res = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw await toError(res);
    return (await res.json()) as T;
  }

  private async forget(): Promise<false> {
    const wasSignedIn = this.signedIn;
    this.session = undefined;
    this.key = undefined;
    localStorage.removeItem(SESSION_KEY);
    await clearDeviceKey();
    if (wasSignedIn) this.listeners.forEach((l) => l());
    return false;
  }
}

async function toError(res: Response): Promise<ApiError> {
  let code = "http_" + res.status;
  let message = res.statusText || "Request failed";
  try {
    const body = (await res.json()) as { error?: string; message?: string };
    code = body.error ?? code;
    message = body.message ?? message;
  } catch {
    /* not JSON */
  }
  const retry = Number(res.headers.get("Retry-After"));
  return new ApiError(res.status, code, message, retry || undefined);
}

export const api = new ApiClient();
