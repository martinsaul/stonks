-- Game master: bans, audit trail, economy history, fill review data, rollbacks.

alter table accounts add column banned_at timestamptz;
alter table accounts add column ban_reason text;

-- Every admin action, attributed.
create table admin_actions (
    id         bigserial primary key,
    at         timestamptz not null default now(),
    admin_id   bigint      not null,
    action     text        not null,
    target     text,
    payload    jsonb       not null default '{}',
    result     text
);
create index admin_actions_at on admin_actions (at desc);

-- Player economy after every session (admin dashboard).
create table economy_stats (
    game_day       int primary key,
    at             timestamptz not null,
    players        int         not null,
    total_worth    bigint      not null,
    total_cash     bigint      not null,
    total_debt     bigint      not null,
    in_debt        int         not null,
    millionaires   int         not null,
    median_worth   bigint      not null
);

-- The other player on a fill (both sides players), and voided fills.
alter table fills add column counterparty_id bigint;
alter table fills add column voided_at timestamptz;
create index fills_counterparty on fills (counterparty_id) where counterparty_id is not null;

-- A world rollback waiting for the next start.
create table world_rollbacks (
    id           bigserial primary key,
    snapshot_id  bigint      not null,
    requested_by bigint      not null,
    requested_at timestamptz not null default now(),
    applied_at   timestamptz
);
