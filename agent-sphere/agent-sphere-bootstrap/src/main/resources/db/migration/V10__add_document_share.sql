ALTER TABLE agent_document
  ADD COLUMN share_token VARCHAR(64) NULL,
  ADD CONSTRAINT uk_document_share_token UNIQUE (share_token);
