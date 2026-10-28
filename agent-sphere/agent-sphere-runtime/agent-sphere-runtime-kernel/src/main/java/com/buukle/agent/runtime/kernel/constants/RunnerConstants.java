package com.buukle.agent.runtime.kernel.constants;

public final class RunnerConstants {
    public static final String TOOL_TYPE_FUNCTION = "function";
    public static final String EMPTY_JSON_ARGS = "{}";
    public static final long DEFAULT_MAX_INPUT_TOKENS = 1_000_000L;
    public static final long DEFAULT_MAX_OUTPUT_TOKENS = 128_000L;
    /**
     * 字符数 → token 的估算除数。中文实际约 1.5–1.8 字/token（JSON 里的中文值同样偏密），
     * 旧的 3 会显著低估、导致压缩总是晚于真实溢出（线上 32k 路由实测请求 32,852 token 时估算才 ~20k）。
     */
    public static final double TOKEN_ESTIMATE_DIVISOR = 1.8;
    public static final int TOOL_RESULT_MAX_CHARS = 2000;
    public static final int COMPACTION_TOOL_RESULT_MAX_CHARS = 500;
    public static final int TOOL_RESULT_HISTORY_CHARS_BUDGET = 50000;
    public static final String FALLBACK_CALL_ID_PREFIX = "hist_";
    public static final String TOOL_RESULT_BUDGET_OMITTED = "[Tool result omitted due to context limit]";
    public static final String PROMPT_CURRENT_TIME = "\n\nCurrent server time: ";
    public static final String PROMPT_TOOLS_HEADER = "\n\nAvailable tools:\n";
    /** 待办列表尾部段：header 同时作为槽位定位 marker（内容以 header 开头的消息即待办槽）。 */
    public static final String PROMPT_TODOLIST_HEADER = "\n\n## 进度记录（不代表任务完成）\n";
    public static final String PROMPT_TODOLIST_INSTRUCTION =
            "\n\n**请根据任务状态变更及时更新此列表，不要等待用户提醒。"
            + "注意：此列表只是进度记录，**不构成任务完成判据** —— 任务是否完成由任务指令"
            + "（技能步骤 / 期望输出 / 收尾条件）决定，而不是由本列表全部 completed 决定。"
            + "它由你维护：采用技能建立时按技能步骤填写，否则由你自行规划；"
            + "任何更新都必须回传完整清单（该工具是整体替换）。**";
    public static final String PROMPT_TOOLS_FOOTER = """
            
            TASK LIST: keep exactly ONE task list via the task list tool. It is the plan for the
            WHOLE task and must always contain EVERYTHING still to do — regardless of which skill
            you have read.

            - If NO skill applies: create your own task list covering the user's request before
              acting, and restructure it freely (add / remove / reorder / re-scope) as work evolves.
            - If a skill IS the whole plan: read it (skill_library) first, then lay the list out
              from its steps — every `## stepN` and `### stepN.M`, in order — writing each item's
              content as plain numbering derived from the step id (`N. <title>` for stepN,
              `N.M <title>` for stepN.M; drop the word "step"; no `#` marks). For each such item
              also set its `skillId` (the skill id returned by skill_library) and `stepId`
              (the step id, e.g. step1 / step1.1).
            - If a skill only details ONE OR A FEW steps of your EXISTING plan: KEEP the existing
              list and use that skill as the method for those steps — only add `skillId`/`stepId`
              to the relevant items (optionally nest the skill's sub-steps under them). Do NOT
              replace the plan with the skill's steps and do NOT drop unrelated items.

            Before executing any item that carries `skillId` + `stepId`, read that step's body with
            skill_library(action=get, skillId=<skillId>, sectionHeading=<stepId>) — the outline is
            only titles; the method lives in the section (an H2 section returns its H3 children too).

            The task list tool REPLACES the entire list — always pass the COMPLETE list (existing
            items plus any additions/changes); anything you omit is deleted. Keep it in sync with
            actual progress whenever a task's status changes (pending → in_progress → completed / cancelled).

            A todo's status is PROGRESS ONLY. Marking every todo 'completed' does NOT mean the
            task is finished — completion is decided by the task instructions and, when a skill
            applies, the skill's closing conditions and expected output. Do not stop early just
            because the list looks done.

            You may call multiple INDEPENDENT tools in a single turn — they will
            run in parallel. But tools with sequential dependencies (e.g., fetch
            a URL first, then read its content) MUST be called in separate turns.
            Mark dependent tasks as 'pending' in the todo list until their turn.
            
            IMPORTANT: When calling the task list tool, the JSON MUST be strictly valid.
            Each key-value pair MUST be separated by a comma. Example:
            {"todos": [{"content": "...", "status": "pending", "priority": "high"}]}
            Never omit commas between object properties.

            CRITICAL: When you need to call a tool, you MUST use the function calling API
            (respond with tool_calls in the API format). Do NOT describe or plan tool calls
            as JSON or text in your reply — invoke the tool directly through the available
            function definitions.


            CRITICAL — EXECUTE, DO NOT ASK: When the user gives you a clear instruction
            (e.g., 'search for X', 'check Y', 'look up Z'), EXECUTE it immediately.
            Call the necessary tools and provide the result. Do NOT ask the user
            'what do you want to do' or 'what would you like to search' — they already
            told you. If you need exactly one missing detail, use the clarification
            tool ONCE, then ACT on the response. Never ask multiple consecutive
            clarifications or questions. NEVER respond with a question when the user
            has already stated their intent.""";
    /** 仅在注册了 clarification 工具时追加（避免对不存在的工具发指令）。 */
    public static final String PROMPT_CLARIFICATION_RULES = """

            CLARIFICATION RULE: If you need the user to confirm an action, choose between options,
            or provide information, you MUST call the clarification tool (choice / confirm / input).
            NEVER pose questions in plain text — the user can only answer through that tool.

            CLARIFICATION AFTERCARE: After the user responds, immediately update the task list to
            reflect the new information, then execute the next step. Do NOT ask another
            clarification on the same topic.""";

