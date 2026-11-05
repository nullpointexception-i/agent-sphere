-- V137 created im_conversation_setting with TIMESTAMPTZ while the entity uses
-- LocalDateTime. Align it with the other IM tables normalized by V131.
-- Conditional so re-running after a manual fix is a no-op.
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'im_conversation_setting'
          AND data_type = 'timestamp with time zone'
    LOOP
        EXECUTE format(
            'ALTER TABLE im_conversation_setting ALTER COLUMN %I TYPE TIMESTAMP USING %I AT TIME ZONE ''UTC''',
            rec.column_name, rec.column_name
        );
    END LOOP;
END $$;
