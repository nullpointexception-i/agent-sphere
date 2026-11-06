-- Persist the no-clarification flag on the run snapshot.
-- Before V100 it lived only on the in-memory RunVO set by RunServiceImpl#createRun,
-- so any resume path (claimForResume / getRun / approval or wait resume) rebuilt the
-- run from the DB with the flag missing. ContextPreparer then saw noClarification=false
-- and stopped registering the finish tool, leaving STRICT_FINISH runs with no legal way
-- to complete (worker runs looped until manually stopped).
ALTER TABLE agent_run ADD COLUMN no_clarification BOOLEAN NOT NULL DEFAULT FALSE;
