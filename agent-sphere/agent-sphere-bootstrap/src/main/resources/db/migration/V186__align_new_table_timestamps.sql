-- Align new application/project/notification tables with the project's
-- LocalDateTime convention. Existing tables use TIMESTAMP without time zone.

DO $$
DECLARE
    target RECORD;
BEGIN
    FOR target IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND data_type = 'timestamp with time zone'
          AND table_name IN (
              'biz_application',
              'biz_application_scene',
              'biz_application_run',
              'biz_application_grant',
              'biz_application_member',
              'sys_i18n_text',
              'sys_user_locale',
              'agent_entity_binding',
              'pm_project',
              'pm_project_member',
              'pm_requirement',
              'pm_requirement_activity',
              'im_notification'
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN %I TYPE TIMESTAMP USING %I AT TIME ZONE ''UTC''',
            target.table_name, target.column_name, target.column_name);
    END LOOP;
END $$;
