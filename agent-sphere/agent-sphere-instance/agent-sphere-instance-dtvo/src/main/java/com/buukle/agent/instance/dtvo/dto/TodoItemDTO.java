package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

@Data
public class TodoItemDTO {
    private String content;
    private String status;
    private String priority;
    /** Source skill id (see agent_session_todo.skill_id). */
    private Long skillId;
    /** Source skill step id, e.g. step1 / step1.1 (see agent_session_todo.step_id). */
    private String stepId;
}
