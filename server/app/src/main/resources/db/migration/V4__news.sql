-- The market's news feed and corporate actions. Both are deterministic outputs of the
-- engine (stable ids / keys), so replays after a restart write them idempotently.

create table news (
    id           bigint primary key,
    published_at timestamptz not null,
    day          int         not null,
    ticker       text,
    sector       text,
    category     text        not null,
    headline     text        not null,
    sentiment    double precision not null default 0
);
create index news_ticker_id on news (ticker, id desc);

-- Splits (ratio = new shares per old share), listings and delistings.
create table corporate_actions (
    ticker     text        not null,
    day        int         not null,
    kind       text        not null,
    ratio      double precision not null default 1,
    at         timestamptz not null,
    primary key (ticker, day, kind)
);
