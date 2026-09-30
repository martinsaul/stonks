# Stonks API (v1)

All endpoints live under `/api/v1`. Prices are **integer cents**. Times are ISO-8601
UTC strings unless noted. The machine-readable spec is [`api/openapi.yaml`](../api/openapi.yaml).

Custom clients are welcome. They play by the same rules as the web client: every call
is signed and rate-limited.

## Authentication

### 1. Sign in (unsigned endpoints)

```
POST /api/v1/auth/otp/request   {"email": "you@gmail.com"}
→ 202 {"challengeId": "…", "expiresAt": "…"}   (a 6-digit code is emailed)

POST /api/v1/auth/otp/verify    {"challengeId": "…", "code": "123456", "publicKey": "<base64url SPKI>"}
→ 200 {"sessionId": "…", "accountId": 1, "expiresAt": "…", "serverTime": 1790661706012}
```

Each request creates its own challenge, and only the requester learns its
`challengeId`. Other people's requests can't replace your code or use up its 5
attempts. Too many requests for one address stop *sending* email but still return
`202` (no lockout, no signal).

> **Temporary:** while email delivery is a stub, every code is `111111`
> (`STONKS_FIXED_OTP`), and the response includes it as `devCode`. Anyone can then
> sign in as any address. Set `STONKS_FIXED_OTP=` (empty) to use random codes.

Before verifying, the client generates an **ECDSA P-256 key pair** and sends the public
key (SubjectPublicKeyInfo DER, base64url without padding). In browsers use WebCrypto
with `extractable: false`, so the private key can never leave the device, and store the
`CryptoKey` in IndexedDB. The session is bound to that key: a leaked session id is
useless without it.

Registration and login are the same flow. Addresses must be at a well-known provider.
Gmail dot variants reach the same account. `+tag` aliases are refused with
`403 alias_refused`, with the same response whether or not the base account exists
(no account enumeration). If it does exist, that account quietly receives a
"Nice Try" badge.

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
 "depth": {"FOOF": {"bids": [{"price": 5145, "size": 1200}, …], "asks": […]}},
 "benchmarkRate": 4.25, "latestNewsId": 1834, "listings": -118023}
```

When `latestNewsId` grows, fetch `/news?after=<last id you have>`. When `listings`
changes, a company listed or delisted: reload `/market` and resubscribe.

Between ticks, when only your own account changes (an order placed, filled or
cancelled), you get `{"type": "account", "account": {…}}`. Other players don't.
Slow clients skip frames rather than queueing them. Only text messages are
accepted: every client message counts toward the 5/s limit, and binary messages
close the connection.

## Endpoints (signed)

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/auth/logout` | Revokes the current session. |
| `GET`  | `/me` | Account and badges. |
| `GET`  | `/market` | Session state, regime, index, benchmark rate, latest news id and all listed quotes. Prefer the WebSocket for live data. |
| `GET`  | `/quotes/{ticker}` | Quote, profile, 52-week range, 30-day average volume, 10-level depth and fundamentals (EPS TTM, P/E, dividend, ex/pay dates, next earnings and consensus, status, deal offer, pending split, short interest, borrow fee). Delisted tickers return `404` with the reason. |
| `GET`  | `/quotes/{ticker}/candles` | `res` = `5s` \| `1m` \| `1d`; `from`/`to` (ISO or epoch ms); `limit` ≤ 2000 (default 500). Returns the newest candles in range, oldest first, plus the in-progress `live` candle. Adjusted for splits; delisted tickers keep their history. |
| `GET`  | `/news` | Newest first. `ticker` (also returns market-wide and same-sector news), `before`/`after` (id paging), `limit` ≤ 100 (default 30). `tone` is positive/negative/neutral. Rumors may turn out false. |
| `GET`  | `/calendar` | Next 60 game days: earnings (with consensus), ex-dividend and pay dates, splits, deal closes, IPOs, rate decisions; plus recent delistings. |
| `POST` | `/economy/{action}` | `claim`, `reset`, `bankrupt`, `upgrade`, `clear-badge`, `buy-bond` (`{"offeringId", "amount"}`). Logged and applied like orders; `400` with the reason when refused. Returns the new standing. |
| `GET`  | `/bonds` | Current bond offerings. |
| `POST` | `/me/name` | `{"displayName"}`: 3–20 letters, digits, `_` or `-`; unique. |
| `GET`  | `/leaderboards/millionaires` | Reviewed players over $1M: fewest badges of shame first, then net worth. |
| `GET`  | `/leaderboards/season?season=YYYY-MM` | Monthly (UTC) season by return %, plus your own entry and why it's unranked. |
| `GET`  | `/players/{name}` | Public profile: plan, net worth, level, badges, past seasons. Never the email. |
| `GET`  | `/portfolio` | Cash, equity, buying power, margin, positions with P/L, open orders, recent notices and `standing` (net worth incl. bonds, level, badges of shame, claim/reset availability, game-over line, bonds). The first call opens the trading account with starting cash. |
| `POST` | `/orders` | Place an order (see below). Returns `202` with the order ids and when it's expected to execute. |
| `DELETE` | `/orders/{id}` | Cancel an open order, or a whole group (OCO/OTO/bracket) by group id. |
| `GET`  | `/orders?status=open\|all` | Open orders, or the last 200 orders. |
| `GET`  | `/fills?limit=100` | Your executions, newest first. |

