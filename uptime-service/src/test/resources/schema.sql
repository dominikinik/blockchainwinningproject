-- Copy of the uptime_record table from uptime-db/init/02-schema.sh. Keep the two in step.
CREATE TABLE IF NOT EXISTS uptime_record (
    ts         TIMESTAMP(6) WITH TIME ZONE PRIMARY KEY,
    up         BOOLEAN NOT NULL,
    samples    INTEGER NOT NULL,
    up_samples INTEGER NOT NULL,
        partial_coverage BOOLEAN,
        failures JSONB
);
