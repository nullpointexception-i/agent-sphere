-- ============================================================
-- V145: Router reply protocol switches to "final content is the reply"
-- ============================================================
-- Plain Q&A replies are now written as assistant content; the platform auto-persists
-- them as an outbound message. send_message stays reserved for cards / proactive
-- notices / dispatch acks. Only stored prompts that exactly contain the earlier
-- built-in default are rewritten; custom prompts are left untouched.

UPDATE agent_instance
SET system_prompt = replace(
    replace(
      replace(
        replace(
          system_prompt,
          '对外只通过 send_message 回复，不要输出内部推理。',
          '普通问答：直接把给用户的最终回复写在正文里，系统会自动把它作为消息发送；不要在正文中输出内部推理。'
        ),
        '用户直接提问：能直接回答就调用 send_message 回复。',
        '用户直接提问：能直接回答就直接输出正文。'
      ),
      '先调用 dispatch_worker 派发具体目标，再用 send_message 简短告知用户已开始处理。',
      '先调用 dispatch_worker 派发具体目标，然后在正文里简短告知用户已开始处理。'
    ),
    '基于报告总结结果后用 send_message 回复，不要重复派发同一个任务。',
    '基于报告总结结果后用正文回复，不要重复派发同一个任务。'
)
WHERE instance_role = 'ROUTER'
  AND business_type = 'SUPER_AGENT'
  AND system_prompt LIKE '%对外只通过 send_message 回复%';
