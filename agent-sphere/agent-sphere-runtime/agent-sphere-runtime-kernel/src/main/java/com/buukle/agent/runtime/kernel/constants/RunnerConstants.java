package com.buukle.agent.runtime.kernel.constants;

public final class RunnerConstants {
    public static final String TOOL_TYPE_FUNCTION = "function";
    public static final String EMPTY_JSON_ARGS = "{}";
    public static final long DEFAULT_MAX_INPUT_TOKENS = 1_000_000L;
    public static final long DEFAULT_MAX_OUTPUT_TOKENS = 128_000L;
    /** Fallback when a route has no max_input_tokens configured (matches the frontend default for new routes). */
    public static final long DEFAULT_ROUTE_MAX_INPUT_TOKENS = 128_000L;
    /** Fallback when a route has no max_output_tokens configured (matches the frontend default for new routes). */
    public static final long DEFAULT_ROUTE_MAX_OUTPUT_TOKENS = 16_000L;
    /** Minimum output tokens reserved for a single chat turn; below this no request is sent — compact or fail instead. */
    public static final int MIN_CHAT_OUTPUT_TOKENS = 1024;
    /**
     * Divisor used to estimate tokens from character count. Chinese text is actually about 1.5–1.8 chars/token (Chinese values inside JSON are similarly dense),
     * and the old value of 3 under-estimated badly, so compaction always lagged behind real overflow (on production a 32k route estimated only ~20k at an actual 32,852-token request).
     */
    public static final double TOKEN_ESTIMATE_DIVISOR = 1.8;
    public static final int TOOL_RESULT_MAX_CHARS = 2000;
    public static final int COMPACTION_TOOL_RESULT_MAX_CHARS = 500;
    public static final int TOOL_RESULT_HISTORY_CHARS_BUDGET = 50000;
    public static final String FALLBACK_CALL_ID_PREFIX = "hist_";
    public static final String TOOL_RESULT_BUDGET_OMITTED = "[Tool result omitted due to context limit]";
    public static final String PROMPT_TOOLS_HEADER = "\n\nAvailable tools:\n";
    /** Todo list tail segment: the header also acts as the slot marker (a message whose content starts with the header is the todo slot). */
    public static final String PROMPT_TODOLIST_HEADER = "\n\n## Progress log (does not mean the task is complete)\n";
    public static final String PROMPT_TODOLIST_INSTRUCTION =
            "\n\n**Update this list promptly when the task status changes; do not wait for the user to remind you. "
            + "Note: this list is a progress record only and **is not a completion criterion** - whether the task is complete is decided by the task instructions "
            + "(skill steps / expected output / closing conditions), not by every item in this list being completed. "
            + "You maintain it: when a skill defines the plan, fill it in from the skill steps; otherwise plan it yourself. "
            + "Every update must send the COMPLETE list (this tool replaces the whole list).**";
    public static final String PROMPT_TOOLS_FOOTER = """
            
            TASK LIST: keep exactly ONE task list via the task list tool. It is the plan for the WHOLE task
            and must contain everything still to do.
            - No skill applies: create your own list before acting; restructure freely as work evolves.
            - A skill IS the whole plan: read it first, then lay out every `## stepN` and `### stepN.M` in
              order as plain numbering (`N. <title>` / `N.M <title>`), with `skillId` + `stepId` on each item.
            - A skill covers only some steps: keep your existing list; add `skillId`/`stepId` to those items only.
            Before executing an item with `skillId` + `stepId`, read its body with
            skill_library(action=get, skillId=<id>, sectionHeading=<stepId>); the outline is titles only.
            The task list tool REPLACES the entire list — always pass the COMPLETE list; omitted items are deleted.
            A completed list is PROGRESS ONLY, not completion; completion follows the task instructions/skill.
            You may call multiple INDEPENDENT tools in one turn (parallel); dependent tools need separate turns.
            When calling the task list tool, JSON must be valid (commas between properties):
            {"todos": [{"content": "...", "status": "pending", "priority": "high"}]}
             CRITICAL: call tools through the function-calling API (tool_calls); never describe them as JSON/text.
             INTERMEDIATE PROGRESS: when the work is not complete but you need to report a milestone, call progress
             with a concise summary and optional nextAction. Do not use plain text as a progress protocol.
             CRITICAL — EXECUTE, DO NOT ASK: when the user gave a clear instruction, execute it immediately with
            tools. Do NOT ask "what do you want to do". If exactly one detail is missing, use the clarification
            tool once when available, otherwise proceed with the best available assumption; never ask
            consecutive clarifications.
            """;
    /** Appended only when the clarification tool is registered (avoids instructing a tool that does not exist). */
    public static final String PROMPT_CLARIFICATION_RULES = """

            CLARIFICATION RULE: If you need the user to confirm an action, choose between options,
            or provide information, you MUST call the clarification tool (choice / confirm / input).
            NEVER pose questions in plain text — the user can only answer through that tool.

            CLARIFICATION AFTERCARE: After the user responds, immediately update the task list to
            reflect the new information, then execute the next step. Do NOT ask another
            clarification on the same topic.""";

