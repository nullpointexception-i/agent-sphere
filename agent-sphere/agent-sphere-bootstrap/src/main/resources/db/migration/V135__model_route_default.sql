-- ============================================================
-- V135: default model route flag
-- ============================================================

ALTER TABLE agent_model_route
    ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill one DeepSeek route per owner so existing users still have a default.
WITH deepseek_routes AS (
    SELECT id,
           created_by,
           ROW_NUMBER() OVER (PARTITION BY COALESCE(created_by, 'system') ORDER BY id) AS rn
    FROM agent_model_route
    WHERE delete_flag = 0
      AND company = 'deepseek'
)
UPDATE agent_model_route r
SET is_default = TRUE
FROM deepseek_routes d
WHERE r.id = d.id
  AND d.rn = 1
  AND NOT EXISTS (
      SELECT 1
      FROM agent_model_route existing
      WHERE existing.delete_flag = 0
        AND existing.is_default = TRUE
        AND COALESCE(existing.created_by, 'system') = COALESCE(r.created_by, 'system')
  );

CREATE UNIQUE INDEX IF NOT EXISTS uk_route_default_owner
    ON agent_model_route ((COALESCE(created_by, 'system')))
    WHERE is_default = TRUE AND delete_flag = 0;
