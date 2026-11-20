-- Operations: the last time a periodic job outside the app succeeded. The backup container writes
-- name = 'backup' after each backup is safely off the server; the app alerts the operator when it
-- gets old (SPEC section 8). Not tenant-owned: no clinic_id.
create table ops_heartbeat (
    name text        primary key,
    at   timestamptz not null
);
