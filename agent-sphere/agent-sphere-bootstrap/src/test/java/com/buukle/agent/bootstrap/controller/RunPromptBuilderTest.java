package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存友好性单测：
 * - buildSystemPrompt 对给定 ctx 字节确定（不含动态时间、不含待办），两次调用结果一致；
 * - 待办列表独立为尾部段 buildTodolistSuffix，不进入 system 头部（messages[0] 前缀稳定）；
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
        String first = builder.buildSystemPrompt(ctx());
        String second = builder.buildSystemPrompt(ctx());

        assertEquals(first, second, "同一 ctx 两次生成必须字节一致，才能命中前缀缓存");
        assertFalse(first.contains("Current server time"), "system prompt 不应包含动态时间");
    }

    @Test
    void buildSystemPrompt_neverContainsTodolist() {
        // 待办已从系统提示剥离为尾部独立段：messages[0] 前缀不随待办变化，缓存稳定
        String systemPrompt = builder.buildSystemPrompt(ctx());

        assertFalse(systemPrompt.contains("## 当前待办列表"),
                "system prompt 不应包含待办列表（待办改为尾部独立段）");
        assertFalse(systemPrompt.contains(RunnerConstants.PROMPT_TODOLIST_HEADER.trim()),
                "system prompt 不应包含待办 header marker");
    }

    @Test
    void buildTodolistSuffix_isStableTailWithHeaderMarker() {
        String a = builder.buildTodolistSuffix("todos: A");
        String b = builder.buildTodolistSuffix("todos: B");

        assertTrue(a.startsWith(RunnerConstants.PROMPT_TODOLIST_HEADER), "待办段以 header marker 开头（供原位定位）");
        assertNotEquals(a, b, "待办内容变化应反映在尾部段");
        assertTrue(builder.isTodolistSlot(a), "header marker 应能被 isTodolistSlot 识别");
        assertFalse(builder.isTodolistSlot("plain system text"));
        assertNull(builder.buildTodolistSuffix("  "), "空白待办应返回 null（调用方移除旧槽）");
        assertNull(builder.buildTodolistSuffix(null));
    }

    @Test
    void currentTimeText_isIsolatedTailFragment() {
        String time = builder.currentTimeText();

        assertTrue(time.startsWith("\n\nCurrent server time: "));
        assertTrue(time.length() > "\n\nCurrent server time: ".length());
        // 不包含 footer/待办等 system 主体内容，确保仅作为尾部小段
        assertFalse(time.contains("Available tools"));
        assertFalse(time.contains("## 当前待办列表"));
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