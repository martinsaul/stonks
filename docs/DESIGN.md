# Stonks — Game Design

Living document. Decisions are recorded here once agreed.

## Concept

A multiplayer browser game simulating a stock market of ~50 fictional companies
(e.g. Foofle, Macrohard). The server is authoritative; the client only renders
server state and submits player actions. Prices move from simulated events and
long-term strategies, but player trading moves prices for real. All money is
virtual.

## Platform & stack

- **Browser only.** Responsive web app, desktop-first, Yahoo Finance–like layout.
- **Backend:** Kotlin (Ktor, coroutines). One coroutine/actor per ticker owns its
  simulation and order book.
- **Persistence:** Postgres (+ TimescaleDB for price history).
- **Frontend:** TypeScript + React; charting via TradingView Lightweight Charts.
- **API contract:** single schema (protobuf or OpenAPI) generating both Kotlin
  and TypeScript types.

## World & scale

- **One shared global market.** All players trade the same tickers; every
  player's trades affect prices seen by everyone.
- **Target scale:** up to ~4k concurrent players. A single backend instance is
  sufficient; no horizontal sharding in the initial architecture.
- **Market depth auto-tunes** from average daily active players so player impact
  stays meaningful but not absurd as the population changes. Admin-overridable.

## Regions & schedule

- Markets are **regional instances**: AMER, EMEA, APAC. **Launch with AMER
  only**; expand later.
- AMER runs on **US Eastern Time**.
- Prices tick every **5 seconds** during sessions.

### Weekday (Mon–Fri): two sessions, each one game day

```
06:00 ─ 13:30   Session A (7.5h)
13:30 ─ 14:30   Break ("overnight": news/earnings may drop, prices may gap)
14:30 ─ 22:00   Session B (7.5h)
22:00 ─ 06:00   Closed
```

### Weekend (Sat–Sun): one reduced session, one game day

- **10:00–20:00 ET.**
- Baseline volatility ~50% of normal.
- No scheduled catalysts (earnings, dividends, splits). Random news at a
  reduced rate, mostly minor.
- Player price impact is fully real.
- Admin-triggered events allowed.
- **Maintenance window:** Sunday 20:00 → Monday 06:00.

**12 game days per real week.**

### Closed-market behavior
- Orders can be placed while the market is closed and queue for the open.
- Each session opens with an **opening auction**: queued orders cross at a
  single opening price.
- Earnings are staggered: each company reports roughly every 4 real weeks
  (~12 earnings calls/week across 50 companies). Tunable.
- Margin interest and borrow fees accrue per game day. Real-time timers
  (weekly claim, monthly reset, cooldowns) run in real time.

## Price model

- **Strategies set returns, not price levels.** Each tick:
  `new_price = last_price × (1 + curve_pct[t])`, then player orders act on the
  book. The curve is pre-computed from the company's strategy plus events.
- **Synthetic order book.** NPC market makers quote bid/ask depth around the
  **current** price (no hidden anchor). Player orders consume depth, causing
  real slippage; player-vs-player orders match first.
- **No rails.** Player-driven moves are permanent; nothing reverts price to a
  "fair value". Shake-outs, dumps and short squeezes emerge from player behavior.
- **Fundamentals (v1):** shares outstanding, quarterly EPS
  (`EPS_prev × (1 + strategy growth + noise)`), consensus estimate, market cap,
  P/E, dividend yield. Earnings calls resolve actual vs. estimate (beat/miss),
  combined with an outcome pattern (uptrend, reversal, downtrend, stagnancy…).
- **Later:** NPC trader archetypes as plug-ins trading through the same book
  (momentum/RSI/MACD bots, mean-reversion bots, copycats).

## Design principles

- **Keep it manageable.** We're not NYSE. Add depth incrementally through
  pluggable pieces rather than a complex core.

- **No pay-to-win.** Monetization is deferred. Anything bought with real money
  must never affect net worth, trading, or leaderboard eligibility.

## Commission plans

Unlocked by **lifetime realized profit**. Permanent, except: dropping to net
worth <= $0 or resetting the account returns the player to Rookie.

| Plan    | Unlock   | Commission                                   | Margin rate        |
|---------|----------|----------------------------------------------|--------------------|
| Rookie  | start    | $4.95 flat / trade                           | benchmark + 4%     |
| Trader  | $25k     | $0.01/share, min $1, max 1% of trade value   | benchmark + 2.5%   |
| Pro     | $250k    | $0.005/share, min $1, max 1% of trade value  | benchmark + 1.5%   |
| Whale   | $1M      | $0.0035/share, min $0.35, + large-order fee  | benchmark + 1%     |

