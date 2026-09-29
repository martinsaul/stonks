# Stonks server

Kotlin backend. See [`docs/DESIGN.md`](../docs/DESIGN.md) for the game design.

## Modules

| Module   | Purpose |
|----------|---------|
| `engine` | Pure simulation library: market clock, order book and matching, opening auction, market makers, background order flow, strategies, market regimes, candles, backfill. No I/O. Deterministic from a seed. |
| `cli`    | `stonks-sim` command-line tool for running backfills and inspecting worlds. |
| `app`    | Ktor server: signed-request auth, rate limiting, REST + WebSocket API, Postgres persistence, live market runtime. |

## Requirements

JDK 21. Gradle is provided via the wrapper.

## Common tasks

```sh
./gradlew test                     # all tests (app tests use an embedded Postgres; no Docker needed)
./gradlew :engine:test            # run engine tests
./gradlew :cli:installDist         # build the CLI

# Simulate 2 game-years of history (≈500 sessions, ~40s on 4 cores)
./cli/build/install/stonks-sim/bin/stonks-sim backfill --seed 42 --sessions 500 --out backfill-out
```

The backfill writes `companies.csv`, `daily/<TICKER>.csv` and `minute/<TICKER>.csv`
(the last ~90 sessions), and prints a summary table with each company's return,
realized volatility and strategy history.

## How prices move

Each ticker, every 5-second tick:

1. The strategy curve produces a log return (strategy drift and volatility, market and
   sector factors scaled by beta, market regime). The reference price moves by it.
2. The market maker re-quotes a 10-level ladder around the reference. The ladder is
   stored as primitive arrays inside the book, not as order objects.
3. Queued player orders match, then Poisson background market orders.
4. However far trading pushed the consumed side's touch price is added to the
   reference **permanently**; nothing pulls it back.

Random draws never depend on book state, so two worlds with the same seed stay in
lockstep even if one receives extra orders. This is what the impact tests rely on.

## Running the server locally

```sh
# Postgres 16+ (TimescaleDB optional) with a `stonks` database, then:
STONKS_DB_URL=jdbc:postgresql://localhost:5432/stonks STONKS_DEV_MODE=true ./gradlew :app:run
```

| Variable | Default | Purpose |
|----------|---------|---------|
| `STONKS_PORT` | `8080` | HTTP port |
| `STONKS_DB_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/stonks`, `stonks`, `stonks` | Database |
| `STONKS_OTP_PEPPER` | dev value (warns) | Secret for OTP hashes. **Set in production.** |
| `STONKS_WORLD_SEED` | random | Seed used only when the world is first created |
| `STONKS_BACKFILL_SESSIONS` | `500` | Game days of history simulated at world creation |
| `STONKS_DEV_MODE` | `false` | Returns OTP codes in responses; allows `localhost:5173` CORS |
| `STONKS_TRUST_PROXY` | `false` | Use `X-Forwarded-For` for client IPs (only behind your own proxy) |
| `STONKS_CORS_ORIGINS` | – | Comma-separated allowed browser origins |
| `STONKS_ALLOWED_EMAIL_DOMAINS` | built-in list | Override the email provider allowlist |

## Server architecture

- **One simulation thread** owns the engine and steps it on the wall clock (tick *k*
  runs when its 5-second window ends). After each tick it publishes an immutable
  `MarketState`, which REST handlers and the WebSocket feed read without locks.
- **Snapshots, not replays.** The world's full state is snapshotted after every
  session. On startup the server restores the latest snapshot and simulates any sessions
  it missed, fast-forwarding into a session in progress. History already written is never
  recomputed, so deploying new engine code only affects the future.
- **Candles** flow from the engine through a `CandleSink` to a batching writer. Intraday
  retention is 7 days for 5-second candles and 60 days for 1-minute candles (TimescaleDB
  policies when available, otherwise an hourly sweep). Daily candles are kept forever.
- **Auth:** device-bound ECDSA sessions with per-request signatures, nonces and a
  timestamp window. See [`docs/API.md`](../docs/API.md).