Unauthenticated: `GET /healthz`.

### Placing orders

```json
{"ticker": "FOOF", "structure": "SINGLE",
 "legs": [{"side": "BUY", "quantity": 10, "type": "LIMIT", "limitPrice": 2800, "timeInForce": "GTC"}]}
```

| `type` | Required fields |
|--------|-----------------|
| `MARKET` | none (`timeInForce` DAY or IOC) |
| `LIMIT` | `limitPrice` |
| `STOP` | `stopPrice` |
| `STOP_LIMIT` | `stopPrice`, `limitPrice` |
| `TRAILING_STOP` | `trailAmount` (cents) or `trailPercent` |
| `TRAILING_STOP_LIMIT` | trailing distance plus `limitOffset` (cents beyond the stop) |
| `TWAP` / `VWAP` | `durationMinutes` (1–600) |

`structure`: `SINGLE` (one leg); `OCO` (two legs, and the first fill cancels the other);
`OTO` (a parent and 1–2 children that activate after it fills); `BRACKET` (entry
MARKET/LIMIT, a take-profit LIMIT and a stop-loss stop, where the exits are opposite
the entry, activate after it fills, and cancel each other).

Execution follows the exchange rules in [DESIGN.md](DESIGN.md#execution-model-mimics-a-real-exchange).
An order enters a market-wide intake queue with 10 ms of simulated processing each and
executes at the first tick after it's ready. Matching uses price-time priority with price
improvement. Unfilled limits rest in the book. Buying power is checked again at
execution. The WebSocket `tick` frame carries your `account` (the same shape as
`/portfolio`), so live P/L and order status need no polling.

## Admin (game master)

Not part of the public API. Every call needs a normal signed session for an email
listed in `STONKS_ADMIN_EMAILS` **and** the `X-Stonks-Admin-Key` header matching
`STONKS_ADMIN_KEY` (the console is disabled without a key). The key is a second
factor: while every sign-in code is `111111`, an email proves nothing. `403` otherwise.

| Method | Path | Notes |
|--------|------|-------|
| `GET`  | `/admin/overview` | Tickers with strategy, status and controls; scheduled items (including hidden random events); bonds; enum values. |
| `POST` | `/admin/actions` | `{"type": …}`: `strategy`, `event`, `shock`, `rate`, `regime`, `halt`, `tune`, `split`, `dividend`, `buyback`, `ipo`, `bonds`, `void_fill`, `adjust_cash`. `at` (ISO) schedules events and shocks. Logged like player inputs and audited. |
| `GET`  | `/admin/players?q=` · `/admin/players/{id}` | Search; detail with portfolio, sessions, fills, economy events, flags. |
| `POST` | `/admin/players/{id}/ban` | `{"banned", "reason"}`: revokes sessions, blocks sign-in, hides from leaderboards. |
| `POST` | `/admin/fills/{id}/void` | Reverses a fill at its price and refunds the commission. |
| `GET`/`POST` | `/admin/reviews` · `/admin/reviews/{id}` | Millionaire review queue: `{"status": "APPROVED" \| "REJECTED" \| "PENDING"}`. |
| `GET`  | `/admin/economy` · `/admin/audit` · `/admin/snapshots` | Stats after every session; audit log; world snapshots. |
| `POST` | `/admin/rollback` | `{"snapshotId", "confirm": "ROLLBACK"}`: restores that snapshot on restart, discarding every later input. The server restarts. |

## Rate limits

| Budget | Default |
|--------|---------|
| Per session | burst 40, refills 10/s |
| Per account (all sessions) | burst 80, refills 20/s |
| Per IP, before signature checks | burst 100, refills 50/s |
| Concurrent in-flight requests per account | 8 |
| Sessions per account | 5 (the oldest is revoked) |
| WebSockets per account | 3; client messages 5/s |
| OTP requests | 10 per IP per hour; emails capped at 5 per address per 15 min (silently) |
| OTP verification | 30 per IP per hour; 5 wrong codes lock a code |

Most calls cost 1 token. Candle requests cost an extra `limit / 250`. Exceeding a
budget returns `429` with `Retry-After` (seconds).

## Errors

```json
{"error": "replayed", "message": "This request was already used."}
```

| Status | `error` codes |
|--------|---------------|
| 400 | `bad_request`, `email_rejected`, `otp_rejected`, `order_invalid`, `order_rejected` |
| 401 | `auth_required`, `bad_timestamp`, `clock_skew`, `bad_nonce`, `session_invalid`, `bad_signature`, `replayed` |
| 403 | `alias_refused` |
| 404 | `not_found` |
| 429 | `rate_limited`, `too_many_in_flight` |
| 503 | `busy` |
