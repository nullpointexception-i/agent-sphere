-- Shorten legacy project/requirement codes for readability.
UPDATE pm_project
SET project_key = 'PRJ-' || id,
    updated_by = 'system',
    updated_at = now()
WHERE length(project_key) > 12;

UPDATE pm_requirement
SET requirement_key = 'REQ-' || id,
    updated_by = 'system',
    updated_at = now()
WHERE length(requirement_key) > 12;
