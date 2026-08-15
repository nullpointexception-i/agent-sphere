-- ============================================================
-- agent_task 多副本 DB 化轮询所需列
-- ============================================================
ALTER TABLE agent_task
    ADD COLUMN poll_phase VARCHAR(20) NULL,
    ADD COLUMN polled_at  TIMESTAMP   NULL,
    ADD COLUMN started_at TIMESTAMP   NULL;