    /** Authoritative rule when the clarification tool is not registered (autonomous tasks): must not ask the user questions. */
    public static final String PROMPT_NO_CLARIFICATION_RULE = """

            NO USER QUESTIONS: You cannot ask the user for input in this run. If you are blocked
            by missing external information or permission, state exactly what is needed in your final
            answer (or call `finish(status=blocked, summary=...)` when that tool is available).
            Otherwise proceed with the best available information.""";
    public static final String COMPACTION_USER_PREFIX = "User: ";
    public static final String COMPACTION_ASSISTANT_PREFIX = "Assistant: ";
    public static final String COMPACTION_NEWLINE = "\n";
    public static final String HISTORY_SUMMARY_PREFIX = "[Conversation summary]\n";
    /** todowrite tool receipt: the full list is delivered through the tail todo slot, so the receipt keeps only a short confirmation. */
    public static final String TODO_WRITE_ACK = "{\"ok\":true,\"message\":\"task list updated\"}";
    /**
     * Lead time for the forced-finalization instruction: once remaining loops ≤ this value, {@link #FINAL_TURN_INSTRUCTION} is injected.
     *
     * <p>It used to be "inject only on the last loop (n-1)". But the finalization instruction is only a <b>hint</b>, not enforcement, and models often
     * really wrap up only on the last loop — production runs 1575/1569 both landed exactly on the last loop. Giving the model 1 loop to wrap up
     * is too tight: it either cannot finish writing the conclusion, or can only use "interrupted" as the delivery excuse
     * (run 1569 had `status:"INCOMPLETE"`). Moving the lead time to n-3 gives it 3 loops to finish and polish.
     *
     * <p><b>Note</b>: this does not affect when the loop cap takes effect (still n-1); it only moves the "time to wrap up" signal earlier.
     */
    public static final int FINAL_TURN_INSTRUCTION_LEAD = 3;

    /**
     * Terminal-turn finalization instruction: appended as a tail system message (index 0 is left untouched so the prefix cache stays stable).
     *
     * <p>The wording says "approaching the limit / limited remaining loops" instead of "this is your last loop" — because it starts being injected at n-3,
     * claiming "last loop" would be false and could make the model misjudge that it has another chance and keep calling tools.
     */
    public static final String FINAL_TURN_INSTRUCTION =
            "\n\n**IMPORTANT: You are approaching the hard loop limit. From now on you must NOT call any more"
                    + " tools except `finish`. Use your remaining turns to deliver a complete, well-structured"
                    + " final answer, then call `finish(status, summary)` to end the run.**";

    /**
     * Marker for the per-loop budget notice: used both to locate and clear the previous loop's copy across turns and as the text prefix.
     *
     * <p>The budget text changes every loop (current loop / loops left), and tool loops append
     * assistant/tool/observation messages to the tail of messages (SessionRunner#runInternal), so **in-place replacement is not possible** —
     * the previous loop's copy would be pushed into the middle of the history, wasting context and contradicting the new text.
     * Each loop must first clear all copies by this marker and then re-append at the tail, so exactly one copy exists in the list at all times.
     */
    public static final String LOOP_BUDGET_PREFIX = "\n\n[LOOP BUDGET] ";

