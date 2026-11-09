-- ============================================================
-- V167: group avatars are no longer participants
-- ============================================================
-- Group avatars are resolved dynamically from the members' public avatar
-- catalogue, so persisted AGENT participant rows for GROUP conversations are
-- stale. Soft-delete them; DM agent rows must stay because a DM has exactly one
-- agent peer.
--
-- im_participant.delete_flag is boolean (see AGENTS.md: the type differs per
-- table, never assume smallint here).
UPDATE im_participant p
SET delete_flag = true
FROM im_conversation c
WHERE p.conversation_id = c.id
  AND c.type = 'GROUP'
  AND p.participant_type = 'AGENT'
  AND p.delete_flag = false;
