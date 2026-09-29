-- Stonks schema v1: world state, accounts & auth, candles, event log.

create table world (
    id          int primary key default 1 check (id = 1),
    seed        bigint      not null,
    live_at     timestamptz not null,
    created_at  timestamptz not null default now()
);

-- Engine snapshots, taken while the market is closed (after every session).
create table world_snapshots (
    id                 bigserial primary key,
    taken_at           timestamptz not null default now(),
    sessions_completed int         not null,
    last_close         timestamptz,
    data               bytea       not null
);

create table accounts (
    id              bigserial primary key,
    email           text        not null,
    email_canonical text        not null unique,
    created_at      timestamptz not null default now(),
    last_login_at   timestamptz
);

create table account_badges (
    id         bigserial primary key,
    account_id bigint      not null references accounts (id),
    badge      text        not null,
    detail     text,
    awarded_at timestamptz not null default now()
);
create index account_badges_account on account_badges (account_id);

create table otp_challenges (
    id          bigserial primary key,
    email       text        not null,
    code_hash   bytea       not null,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    attempts    int         not null default 0,
    consumed_at timestamptz
);
create index otp_challenges_email on otp_challenges (email, created_at desc);

-- Device-bound sessions: every request is signed with the private key matching
-- public_key (ECDSA P-256), so a leaked session id alone is useless.
create table sessions (
    id           text primary key,
    account_id   bigint      not null references accounts (id),
    public_key   bytea       not null,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    user_agent   text,
    ip           text
);
create index sessions_account on sessions (account_id);

-- Append-only event log (audit trail; the basis for reviews and rollbacks).
create table events (
    id         bigserial primary key,
    at         timestamptz not null default now(),
    type       text        not null,
    account_id bigint,
    payload    jsonb       not null default '{}'
);
create index events_account on events (account_id, at);
create index events_type on events (type, at);

-- Candles. Prices in integer cents. Daily candles are stamped with the session open.
create table candles_5s (
    ticker text not null, time timestamptz not null,
    open bigint not null, high bigint not null, low bigint not null, close bigint not null,
    volume bigint not null,
    primary key (ticker, time)
);
create table candles_1m (like candles_5s including all);
create table candles_1d (like candles_5s including all);

-- Use TimescaleDB when available (hypertables + retention); otherwise the app
-- deletes expired rows itself.
do $$
begin
    if exists (select 1 from pg_available_extensions where name = 'timescaledb') then
        create extension if not exists timescaledb;
        perform create_hypertable('candles_5s', 'time', chunk_time_interval => interval '1 day');
        perform create_hypertable('candles_1m', 'time', chunk_time_interval => interval '7 days');
        perform add_retention_policy('candles_5s', interval '7 days');
        perform add_retention_policy('candles_1m', interval '60 days');
    end if;
end
$$;
