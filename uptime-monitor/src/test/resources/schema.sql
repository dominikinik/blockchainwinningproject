-- Copy of the tracking_event and uptime_deal tables from monitor-db/init/02-schema.sh, for the in-memory H2 test database.
CREATE TABLE IF NOT EXISTS tracking_event (
    id                BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    service_id        UUID NOT NULL,
    version           BIGINT NOT NULL CHECK (version >= 1),
    type              VARCHAR(32) NOT NULL
        CHECK (type IN ('TrackingStarted', 'HealthCheckSucceeded', 'Downtime', 'InternalErrorHappened', 'TrackingFinished')),
    occurred_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    health_url        TEXT,
    check_interval_ms BIGINT,
    http_status       INTEGER,
    reason            TEXT,
    PRIMARY KEY (service_id, version)
);

-- Deals of the uptime_deal Solana program that this monitor settles as their oracle. One row per deal,
-- updated in place as it advances (PROPOSED -> ACTIVE at acceptance -> SETTLED / FAILED / CANCELLED).
-- starts_at stays NULL until the recipient accepts.
CREATE TABLE IF NOT EXISTS uptime_deal (
    address           VARCHAR(44) PRIMARY KEY,
    service_id        UUID NOT NULL,
    payer             VARCHAR(44) NOT NULL,
    recipient         VARCHAR(44) NOT NULL,
    amount_lamports   BIGINT NOT NULL CHECK (amount_lamports >= 0),
    guarantee_lamports BIGINT NOT NULL CHECK (guarantee_lamports >= 0),
    duration_seconds  BIGINT NOT NULL CHECK (duration_seconds >= 1),
    accept_deadline   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    starts_at         TIMESTAMP(6) WITH TIME ZONE,
    status            VARCHAR(16) NOT NULL
        CHECK (status IN ('PROPOSED', 'ACTIVE', 'SETTLED', 'FAILED', 'CANCELLED')),
    up_checks         BIGINT,
    total_rounds      BIGINT,
    paid_to_recipient BOOLEAN,
    signature         VARCHAR(88),
    sent_at           TIMESTAMP(6) WITH TIME ZONE,
    attempts          INTEGER NOT NULL DEFAULT 0,
    error             TEXT,
    registered_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
