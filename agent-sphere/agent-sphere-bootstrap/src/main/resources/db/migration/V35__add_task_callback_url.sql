-- V35: agent_task 增加回调地址列（Bole tasks 终态回调）

ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS callback_url VARCHAR(500);
