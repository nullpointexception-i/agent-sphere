-- ============================================================
-- One-off production migration: split the bole SSO users into their own tenant
--
-- Model after this migration:
--   PLATFORM (1)  platform administration only
--   DEFAULT  (2)  native / self-registered users
--   BOLE          users provisioned by the `bole` identity provider
--
-- Moved: the users, their roles, and their *usable* resources
--        (skills / hub entries / MCP / CLI / instances / bindings / super agents / channel bindings
--         / model providers / model routes / API keys)
-- Kept in DEFAULT: history (sessions, runs, timelines, IM messages/threads, audit log)
-- Archived: mixed bole <-> non-bole IM conversations (status = ARCHIVED)
--
-- Run with a maintenance window (no active runs), inside one transaction:
--   psql -h <host> -p <port> -U <user> -d buukle_agent_2026061101 -v ON_ERROR_STOP=1 -f migrate-bole-tenant.sql
--
-- Pre-checks (read-only, run before the transaction and keep the output):
--   SELECT id, code, name, pid, status FROM sys_tenant ORDER BY id;
--   SELECT count(*) FROM agent_sso_identity WHERE provider_code = 'bole';
--   WITH bole AS (SELECT agent_user_id uid FROM agent_sso_identity WHERE provider_code = 'bole')
--   SELECT 'instances', count(*) FROM agent_instance i
--     JOIN agent_user u ON u.username = i.created_by JOIN bole b ON b.uid = u.id WHERE i.delete_flag = 0;
--   SELECT status, count(*) FROM im_conversation GROUP BY 1;
--   -- model chain owned by bole users (all should move to BOLE):
--   WITH bole AS (SELECT u.username FROM agent_user u
--                 JOIN agent_sso_identity s ON s.agent_user_id = u.id AND s.provider_code = 'bole')
--   SELECT 'routes', count(*) FROM agent_model_route WHERE created_by IN (SELECT username FROM bole)
--   UNION ALL SELECT 'providers', count(*) FROM agent_model_provider WHERE created_by IN (SELECT username FROM bole)
--   UNION ALL SELECT 'api_keys', count(*) FROM agent_api_key k JOIN agent_model_provider p ON p.id = k.provider_id
--     WHERE p.created_by IN (SELECT username FROM bole);
-- ============================================================

\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    v_platform      BIGINT;
    v_default       BIGINT;
    v_bole          BIGINT;
    v_bole_user_role BIGINT;
    v_users         BIGINT[];
    v_usernames     TEXT[];
    v_skill_ids     BIGINT[];
    v_instance_ids  BIGINT[];
    v_agent_ids     BIGINT[];
    v_moved         BIGINT;
