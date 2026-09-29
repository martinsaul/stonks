# Stonks web client

React + TypeScript (Vite), charts by [TradingView Lightweight Charts](https://www.tradingview.com/lightweight-charts/).
The client only displays what the server says and signs every request with a
device key (see [`docs/API.md`](../docs/API.md)).

```sh
npm install
npm run dev          # http://localhost:5173, proxies /api to STONKS_API (default http://localhost:8080)
npm test             # unit tests (signing, candle aggregation, formatting)
npm run typecheck
npm run build        # production bundle in dist/
npm run gen:api      # regenerate src/api/schema.d.ts from api/openapi.yaml
```

Run the server with `STONKS_DEV_MODE=true` for local development: sign-in codes are
returned by the API and filled in automatically. To see the market open outside
trading hours, also set `STONKS_DEV_TIME_SHIFT_HOURS` (e.g. `8`). The client adapts to
the server's clock.

## Structure

| Path | What |
|------|------|
| `src/api/` | Generated API types, WebCrypto device keys (`crypto.ts`), signed client with clock-skew correction (`client.ts`), shared WebSocket feed (`feed.ts`) |
| `src/lib/` | Market context (REST snapshot + live feed), formatting, chart range loading, theme, watchlist |
| `src/components/` | Header and market strip, quote tables, price chart, order book |
| `src/pages/` | Sign-in, Markets (home), Quote, Screener, Account |

The private signing key is generated non-extractable and kept in IndexedDB, so it
never leaves the browser. A session copied to another device doesn't work there.

## Colors

Up/down colors are a validated colorblind-safe pair in both themes (light
`#0b8a60`/`#d6453d`, dark `#1a9a78`/`#e0524a`), with darker/lighter steps for text so
it meets 4.5:1 contrast. Direction never relies on color alone: changes carry ▲/▼ and
a sign, and up candles are hollow while down candles are filled. Volume sits in its
own pane below the price chart, never on a second axis.
