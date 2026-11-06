-- ============================================================
-- V146: forbid send_message for ordinary Router Q&A
-- ============================================================
-- V145 moved plain replies to assistant content. Models sometimes still call
-- send_message for a simple answer, which adds a tool round-trip and makes the
-- streamed reply differ from the authoritative message. Make the rule explicit.

UPDATE agent_instance
SET system_prompt = replace(
    system_prompt,
    '普通问答：直接把给用户的最终回复写在正文里，系统会自动把它作为消息发送；不要在正文中输出内部推理。',
    '普通问答：直接把给用户的最终回复写在正文里，禁止调用 send_message，系统会自动把它作为消息发送；不要在正文中输出内部推理。'
)
WHERE instance_role = 'ROUTER'
  AND business_type = 'SUPER_AGENT'
  AND system_prompt LIKE '%普通问答：直接把给用户的最终回复写在正文里，系统会自动把它作为消息发送%';
