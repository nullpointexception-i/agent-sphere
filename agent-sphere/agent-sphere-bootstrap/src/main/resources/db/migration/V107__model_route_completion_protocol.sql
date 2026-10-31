-- V107: completion protocol belongs to the model route (model capability default),
-- with an optional instance override. agent_run pins the resolved value per run.
ALTER TABLE agent_model_route
    ADD COLUMN IF NOT EXISTS completion_protocol VARCHAR(30) NOT NULL DEFAULT 'STRICT_FINISH';

-- NULL = follow the model route. Drop the V106 defaults so omitted inserts stay NULL.
ALTER TABLE agent_instance
    ALTER COLUMN completion_protocol DROP DEFAULT;
ALTER TABLE agent_instance
    ALTER COLUMN completion_protocol DROP NOT NULL;

-- V106 was never released; reset the defaulted values to "inherit from model".
UPDATE agent_instance SET completion_protocol = NULL;

-- A run is resolved at first entry and must remain pinned across resume/fallback.
ALTER TABLE agent_run
    ALTER COLUMN completion_protocol DROP DEFAULT;
ALTER TABLE agent_run
    ALTER COLUMN completion_protocol DROP NOT NULL;
