-- Copy of the tracking_event table from monitor-db/init/02-schema.sh, for the in-memory H2 test database.
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
