BEGIN;

-- Exact instants are ISO-8601 strings/JSON. Integer seconds are broad-selection indexes only.
CREATE TABLE IF NOT EXISTS tracking_session (
    id UUID PRIMARY KEY,
    started_at TEXT NOT NULL,
    stopped_at TEXT,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'STOPPING', 'STOPPED', 'INTERRUPTED')),
    committed_through TEXT,
    start_second BIGINT NOT NULL,
    stop_second BIGINT,
    CHECK ((status = 'ACTIVE') = (stopped_at IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS tracking_one_open_session
    ON tracking_session ((1)) WHERE status IN ('ACTIVE', 'STOPPING');
CREATE INDEX IF NOT EXISTS tracking_session_bounds ON tracking_session (start_second, stop_second);

CREATE TABLE IF NOT EXISTS tracking_event (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES tracking_session(id),
    type TEXT NOT NULL CHECK (type IN ('START', 'STOP')),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    UNIQUE (session_id, type)
);

CREATE TABLE IF NOT EXISTS uptime_event (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES tracking_session(id),
    bucket_start TEXT NOT NULL,
    start_second BIGINT NOT NULL,
    end_second BIGINT NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    UNIQUE (session_id, bucket_start),
    UNIQUE (id, session_id)
);
CREATE INDEX IF NOT EXISTS uptime_event_bounds ON uptime_event (start_second, end_second);

CREATE TABLE IF NOT EXISTS bad_event (
    id UUID PRIMARY KEY,
    uptime_event_id UUID NOT NULL,
    session_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    FOREIGN KEY (uptime_event_id, session_id) REFERENCES uptime_event(id, session_id),
    UNIQUE (uptime_event_id, ordinal)
);
CREATE INDEX IF NOT EXISTS bad_event_parent ON bad_event (uptime_event_id);

COMMIT;
