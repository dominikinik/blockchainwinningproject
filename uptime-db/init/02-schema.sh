#!/bin/sh
# Creates the uptime schema in both the main and the test database.
set -e
for db in uptime uptime_test; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<'SQL'
-- One row per second; a second missing from this table is treated as "down".
CREATE TABLE IF NOT EXISTS uptime_record (
    ts         TIMESTAMP(6) WITH TIME ZONE PRIMARY KEY,
    up         BOOLEAN NOT NULL,
    samples    INTEGER NOT NULL,
    up_samples INTEGER NOT NULL
);
SQL
done