BEGIN
    SELECT id INTO v_platform FROM sys_tenant WHERE code = 'PLATFORM' AND delete_flag = 0;
    SELECT id INTO v_default  FROM sys_tenant WHERE code = 'DEFAULT'  AND delete_flag = 0;
    IF v_platform IS NULL OR v_default IS NULL THEN
        RAISE EXCEPTION 'PLATFORM or DEFAULT tenant is missing; aborting';
    END IF;

    -- 1) Tenant -------------------------------------------------------------
    SELECT id INTO v_bole FROM sys_tenant WHERE code = 'BOLE' AND delete_flag = 0;
    IF v_bole IS NULL THEN
        INSERT INTO sys_tenant (code, name, pid, sort_order, status, remark, created_by, updated_by)
        VALUES ('BOLE', '伯乐', v_platform, 2, 'ACTIVE',
                'Bole identity-provider tenant', 'system', 'system')
        RETURNING id INTO v_bole;
        RAISE NOTICE 'Created tenant BOLE id=%', v_bole;
    ELSE
        RAISE NOTICE 'Reusing tenant BOLE id=%', v_bole;
    END IF;

    -- 2) Roles: clone the default tenant ADMIN / USER roles and their permissions
    INSERT INTO sys_role (name, code, description, status, created_by, updated_by, tenant_id)
    SELECT r.name, r.code, r.description, 'ACTIVE', 'system', 'system', v_bole
    FROM sys_role r
    WHERE r.tenant_id = v_default
      AND r.code IN ('ADMIN', 'USER')
      AND NOT EXISTS (
          SELECT 1 FROM sys_role x WHERE x.tenant_id = v_bole AND x.code = r.code
      );

    INSERT INTO sys_role_permission (role_id, permission_id, status, created_by, updated_by)
    SELECT br.id, rp.permission_id, 'ACTIVE', 'system', 'system'
    FROM sys_role r
    JOIN sys_role br ON br.tenant_id = v_bole AND br.code = r.code
    JOIN sys_role_permission rp ON rp.role_id = r.id
    WHERE r.tenant_id = v_default
      AND r.code IN ('ADMIN', 'USER')
      AND NOT EXISTS (
          SELECT 1 FROM sys_role_permission x
          WHERE x.role_id = br.id AND x.permission_id = rp.permission_id
      );

    SELECT id INTO v_bole_user_role FROM sys_role WHERE tenant_id = v_bole AND code = 'USER';

    -- 3) Identity provider -> new tenant (new bole logins land in BOLE) ------
    UPDATE agent_identity_provider
    SET tenant_id = v_bole,
        default_role_id = COALESCE(v_bole_user_role, default_role_id),
        updated_at = NOW()
    WHERE code = 'bole';

    -- 4) Users --------------------------------------------------------------
    SELECT array_agg(DISTINCT s.agent_user_id) INTO v_users
    FROM agent_sso_identity s
    WHERE s.provider_code = 'bole';

    IF v_users IS NULL THEN
        RAISE NOTICE 'No bole SSO identities found; nothing else to migrate';
        RETURN;
    END IF;

    SELECT array_agg(username) INTO v_usernames FROM agent_user WHERE id = ANY(v_users);

    -- 4.1 user -> tenant assignment (keep visibility and current flag)
    UPDATE sys_user_tenant ut
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE ut.user_id = ANY(v_users)
      AND ut.tenant_id = v_default
      AND ut.delete_flag = 0
      AND NOT EXISTS (
          SELECT 1 FROM sys_user_tenant x
          WHERE x.user_id = ut.user_id AND x.tenant_id = v_bole AND x.delete_flag = 0
      );

    -- 4.2 roles: replace default-tenant roles with the matching BOLE roles
    INSERT INTO sys_user_role (user_id, role_id, tenant_id, status, created_by, updated_by, created_at, updated_at)
    SELECT ur.user_id, br.id, v_bole, 'ACTIVE', 'system', 'system', NOW(), NOW()
    FROM sys_user_role ur
    JOIN sys_role dr ON dr.id = ur.role_id
    JOIN sys_role br ON br.tenant_id = v_bole AND br.code = dr.code
    WHERE ur.user_id = ANY(v_users)
      AND dr.tenant_id = v_default
      AND NOT EXISTS (
          SELECT 1 FROM sys_user_role x
          WHERE x.user_id = ur.user_id AND x.role_id = br.id AND COALESCE(x.tenant_id, 0) = v_bole
      );

    DELETE FROM sys_user_role ur
    USING sys_role dr
    WHERE ur.role_id = dr.id
      AND dr.tenant_id = v_default
      AND ur.user_id = ANY(v_users);

    -- 4.3 legacy compatibility column
    UPDATE agent_user SET tenant_id = v_bole WHERE id = ANY(v_users);

    -- 5) Capability content -------------------------------------------------
    UPDATE capability_skill
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE tenant_id = v_default AND created_by = ANY(v_usernames);

    SELECT array_agg(id) INTO v_skill_ids
    FROM capability_skill
    WHERE tenant_id = v_bole AND created_by = ANY(v_usernames);

    IF v_skill_ids IS NOT NULL THEN
        UPDATE capability_skill_hub
        SET tenant_id = v_bole, updated_at = NOW()
        WHERE tenant_id = v_default AND source_skill_id = ANY(v_skill_ids);
    END IF;

    UPDATE capability_skill_follow
    SET tenant_id = v_bole
    WHERE tenant_id = v_default
      AND (user_id = ANY(v_users)
           OR hub_skill_id IN (SELECT id FROM capability_skill_hub WHERE tenant_id = v_bole));

    UPDATE capability_mcp
    SET tenant_id = v_bole
    WHERE tenant_id = v_default AND created_by = ANY(v_usernames);

    UPDATE capability_cli
    SET tenant_id = v_bole
    WHERE tenant_id = v_default AND created_by = ANY(v_usernames);

    -- 6) Instances and their capability bindings ----------------------------
    UPDATE agent_instance
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE tenant_id = v_default
      AND created_by = ANY(v_usernames)
      AND COALESCE(system_managed, FALSE) = FALSE
      AND instance_role <> 'GROUP_ROUTER';

    SELECT array_agg(id) INTO v_instance_ids
    FROM agent_instance
    WHERE tenant_id = v_bole AND created_by = ANY(v_usernames);

    IF v_instance_ids IS NOT NULL THEN
        UPDATE agent_instance_capability
        SET tenant_id = v_bole
        WHERE tenant_id = v_default AND instance_id = ANY(v_instance_ids);
    END IF;

    -- 7) Model providers, routes and API keys --------------------------------
    --    These tables are tenant-scoped too: a route left in DEFAULT is invisible
    --    from BOLE and makes every run fail fast with "Route not found".
    --    A provider moves only when none of its routes belongs to a non-bole user,
    --    so DEFAULT routes never end up pointing at a BOLE provider.
    UPDATE agent_model_provider p
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE p.tenant_id = v_default
      AND p.created_by = ANY(v_usernames)
      AND NOT EXISTS (
          SELECT 1 FROM agent_model_route r
          WHERE r.provider_id = p.id
            AND (r.created_by IS NULL OR NOT (r.created_by = ANY(v_usernames)))
      );

    -- Only move routes whose provider is now resolvable inside BOLE.
    UPDATE agent_model_route r
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE r.tenant_id = v_default
      AND r.created_by = ANY(v_usernames)
      AND (r.provider_id IS NULL
           OR EXISTS (
               SELECT 1 FROM agent_model_provider p
               WHERE p.id = r.provider_id AND p.tenant_id = v_bole
           ));

    UPDATE agent_api_key k
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE k.tenant_id = v_default
      AND k.provider_id IN (SELECT id FROM agent_model_provider WHERE tenant_id = v_bole);

    -- 8) Super agents and their channel config ------------------------------
    UPDATE agent_super_agent
    SET tenant_id = v_bole, updated_at = NOW()
    WHERE tenant_id = v_default AND owner_user_id = ANY(v_users);

    SELECT array_agg(id) INTO v_agent_ids
    FROM agent_super_agent
    WHERE tenant_id = v_bole AND owner_user_id = ANY(v_users);

    IF v_agent_ids IS NOT NULL THEN
        UPDATE agent_channel_binding
        SET tenant_id = v_bole
        WHERE tenant_id = v_default AND super_agent_id = ANY(v_agent_ids);
    END IF;

    -- 9) Archive mixed bole / non-bole IM conversations ---------------------
    --    History stays in DEFAULT; archived rows are hidden from every list and read-only.
    UPDATE im_conversation c
    SET status = 'ARCHIVED', updated_at = NOW()
    WHERE c.status = 'ACTIVE'
      AND EXISTS (
          SELECT 1 FROM im_participant p
          JOIN agent_sso_identity s
            ON s.agent_user_id = p.participant_id AND s.provider_code = 'bole'
          WHERE p.conversation_id = c.id
            AND p.participant_type = 'USER'
            AND p.delete_flag = FALSE
      )
      AND EXISTS (
          SELECT 1 FROM im_participant p
          WHERE p.conversation_id = c.id
            AND p.participant_type = 'USER'
            AND p.delete_flag = FALSE
            AND NOT EXISTS (
                SELECT 1 FROM agent_sso_identity s
                WHERE s.agent_user_id = p.participant_id AND s.provider_code = 'bole'
            )
      );
    GET DIAGNOSTICS v_moved = ROW_COUNT;
    RAISE NOTICE 'Archived % mixed IM conversation(s)', v_moved;

    RAISE NOTICE 'BOLE migration done: tenant=%, users=%, skills=%, instances=%, superAgents=%',
        v_bole, COALESCE(array_length(v_users, 1), 0),
        COALESCE(array_length(v_skill_ids, 1), 0),
        COALESCE(array_length(v_instance_ids, 1), 0),
        COALESCE(array_length(v_agent_ids, 1), 0);