Benchmark = simulated central bank rate.

Net worth = cash + position value − margin debt − short liabilities.

## Starting cash

New and reset accounts start with **$5,000**.

### Starting-cash upgrades (money sink)
Bought with in-game cash, sequentially:

```
Level k (k >= 1): costs $1M × 2^(k−1), raises starting cash to $5k + k × $1k
```

| Level | Cost   | Total spent | Starting cash |
|-------|--------|-------------|---------------|
| 1     | $1M    | $1M         | $6k           |
| 2     | $2M    | $3M         | $7k           |
| 3     | $4M    | $7M         | $8k           |
| 5     | $16M   | $31M        | $10k          |
| 10    | $512M  | ~$1B        | $15k          |
| 20    | $524B  | ~$1T        | $25k          |

- Upgrades **survive monthly resets**.
- **Bankruptcy (voluntary or forced) removes one level.**

## Margin & liquidation

| Plan    | Max leverage | Liquidation below (equity / position value) |
|---------|--------------|---------------------------------------------|
| Rookie  | 2x           | 30%                                         |
| Trader  | 2x           | 25%                                         |
| Pro     | 4x           | 25%                                         |
| Whale   | 6x           | 20%                                         |

- **Shorts:** 150% initial collateral, liquidated below 130%.
- **Instant auto-liquidation**, no grace period. The server closes positions
  (largest loser first) until back above maintenance. Normal commissions apply.
- **Warning** at 5% above the liquidation threshold (banner + notification).
- Gaps (e.g. post-earnings opens) liquidate at the gapped price and can leave the
  player in debt. Intended.
- Margin interest: plan rate over benchmark, accrued per game day.

## Bailouts, resets and bankruptcy

### Weekly claim
Players with net worth below $1,000 may claim $1,000 once per week. Players in
debt may also claim it to pay debt down.

### Monthly reset
Resets the account to starting cash, **wipes debt**, and resets the plan to
Rookie. The cooldown before the *next* reset is:

```
cooldown_days = (30 + 0.55·D + 0.015·D²) × 1.25^r

D = debt at the moment of reset, in $k (0 if not in debt)
r = resets performed while in debt within the trailing 180 days
```

| Debt at reset | Cooldown (r = 0) |
|---------------|------------------|
| none          | 30 days          |
| −$10k         | 37 days          |
| −$20k         | 47 days          |
| −$50k         | 95 days          |
| −$100k        | 235 days         |

### Bankruptcy
- **Voluntary:** a player with negative net worth may declare bankruptcy at any
  time. Immediate fresh start, no cooldown, +1 badge of shame.
- **Forced (game over):** triggered automatically when
  `net worth <= −10 × starting cash` (−$50k for a $5k start), +1 badge of shame.

### Badges of shame
- Clearing badge #n costs `$1,000 × 2^(n−1) / n`, paid from cash balance.
  Badges are cleared from the highest down.
- **Eternal Shame:** once a player reaches 30 badges, none can be cleared again.

| Badge # | Cost     |
|---------|----------|
| 1–2     | $1k      |
| 5       | $3.2k    |
| 8       | $16k     |
| 10      | $51k     |
| 15      | $1.1M    |
| 20      | $26M     |
| 25      | $671M    |
| 29      | $9.3B    |

## Leaderboard

- Players with net worth over $1M, manually reviewed for cheating.
- **Tiered by outstanding badges of shame:** rank by fewest outstanding badges,
  then by net worth. Eternal Shame is its own bottom tier.

## Achievement badges (to be expanded)

- **Midas' Hands** — bought at the bottom, sold at the top.
- **Sadim's Hands** — bought at the top, sold at the bottom.
- Return-% milestones.

## Open topics

- Cross-region accounts and leaderboards (when EMEA/APAC launch)
- Short borrow fees and availability
- Company bankruptcy and replacement
- Order types (market, limit, stop, stop-limit, trailing stop, bracket/OCO)
- Seasons
- Auth / anti multi-accounting
- Additional money sinks (candidates: retire/prestige, IPO sponsorship, paid intel)
- Admin tooling (manual events, strategy changes, community goals)
- Copycat virtual traders
- Historical data generation
