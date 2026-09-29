-- Trading: the write-ahead input log (source of truth for replay) and projections.

-- Every player input, logged before it is applied to the engine. After a restart the
-- engine restores its latest snapshot and replays inputs with seq > the snapshot's.
create table engine_inputs (
    seq        bigserial primary key,
    at         timestamptz not null default now(),
    apply_day  int         not null,
    apply_tick int         not null,   -- -2 = while the market was closed
    kind       text        not null,
    account_id bigint      not null,
    payload    jsonb       not null
);

-- Projections of engine events (rebuildable; idempotent writes).
create table orders (
    id           bigint primary key,
    group_id     bigint      not null,
    account_id   bigint      not null,
    ticker       text        not null,
    side         text        not null,
    kind         text        not null,
    quantity     bigint      not null,
    status       text        not null,
    filled       bigint      not null,
    avg_price    double precision,
    limit_price  bigint,
    stop_price   bigint,
    reason       text,
    liquidation  boolean     not null default false,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index orders_account on orders (account_id, updated_at desc);

create table fills (
    id          text primary key,
    order_id    bigint      not null,
    account_id  bigint      not null,
    ticker      text        not null,
    side        text        not null,
    quantity    bigint      not null,
    price       bigint      not null,
    commission  bigint      not null,
    realized    bigint      not null,
    maker       boolean     not null,
    liquidation boolean     not null,
    game_day    int         not null,
    tick        int         not null,
    at          timestamptz not null default now()
);
create index fills_account on fills (account_id, at desc);

-- Latest portfolio figures per account (for leaderboards and reviews).
create table portfolios (
    account_id        bigint primary key,
    plan              text        not null,
    cash              bigint      not null,
    equity            bigint      not null,
    lifetime_realized bigint      not null,
    updated_at        timestamptz not null default now()
);
