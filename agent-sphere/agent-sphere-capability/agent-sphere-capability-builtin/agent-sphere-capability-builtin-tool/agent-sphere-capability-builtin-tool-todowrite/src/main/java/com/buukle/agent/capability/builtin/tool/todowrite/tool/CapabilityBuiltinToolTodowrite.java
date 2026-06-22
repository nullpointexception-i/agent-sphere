package com.buukle.agent.capability.builtin.tool.todowrite.tool;

import com.buukle.agent.capability.builtin.dtvo.enums.BuiltinToolEnum;
import com.buukle.agent.capability.builtin.tool.spi.CapabilityBuiltinToolSpi;
import com.buukle.agent.capability.builtin.tool.spi.constant.BuiltinToolConstants;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteContext;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ExecuteResult;
import com.buukle.agent.capability.builtin.tool.spi.dtvo.ToolInfoVO;
import com.buukle.agent.capability.builtin.tool.spi.util.ToolSchemaUtil;
import com.buukle.agent.capability.builtin.tool.todowrite.dtvo.dto.TodowriteExecuteContext;
import com.buukle.agent.capability.builtin.tool.todowrite.dtvo.vo.TodowriteResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CapabilityBuiltinToolTodowrite implements CapabilityBuiltinToolSpi {

    static final String STATUS_PENDING = "pending";
    static final String PRIORITY_MEDIUM = "medium";
    static final String TOOL_DESCRIPTION = """
            Create and maintain a structured task list for the current coding session.
            Call this tool whenever a task starts, completes, or is cancelled — do NOT
            treat it as a one-time initialization. Mark a task as in_progress BEFORE
            working on it (max one at a time). Mark completed ONLY after the work is
            done AND verified. If blocked, keep it in_progress and add a follow-up
            todo. Call again whenever any task status changes.""";

    @Override
    public BuiltinToolEnum getToolType() {
        return BuiltinToolEnum.TODOWRITE;
    }

    @Override
    public boolean needConfig() {
        return false;
    }

    @Override
    public ToolInfoVO getInfo() {
        ToolInfoVO info = new ToolInfoVO();
        info.setName(BuiltinToolConstants.NAME_PREFIX + CapabilityBuiltinToolTodowrite.class.getSimpleName());
        info.setDescription(TOOL_DESCRIPTION);
        info.setDisplayNameCn("待办写入");
        info.setDisplayNameEn("Todo Write");
        info.setParamSchema(ToolSchemaUtil.generateParamSchema(TodowriteExecuteContext.class));
        info.setResponseSchema(ToolSchemaUtil.generateParamSchema(TodowriteResultVO.class));
        return info;
    }

    @Override
    public Class<? extends ExecuteContext> getContextType() {
        return TodowriteExecuteContext.class;
    }

    @Override
    public Class<? extends ExecuteResult> getResultType() {
        return TodowriteResultVO.class;
    }

    @Override
    public ExecuteResult execute(ExecuteContext ctx) {
        TodowriteExecuteContext twCtx = (TodowriteExecuteContext) ctx;
        List<TodowriteResultVO.TodoItemVO> resultTodos = new ArrayList<>();

        List<TodowriteExecuteContext.TodoItemDTO> input = twCtx.getTodos();
        if (input != null) {
            for (TodowriteExecuteContext.TodoItemDTO item : input) {
                resultTodos.add(new TodowriteResultVO.TodoItemVO(
                        item.getContent() != null ? item.getContent() : "",
                        item.getStatus() != null ? item.getStatus() : STATUS_PENDING,
                        item.getPriority() != null ? item.getPriority() : PRIORITY_MEDIUM));
            }
        }
        return new TodowriteResultVO(resultTodos);
    }
}
