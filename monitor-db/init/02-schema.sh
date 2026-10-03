#!/bin/sh
# Creates the event store schema in both the main and the test database.
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
SQL
done
