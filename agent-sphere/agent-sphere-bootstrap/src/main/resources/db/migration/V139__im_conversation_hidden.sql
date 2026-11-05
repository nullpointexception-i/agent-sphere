-- Per-user conversation visibility. Deleting a chat from the list only hides it
-- for that user; the shared conversation and message history remain intact.
ALTER TABLE im_conversation_setting
    ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_im_conversation_setting_hidden
    ON im_conversation_setting (user_id, hidden) WHERE delete_flag = FALSE;