    /** After this many consecutive compaction failures within one run, stop compacting to avoid burning tokens every loop. */
    public static final int MAX_CONSECUTIVE_COMPACTION_FAILURES = 2;

    /** Marker for the silent follow-up message (old copies are cleaned up when the tail is rebuilt each loop, avoiding accumulation). */
    public static final String SILENT_TURN_NUDGE_PREFIX = "\n\n[NO TOOL CALL] ";

    public static final String SILENT_TURN_NUDGE_TEXT =
            "You ended your turn WITHOUT calling any tool. If the task is complete you MUST call `finish(status, summary)`;"
                    + " otherwise continue by calling the next tool. Replying with plain text alone is treated as an error.";

    /** When remaining loops fall below this value, the budget notice appends an urgency reminder (models do not react to "100 loops left"; they need an explicit push). */
    public static final int LOOP_BUDGET_LOW_THRESHOLD = 5;

    /**
     * Backoff base (ms) for retrying after a single-loop LLM failure; grows as {@code base × 2^(attempt-1)} and is capped.
     *
     * <p>It used to be a fixed {@code sleep(1000)} with only 1 retry (2 attempts total). In production the model endpoint was observed
     * returning errors <b>in sudden bursts</b>: one run hit 3 failures within 9 seconds, exhausted the retry budget and went FAILED,
     * while only a run that happened to hit exactly 2 failures survived. A fixed 1s backoff lands inside the burst window and fires again in the same second,
     * amplifying a single jitter into consecutive failures.
     */
    public static final long LLM_TURN_RETRY_BASE_MS = 1_000L;
    /** Retry backoff cap (ms), keeping a long retry chain from dragging a single loop to an unacceptable length. */
    public static final long LLM_TURN_RETRY_MAX_MS = 8_000L;

    /** delegate sub-agent workflow guide (appended only when the delegate tool is registered, to avoid pointless tokens). */
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
    /** Fallback failure reason when the loop exits and no path wrote a terminal state. */
    public static final String RUN_LOOP_NO_TERMINAL_MSG = "Execution error: the run loop exited without producing a terminal state";
    /** Reconciliation failure reason when a run stays RUNNING for a long time but the executing replica is gone (no owner). */
    public static final String RUN_STALE_MSG = "Execution interrupted: the run made no progress for a long time and the executing replica is gone";
    /** Failure reason when the run context is lost (empty tool list); silent loops must not continue. */
    public static final String RUN_CONTEXT_LOST_MSG = "Run context lost (tool list is empty); cannot continue calling tools. Retry.";
    /** Failure reason when there is still no usable output space after compaction; a request doomed to 400 must not be sent. */
    public static final String RUN_CONTEXT_OVERFLOW_MSG =
            "The context is full and compaction cannot free output space. Shorten the conversation or switch to a model with a larger context.";
    /**
     * Synthetic user message appended to the end of the history when a run is recovered after a service restart.
     *
     * <p>History reconstruction replays only {@code run.getUserMessage()}; synthetic user messages such as the wait-resume hint are not persisted —
     * without the tail user anchor, a long stretch of assistant/tool messages follows the last user: this both loses the "re-check after waking" semantics
     * and pushes the reconstructed content-only assistant into DeepSeek's reasoning_content validation window
     * (production run 2794's 400). Adding it keeps the recovery path structurally consistent with the wait-resume path: the tail always has one user message.
     */
    public static final String RUN_RECOVERY_RESUME_PROMPT =
            "[The run was interrupted by a server restart and has been resumed. "
                    + "Continue from the existing history: re-read the current state before acting, "
                    + "do NOT repeat tool calls whose results are already in the history, and finish the task.]";
    /** Tool call reason rules (appended to the system prompt only when call-reason-enabled). */
    public static final String PROMPT_CALL_REASON_RULES = """

            TOOL CALL REASON: every tool call MUST include `_reason` — one short, user-visible sentence
            explaining why you are calling this tool now (max 200 characters). Do NOT put hidden
            chain-of-thought, secrets, or long explanations in `_reason`.
            """;

    private RunnerConstants() {
    }
}
