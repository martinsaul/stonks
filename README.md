# Stonks

A multiplayer stock-market game with fictional companies (Foofle, Macrohard, …). The
server simulates the market and is authoritative; the browser only displays it and
submits player actions.

- Game design: [`docs/DESIGN.md`](docs/DESIGN.md)
- API and request signing: [`docs/API.md`](docs/API.md), [`api/openapi.yaml`](api/openapi.yaml)
- Server (Kotlin): [`server/`](server/README.md)

## Run it (Docker)

```sh
cp .env.example .env        # set POSTGRES_PASSWORD and STONKS_OTP_PEPPER
docker compose up -d --build
docker compose logs -f server
```

The first start creates the world and simulates about 2 game-years of history (1–2
minutes). The API listens on port 8080 (`STONKS_HTTP_PORT`).

Email delivery is still a stub: sign-in codes are written to the server log
(`docker compose logs server | grep OTP`). With `STONKS_DEV_MODE=true` they're also
returned by the API. Use that only for local development.
