-- Apply to both uptime and uptime_test. No backfill: legacy details remain unknown.
BEGIN;
ALTER TABLE uptime_record ADD COLUMN IF NOT EXISTS partial_coverage BOOLEAN;
ALTER TABLE uptime_record ADD COLUMN IF NOT EXISTS failures JSONB;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'uptime_record'::regclass
                   AND conname = 'uptime_record_failures_array') THEN
        ALTER TABLE uptime_record ADD CONSTRAINT uptime_record_failures_array
            CHECK (failures IS NULL OR jsonb_typeof(failures) = 'array');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'uptime_record'::regclass
                   AND conname = 'uptime_record_details_pair') THEN
        ALTER TABLE uptime_record ADD CONSTRAINT uptime_record_details_pair
            CHECK ((partial_coverage IS NULL) = (failures IS NULL));
    END IF;
END $$;
COMMIT;
