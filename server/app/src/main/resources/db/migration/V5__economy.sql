-- Player economy: public names, exactly-once economy records, leaderboards.

-- Public display names (emails are never shown). Case-insensitively unique.
alter table accounts add column display_name text;
update accounts set display_name = 'Trader' || id where display_name is null;
alter table accounts alter column display_name set not null;
create unique index accounts_display_name on accounts (lower(display_name));

-- Claims, resets, bankruptcies, upgrades, badge clearing, bonds, achievements.
-- key is deterministic (engine input seq or game time), so replays write nothing twice.
create table economy_events (
    key        text primary key,
    account_id bigint      not null,
    kind       text        not null,
    amount     bigint      not null default 0,
    detail     text,
    at         timestamptz not null
);
create index economy_events_account on economy_events (account_id, at);
create index economy_events_kind on economy_events (kind, at);

-- Badges awarded from engine events are awarded once per event key.
alter table account_badges add column event_key text;
create unique index account_badges_event_key on account_badges (event_key) where event_key is not null;

-- Millionaires are listed only after a manual review.
create table leaderboard_reviews (
    account_id         bigint primary key references accounts (id),
    status             text        not null default 'PENDING', -- PENDING, APPROVED, REJECTED
    first_qualified_at timestamptz not null default now(),
    reviewed_at        timestamptz,
    reviewer           text,
    note               text
);

-- Monthly seasons ranked by return %.
create table seasons (
    id           text primary key,         -- e.g. 2026-10
    starts_at    timestamptz not null,
    ends_at      timestamptz not null,
    finalized_at timestamptz
);
create table season_entries (
    season       text        not null references seasons (id),
    account_id   bigint      not null,
    start_worth  bigint      not null,
    joined_at    timestamptz not null,
    final_worth  bigint,
    final_return double precision,
    final_rank   int,
    primary key (season, account_id)
);
create index fills_account_at on fills (account_id, at);
