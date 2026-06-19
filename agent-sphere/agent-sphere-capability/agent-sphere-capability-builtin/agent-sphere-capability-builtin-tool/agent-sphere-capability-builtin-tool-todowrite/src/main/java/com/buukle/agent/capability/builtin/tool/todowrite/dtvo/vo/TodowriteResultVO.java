package com.buukle.agent.capability.builtin.tool.todowrite.dtvo.vo;

import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class TodowriteResultVO extends ExecuteResult {
    private List<TodoItemVO> todos;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodoItemVO {
        private String content;
        private String status;
        private String priority;
    }
}
