-- ============================================================
-- V142: normalize system_dic timestamps to TIMESTAMP
-- ============================================================
-- V140 created the table with TIMESTAMPTZ while the Java entity uses
-- LocalDateTime. PostgreSQL JDBC refuses to read TIMESTAMPTZ into
-- LocalDateTime, so dictionary queries fail at runtime.

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND data_type = 'timestamp with time zone'
          AND table_name = 'system_dic'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN %I TYPE TIMESTAMP USING %I AT TIME ZONE ''UTC''',
            rec.table_name, rec.column_name, rec.column_name
        );
    END LOOP;
END $$;
