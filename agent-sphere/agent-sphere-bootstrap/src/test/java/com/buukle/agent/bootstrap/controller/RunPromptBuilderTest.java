package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.instance.dtvo.vo.InstanceVO;
import com.buukle.agent.model.dtvo.dto.complete.ToolDefinitionDTO;
import com.buukle.agent.runtime.kernel.constants.ChatClarification;
import com.buukle.agent.runtime.kernel.constants.FinishConstants;
import com.buukle.agent.runtime.kernel.constants.RunnerConstants;
import com.buukle.agent.runtime.kernel.port.KernelContext;
import com.buukle.agent.runtime.kernel.port.vo.RuntimeTool;
import com.buukle.agent.runtime.kernel.prompt.RunPromptBuilder;
import com.buukle.agent.util.json.ToolCallReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存友好性单测：
 * - buildSystemPrompt 对给定 ctx 字节确定（不含动态时间、不含待办），两次调用结果一致；
 * - 待办列表独立为尾部段 buildTodolistSuffix，不进入 system 头部（messages[0] 前缀稳定）；
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
    void buildSystemPrompt_listsNamesOnly_noDuplicateDescriptions() {
        String systemPrompt = builder.buildSystemPrompt(ctx());

        assertTrue(systemPrompt.contains("builtin_1"), "system 清单保留工具名");
        assertFalse(systemPrompt.contains("desc-builtin_1"),
                "完整 description 由请求 tools 提供，system 里不再重复");
    }

    @Test
    void clarificationRulesFollowRegisteredTools() {
        String withClarification = builder.buildSystemPrompt(KernelContext.builder()
                .agentInstance(new InstanceVO())
                .tools(List.of(tool(ChatClarification.CLARIFICATION_TOOL_NAME)))
                .build());
        assertTrue(withClarification.contains("CLARIFICATION RULE"));
        assertFalse(withClarification.contains("NO USER QUESTIONS"));

        String withoutClarification = builder.buildSystemPrompt(ctx());
        assertTrue(withoutClarification.contains("NO USER QUESTIONS"),
                "没注册 clarification 工具时，必须明确禁止向用户提问");
        assertFalse(withoutClarification.contains("CLARIFICATION RULE"));
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

    @Test
    void buildToolDefinitions_injectsCallReasonButNotFinish() {
        List<ToolDefinitionDTO> defs = builder.buildToolDefinitions(
                List.of(tool("builtin_5"), tool(FinishConstants.TOOL_NAME)));

        Map<String, Object> normalParams = defs.stream()
                .filter(d -> "builtin_5".equals(d.getFunction().getName()))
                .findFirst().orElseThrow().getFunction().getParameters();
        Map<String, Object> normalProps = (Map<String, Object>) normalParams.get("properties");
        assertTrue(normalProps.containsKey(ToolCallReason.REASON_KEY),
                "可见工具 schema 必须注入 _reason");

        Map<String, Object> finishParams = defs.stream()
                .filter(d -> FinishConstants.TOOL_NAME.equals(d.getFunction().getName()))
                .findFirst().orElseThrow().getFunction().getParameters();
        Map<String, Object> finishProps = (Map<String, Object>) finishParams.get("properties");
        assertFalse(finishProps.containsKey(ToolCallReason.REASON_KEY),
                "finish 是内部隐藏工具，不注入调用理由");
    }

    @Test
    void buildLoopBudget_reportsCurrentLoopAndRemainingIncludingThisOne() {
        String text = builder.buildLoopBudget(5, 123, 128);

        assertTrue(text.startsWith(RunnerConstants.LOOP_BUDGET_PREFIX), "预算段以 marker 开头（供跨轮定位并清除）");
        assertTrue(text.contains("5/128"), "应报出当前轮序号与生效上限");
        assertTrue(text.contains("123"), "应报出剩余轮数");
        assertTrue(builder.isLoopBudget(text), "marker 应能被 isLoopBudget 识别");
        assertFalse(builder.isLoopBudget("plain system text"));
        assertFalse(builder.isLoopBudget(null));
        assertFalse(builder.isLoopBudget(RunnerConstants.FINAL_TURN_INSTRUCTION),
                "硬收口指令不是预算段，否则会被当作预算一起清掉");
    }

    @Test
    void buildLoopBudget_neverReportsZeroRemaining() {
        // 最后一轮：remaining = 1（含本轮）。出现「剩 0 轮」会让模型以为自己已无路可走而放弃收尾
        String last = builder.buildLoopBudget(128, 1, 128);

        assertTrue(last.contains("1 turn(s) remaining"), "末轮应报 1 轮而非 0 轮");
        assertFalse(last.contains("0 turn(s) remaining"));
    }

    @Test
    void buildLoopBudget_addsUrgencyOnlyWhenCloseToLimit() {
        String plenty = builder.buildLoopBudget(3, 125, 128);
        String close = builder.buildLoopBudget(124, RunnerConstants.LOOP_BUDGET_LOW_THRESHOLD, 128);

        assertFalse(plenty.contains("converge"), "轮数充裕时不该催促，避免模型过早收口");
        assertTrue(close.contains("converge"), "逼近上限应显式催促收口，否则会被 loop cap 截成半成品");
    }
}