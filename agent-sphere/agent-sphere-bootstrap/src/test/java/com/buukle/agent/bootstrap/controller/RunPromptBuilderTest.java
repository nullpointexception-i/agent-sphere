package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存友好性单测：
 * - buildSystemPrompt 对给定 ctx/todolist 字节确定（不含动态时间），两次调用结果一致；
 * - 当前时间独立为尾部小段 currentTimeText，不进入 system 头部；
 * - buildToolDefinitions 按名称稳定排序（工具顺序变动不影响前缀缓存）。
 */
class RunPromptBuilderTest {

    private final RunPromptBuilder builder = new RunPromptBuilder();

    private RuntimeTool tool(String name) {
        return RuntimeTool.builder()
                .llmToolName(name)
                .description("desc-" + name)
                .parametersSchemaJson("{\"type\":\"object\",\"properties\":{}}")
                .build();
    }

    private KernelContext ctx() {
        InstanceVO instance = new InstanceVO();
        instance.setSystemPrompt("You are a test assistant.");
        return KernelContext.builder()
                .agentInstance(instance)
                .tools(List.of(tool("builtin_5"), tool("cli_1"), tool("builtin_1")))
                .build();
    }

    @Test
    void buildSystemPrompt_isDeterministicAcrossCalls() {
        String first = builder.buildSystemPrompt(ctx(), "todo-list-A");
        String second = builder.buildSystemPrompt(ctx(), "todo-list-A");

        assertEquals(first, second, "同一 ctx/todolist 两次生成必须字节一致，才能命中前缀缓存");
        assertFalse(first.contains("Current server time"), "system prompt 不应包含动态时间");
    }

    @Test
    void buildSystemPrompt_differentTodolist_onlyChangesTail() {
        // todolist 变化只影响尾部待办块；前缀（instance+工具列表+footer）保持一致
        String a = builder.buildSystemPrompt(ctx(), "todos: A");
        String b = builder.buildSystemPrompt(ctx(), "todos: B");

        int prefix = a.indexOf("## 当前待办列表");
        assertTrue(prefix >= 0);
        assertEquals(a.substring(0, prefix), b.substring(0, prefix),
                "待办块之前的前缀必须一致（命中缓存）");
        assertNotEquals(a, b);
    }

    @Test
    void currentTimeText_isIsolatedTailFragment() {
        String time = builder.currentTimeText();

        assertTrue(time.startsWith("\n\nCurrent server time: "));
        assertTrue(time.length() > "\n\nCurrent server time: ".length());
        // 不包含 footer/待办等 system 主体内容，确保仅作为尾部小段
        assertFalse(time.contains("Available tools"));
    }

    @Test
    void buildToolDefinitions_sortedStableByName() {
        List<ToolDefinitionDTO> defs = builder.buildToolDefinitions(ctx().getTools());

        assertEquals(3, defs.size());
        List<String> names = defs.stream()
                .map(ToolDefinitionDTO::getFunction)
                .map(f -> f.getName())
                .toList();
        assertEquals(
                List.of("builtin_1", "builtin_5", "cli_1"),
                names,
                "工具定义应按名称稳定排序，避免遍历顺序变动破坏缓存前缀");
    }
}