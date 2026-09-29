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

## Design principles

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
- **Forced (game over):** hitting the negative threshold triggers bankruptcy
  automatically, +1 badge of shame.

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

- Forced-bankruptcy threshold
- Shared world / expected player count
- Market hours and time compression
- Price impact model (synthetic order book vs impact formula)
- Margin: max leverage, maintenance margin, liquidation, short borrow fees
- Company bankruptcy and replacement
- Order types (market, limit, stop, stop-limit, trailing stop, bracket/OCO)
- Seasons
- Auth / anti multi-accounting
- Additional money sinks (candidates: retire/prestige, IPO sponsorship, paid intel)
- Admin tooling (manual events, strategy changes, community goals)
- Copycat virtual traders
- Historical data generation
