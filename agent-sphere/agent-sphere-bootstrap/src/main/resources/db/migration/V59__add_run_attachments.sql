-- agent_run.attachments — 聊天图片附件引用落库（[{fileKey, contentType}] JSON 数组），
-- 供 Timeline USER 行读取侧解析回显（正文仍以 userMessage 文本为准，附件字节查 agent_file_store）。
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS attachments TEXT;