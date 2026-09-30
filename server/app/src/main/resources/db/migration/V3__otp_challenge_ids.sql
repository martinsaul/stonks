-- Challenges are addressed by an unguessable id returned only to the requester, so
-- other people's requests can't replace a player's code or burn its attempts.
alter table otp_challenges add column token text;
update otp_challenges set token = md5(random()::text || id::text) where token is null;
alter table otp_challenges alter column token set not null;
create unique index otp_challenges_token on otp_challenges (token);
alter table otp_challenges add column canonical text;
create index otp_challenges_expires on otp_challenges (expires_at);
create index sessions_expires on sessions (expires_at);