    /** 未注册 clarification 工具（自主任务）时的权威规则：不得向用户提问。 */
    public static final String PROMPT_NO_CLARIFICATION_RULE = """

            NO USER QUESTIONS: You cannot ask the user for input in this run. If you are blocked
            by missing external information or permission, state exactly what is needed in your final
            answer (or call `finish(status=blocked, summary=...)` when that tool is available).
            Otherwise proceed with the best available information.""";
    public static final String COMPACTION_USER_PREFIX = "User: ";
    public static final String COMPACTION_ASSISTANT_PREFIX = "Assistant: ";
    public static final String COMPACTION_NEWLINE = "\n";
    public static final String HISTORY_SUMMARY_PREFIX = "[Conversation summary]\n";
    /** todowrite 工具回执：完整清单已通过尾部待办槽下发，回执只保留短确认。 */
    public static final String TODO_WRITE_ACK = "{\"ok\":true,\"message\":\"task list updated\"}";
    /**
     * 硬收口指令的提前量：剩余轮数 ≤ 该值时即注入 {@link #FINAL_TURN_INSTRUCTION}。
     *
     * <p>曾是「只在最后一轮（n-1）注入」。但收口指令只是<b>提示</b>、不强制执行，而模型往往
     * 真的在最后一轮才收工 —— 线上 run 1575/1569 两条都正好落在最后一轮。留给模型 1 轮收尾
     * 太紧：它要么来不及把结论写完整，要么只能把"被打断"当成交付理由
     * （run 1569 就是 `status:"INCOMPLETE"`）。提前到 n-3 让它有 3 轮完成收尾与润色。
     *
     * <p><b>注意</b>：这不影响 loop cap 的执行时机（仍是 n-1），只是把"该收工了"的信号提前。
     */
    public static final int FINAL_TURN_INSTRUCTION_LEAD = 3;

    /**
     * 终态轮收口指令：作为尾部 system 消息追加（不修改 index 0，保持前缀缓存稳定）。
     *
     * <p>措辞用「接近上限 / 剩余轮次有限」而非「这是你的最后一轮」—— 因为它在 n-3 就开始注入，
     * 说"最后一轮"是假的，模型可能因此误判还剩一次机会而继续调工具。
     */
    public static final String FINAL_TURN_INSTRUCTION =
            "\n\n**IMPORTANT: You are approaching the hard loop limit. From now on you must NOT call any more"
                    + " tools except `finish`. Use your remaining turns to deliver a complete, well-structured"
                    + " final answer, then call `finish(status, summary)` to end the run.**";

