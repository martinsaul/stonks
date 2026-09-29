# Stonks API (v1)

All endpoints live under `/api/v1`. Prices are **integer cents**. Times are ISO-8601
UTC strings unless noted. The machine-readable spec is [`api/openapi.yaml`](../api/openapi.yaml).

Custom clients are welcome. They play by the same rules as the web client: every call
is signed and rate-limited.

## Authentication

### 1. Sign in (unsigned endpoints)

```
POST /api/v1/auth/otp/request   {"email": "you@gmail.com"}
→ 202 {"expiresAt": "…"}                      (a 6-digit code is emailed)

POST /api/v1/auth/otp/verify    {"email": "…", "code": "123456", "publicKey": "<base64url SPKI>"}
→ 200 {"sessionId": "…", "accountId": 1, "expiresAt": "…", "newAccount": true, "serverTime": 1790661706012}
```

Before verifying, the client generates an **ECDSA P-256 key pair** and sends the public
key (SubjectPublicKeyInfo DER, base64url without padding). In browsers use WebCrypto
with `extractable: false`, so the private key can never leave the device, and store the
`CryptoKey` in IndexedDB. The session is bound to that key: a leaked session id is
useless without it.

Registration and login are the same flow. Addresses must be at a well-known provider.
Aliases (`+tags`, Gmail dots) of an existing account are refused with `403 alias_refused`,
and the original account receives a "Nice Try" badge.

### 2. Sign every request (`STONKS-V1`)

Every other call, reads included, must carry:

| Header               | Value |
|----------------------|-------|
| `X-Stonks-Session`   | session id from sign-in |
| `X-Stonks-Timestamp` | current time, Unix milliseconds (±30s of server time) |
| `X-Stonks-Nonce`     | fresh random value, 16–64 base64url characters, never reused |
| `X-Stonks-Signature` | base64url ECDSA P-256 / SHA-256 signature, IEEE P1363 (`r‖s`, 64 bytes), as WebCrypto produces |

The signature covers this string (newline-separated, no trailing newline):

```
STONKS-V1
<METHOD>
<path and query exactly as sent, e.g. /api/v1/quotes/FOOF/candles?res=1d>
<timestamp>
<nonce>
<lowercase hex SHA-256 of the raw request body (empty body → hash of "")>
```

A request can't be replayed: the nonce is remembered for the timestamp window, and the
body and path are covered by the signature.

If your clock drifts, `401` responses carry `X-Stonks-Server-Time` (Unix ms); compute an
offset and retry.

### 3. WebSocket

Browsers can't set headers on WebSocket upgrades, so the handshake is signed through
query parameters:

```
GET /api/v1/ws?session=<id>&ts=<ms>&nonce=<nonce>&sig=<signature>
```

The signature is over the canonical string for `GET` + `/api/v1/ws` (without the
query) + empty body. A bad or replayed handshake is refused with `401` before the
upgrade.

Client → server:

```json
{"op": "subscribe", "quotes": ["FOOF", "MHRD"], "depth": ["FOOF"]}
{"op": "ping"}
```

`subscribe` replaces the connection's subscriptions: up to 50 quote tickers and 3 depth
tickers. The current state is sent immediately. After that, a `tick` frame arrives every
5 seconds while the market is open:

```json
{"type": "tick", "time": "…", "session": {…}, "regime": "BULL",
 "index": {"name": "STONKS 50", "value": 1143.4, …},
 "quotes": [{"ticker": "FOOF", "last": 5146, "bid": 5145, "ask": 5147, …}],
 "depth": {"FOOF": {"bids": [{"price": 5145, "size": 1200}, …], "asks": […]}}}
```

Slow clients skip frames rather than queueing them.

## Endpoints (signed)

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/auth/logout` | Revokes the current session. |
| `GET`  | `/me` | Account and badges. |
| `GET`  | `/market` | Session state, regime, index and all quotes. Prefer the WebSocket for live data. |
| `GET`  | `/quotes/{ticker}` | Quote, profile, 52-week range, 30-day average volume and 10-level depth. |
| `GET`  | `/quotes/{ticker}/candles` | `res` = `5s` \| `1m` \| `1d`; `from`/`to` (ISO or epoch ms); `limit` ≤ 2000 (default 500). Returns the newest candles in range, oldest first, plus the in-progress `live` candle. |

Unauthenticated: `GET /healthz`.

## Rate limits

| Budget | Default |
|--------|---------|
| Per session | burst 40, refills 10/s |
| Per account (all sessions) | burst 80, refills 20/s |
| Per IP, before signature checks | burst 100, refills 50/s |
| Concurrent in-flight requests per account | 4 |
| Sessions per account | 5 (the oldest is revoked) |
| WebSockets per account | 3; client messages 5/s |
| OTP requests | 3 per address per 15 min; 10 per IP per hour |
| OTP verification | 30 per IP per hour; 5 wrong codes lock a code |

Most calls cost 1 token. Candle requests cost an extra `limit / 250`. Exceeding a
budget returns `429` with `Retry-After` (seconds).

## Errors

```json
{"error": "replayed", "message": "This request was already used."}
```

| Status | `error` codes |
|--------|---------------|
| 400 | `bad_request`, `email_rejected`, `otp_rejected` |
| 401 | `auth_required`, `bad_timestamp`, `clock_skew`, `bad_nonce`, `session_invalid`, `bad_signature`, `replayed` |
| 403 | `alias_refused` |
| 404 | `not_found` |
| 429 | `rate_limited`, `too_many_in_flight` |
