package com.buukle.agent.runtime.kernel.constants;

public final class RunnerConstants {
    public static final String TOOL_TYPE_FUNCTION = "function";
    public static final String EMPTY_JSON_ARGS = "{}";
    public static final long DEFAULT_MAX_INPUT_TOKENS = 1_000_000L;
    public static final long DEFAULT_MAX_OUTPUT_TOKENS = 128_000L;
    public static final int TOKEN_ESTIMATE_DIVISOR = 3;
    public static final int TOOL_RESULT_MAX_CHARS = 2000;
    public static final int COMPACTION_TOOL_RESULT_MAX_CHARS = 500;
    public static final int TOOL_RESULT_HISTORY_CHARS_BUDGET = 50000;
    public static final String FALLBACK_CALL_ID_PREFIX = "hist_";
    public static final String TOOL_RESULT_BUDGET_OMITTED = "[Tool result omitted due to context limit]";
    public static final String PROMPT_CURRENT_TIME = "\n\nCurrent server time: ";
    public static final String PROMPT_TOOLS_HEADER = "\n\nAvailable tools:\n";
    public static final String PROMPT_TOOLS_FOOTER = """
            
            BEFORE any other action, call the task list tool to create a structured
            task list covering the user's request. As you work through each task,
            UPDATE the task list by calling it again whenever a task's status changes
            (pending → in_progress → completed / cancelled). Keep the task list in
            sync with your actual progress.
            
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
            function definitions.""";
    public static final String COMPACTION_USER_PREFIX = "User: ";
    public static final String COMPACTION_ASSISTANT_PREFIX = "Assistant: ";
    public static final String COMPACTION_NEWLINE = "\n";
    public static final String HISTORY_SUMMARY_PREFIX = "[Conversation summary]\n";

    private RunnerConstants() {
    }
}
