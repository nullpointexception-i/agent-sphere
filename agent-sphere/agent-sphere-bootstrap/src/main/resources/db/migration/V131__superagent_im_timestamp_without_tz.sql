-- ============================================================
-- V131: normalize Super-Agent / IM timestamps to TIMESTAMP
-- ============================================================
-- These tables were created with TIMESTAMPTZ while the Java entities use
-- LocalDateTime. PostgreSQL JDBC refuses to read TIMESTAMPTZ into
-- LocalDateTime, so implicit type conversion breaks queries such as
-- im_participant.joined_at.

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND data_type = 'timestamp with time zone'
          AND table_name IN (
              'agent_channel_binding',
              'agent_event',
              'agent_event_delivery',
              'agent_outbound_message',
              'agent_super_agent',
              'agent_thread',
              'agent_thread_subscription',
              'agent_watchdog_run',
              'agent_work_item',
              'im_conversation',
              'im_message',
              'im_participant',
              'im_read_cursor'
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN %I TYPE TIMESTAMP USING %I AT TIME ZONE ''UTC''',
            rec.table_name, rec.column_name, rec.column_name
        );
    END LOOP;
END $$;
