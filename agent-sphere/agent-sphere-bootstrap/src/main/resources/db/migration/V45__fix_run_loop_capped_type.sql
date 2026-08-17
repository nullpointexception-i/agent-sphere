-- 修正 V44：MyBatis-Plus 将 Boolean 字段绑定为 PG boolean，小整数列会报类型不匹配
ALTER TABLE agent_run
    ALTER COLUMN loop_capped TYPE BOOLEAN USING loop_capped <> 0,
    ALTER COLUMN loop_capped SET DEFAULT FALSE;