    /**
     * 每轮 loop 预算预告的 marker：同时用于「跨轮定位并清除上一轮副本」与文案前缀。
     *
     * <p>预算文案每轮都变（当前第几轮 / 还剩几轮），且工具轮会往 messages 尾部追加
     * assistant/tool/观察消息（SessionRunner#runInternal），所以**不能原位替换** ——
     * 上一轮的副本会被挤进历史中间，既浪费 context 又会与新文案互相矛盾。
     * 必须每轮先按本 marker 全部清除再在尾部重新追加，保证全列表恒只有一份。
     */
    public static final String LOOP_BUDGET_PREFIX = "\n\n[LOOP BUDGET] ";

    /**
     * 静默追问上限：用过工具之后，连续这么多轮「没有任何工具调用」即判 run 失败。
     *
     * <p>「没有工具调用」是二义信号（做完 / 放弃），agentic 流程里只允许用 {@code finish} 显式收口，
     * 所以沉默只能是异常。给 2 轮容错是照顾小模型偶发的「只说不做」。
     */
    public static final int MAX_SILENT_TURNS = 2;

    /** 静默追问消息的 marker（每轮重建尾部时据此清理旧副本，避免累积）。 */
    public static final String SILENT_TURN_NUDGE_PREFIX = "\n\n[NO TOOL CALL] ";

    public static final String SILENT_TURN_NUDGE_TEXT =
            "You ended your turn WITHOUT calling any tool. If the task is complete you MUST call `finish(status, summary)`;"
                    + " otherwise continue by calling the next tool. Replying with plain text alone is treated as an error.";

    /** 剩余轮数低于该值时，预算预告追加一句紧迫提醒（模型对「还剩 100 轮」无感，需要显式推一把）。 */
    public static final int LOOP_BUDGET_LOW_THRESHOLD = 5;

    /**
     * 单轮 LLM 失败后的重试退避基数（毫秒），按 {@code 基数 × 2^(attempt-1)} 递增并封顶。
     *
     * <p>曾经是固定 {@code sleep(1000)} + 只有 1 次重试（合计 2 次尝试）。线上实测模型端
     * 会<b>突发性连续</b>秒回错误：有 run 在 9 秒内连撞 3 次即耗尽重试预算而 FAILED，
     * 只有一个恰好只撞 2 次的 run 侥幸活下来。固定 1s 退避撞上突发窗口反而会在同一秒
     * 再打一次，把同一次抖动放大成连续失败。
     */
    public static final long LLM_TURN_RETRY_BASE_MS = 1_000L;
    /** 重试退避上限（毫秒），避免长重试链把单轮拖到不可接受。 */
    public static final long LLM_TURN_RETRY_MAX_MS = 8_000L;

    /** delegate 子 Agent 工作流指引（仅当注册了 delegate 工具时追加，避免无谓 token）。 */
    public static final String PROMPT_SUBAGENT_WORKFLOW = """


            DELEGATE WORKFLOW: Skills are NOT direct tools.
            1) Use skill_library to discover and inspect skills (list/search; get by outline, section, or line range for progressive loading).
            2) Then use delegate to execute the work. call delegate with mode=main to run inline in this loop (no sub-agent);
               use mode=subagent ONLY when isolation is warranted (noisy intermediate context, long-running work, or independent parallel lanes).
               Provide tasks[] (requires mode=subagent) for a DAG of parallel sub-agents; results are aggregated by key.
               A sub-agent inherits the full toolset and the parent model route; optionally pass agentRef=instance:<id> to prefix its system prompt.
            3) You do NOT need a sub-agent for simple work — when no isolation is needed, just finish the task yourself in this loop.""";

    // ---- SessionRunner magic strings ----
    public static final String JSON_ERROR_TOOL_LOST = "{\"error\":\"Tool execution lost\"}";
    public static final String FALLBACK_COMPLETE_MSG = "{\"status\":\"ERROR\",\"reason\":\"No completion content produced\"}";
    public static final String CANCEL_MSG = "⏹️ Run cancelled by user";
    /** 循环退出却没有任何路径写终态时的兜底失败原因。 */
    public static final String RUN_LOOP_NO_TERMINAL_MSG = "执行异常：run 循环已退出但未产生终态";
    /** 长时间停在 RUNNING 且执行副本已不在（无 owner）时的对账失败原因。 */
    public static final String RUN_STALE_MSG = "执行中断：run 长时间无进展且执行副本已失联";

    private RunnerConstants() {
    }
}
