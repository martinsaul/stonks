# Stonks server

Kotlin backend. See [`docs/DESIGN.md`](../docs/DESIGN.md) for the game design.

## Modules

| Module   | Purpose |
|----------|---------|
| `engine` | Pure simulation library: market clock, order book and matching, opening auction, market makers, background order flow, strategies, market regimes, candles, backfill. No I/O. Deterministic from a seed. |
| `cli`    | `stonks-sim` command-line tool for running backfills and inspecting worlds. |

The Ktor application (`app`) arrives in Milestone 2.

## Requirements

JDK 21. Gradle is provided via the wrapper.

## Common tasks

```sh
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
