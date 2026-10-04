#!/bin/sh
# Creates the event store and deal schema in both the main and the test database.
set -e
for db in monitor monitor_test; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<'SQL'
-- Append-only event store: one stream per tracked service, ordered by version (1, 2, ...).
-- The primary key makes a concurrent append of the same version fail (optimistic concurrency).
-- Columns that an event type doesn't use stay NULL.
CREATE TABLE IF NOT EXISTS tracking_event (
    id                BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    service_id        UUID NOT NULL,
    version           BIGINT NOT NULL CHECK (version >= 1),
    type              VARCHAR(32) NOT NULL
        CHECK (type IN ('TrackingStarted', 'Downtime', 'InternalErrorHappened', 'TrackingFinished')),
    occurred_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    health_url        TEXT,
    check_interval_ms BIGINT,
    http_status       INTEGER,
    reason            TEXT,
    PRIMARY KEY (service_id, version)
);

-- Deals of the uptime_deal Solana program that this monitor settles as their oracle. One row per deal,
-- updated in place as it advances (PROPOSED -> ACTIVE at acceptance -> SETTLED / FAILED / CANCELLED).
-- starts_at stays NULL until the recipient accepts. service_id is the deal's own tracked service, which checks
-- health_url from the acceptance until the deal is finished.
CREATE TABLE IF NOT EXISTS uptime_deal (
    address           VARCHAR(44) PRIMARY KEY,
    service_id        UUID NOT NULL,
    health_url        TEXT NOT NULL,
    payer             VARCHAR(44) NOT NULL,
    recipient         VARCHAR(44) NOT NULL,
    amount_lamports   BIGINT NOT NULL CHECK (amount_lamports >= 0),
    guarantee_lamports BIGINT NOT NULL CHECK (guarantee_lamports >= 0),
    duration_seconds  BIGINT NOT NULL CHECK (duration_seconds >= 1),
    accept_deadline   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    starts_at         TIMESTAMP(6) WITH TIME ZONE,
    status            VARCHAR(16) NOT NULL
        CHECK (status IN ('PROPOSED', 'ACTIVE', 'SETTLED', 'FAILED', 'CANCELLED')),
    up_seconds        BIGINT,
    total_seconds     BIGINT,
    paid_to_recipient BOOLEAN,
    signature         VARCHAR(88),
    sent_at           TIMESTAMP(6) WITH TIME ZONE,
    attempts          INTEGER NOT NULL DEFAULT 0,
    error             TEXT,
    registered_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- Databases created before deals had their own health URL: earlier deals were measured on the provider.
-- The default only fills existing rows; afterwards the column matches a freshly created one.
ALTER TABLE uptime_deal ADD COLUMN IF NOT EXISTS health_url TEXT NOT NULL DEFAULT 'http://localhost:8080/api/health';
ALTER TABLE uptime_deal ALTER COLUMN health_url DROP DEFAULT;
SQL
done