END $$;

-- Post-check (run right after; every count should match the pre-check expectations):
--   SELECT tenant_id, count(*) FROM capability_skill GROUP BY 1;
--   SELECT tenant_id, count(*) FROM agent_instance GROUP BY 1;
--   SELECT tenant_id, count(*) FROM sys_user_tenant WHERE delete_flag = 0 GROUP BY 1;
--   SELECT status, count(*) FROM im_conversation GROUP BY 1;
--   -- model chain must have moved (BOLE rows appear, all BOLE instances resolve):
--   SELECT 'routes' t, tenant_id, count(*) FROM agent_model_route GROUP BY 2
--   UNION ALL SELECT 'providers', tenant_id, count(*) FROM agent_model_provider GROUP BY 2
--   UNION ALL SELECT 'api_keys', tenant_id, count(*) FROM agent_api_key GROUP BY 2 ORDER BY 1,2;
--   -- expect 0: every BOLE instance route resolves inside BOLE
--   SELECT count(*) FROM agent_instance i
--   JOIN sys_tenant t ON t.code = 'BOLE'
--   LEFT JOIN agent_model_route r ON r.id = i.model_route_id AND r.tenant_id = t.id
--   WHERE i.tenant_id = t.id AND i.delete_flag = 0
--     AND i.model_route_id IS NOT NULL AND r.id IS NULL;

COMMIT;
