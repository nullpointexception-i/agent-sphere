package com.buukle.agent.runtime.kernel.constants;

/** read_tool_result：按 callId 分页回读被上下文预算省略的历史工具结果。 */
public final class ToolResultReadConstants {
    public static final String INTERNAL_NAME = "read_tool_result";
    public static final String DISPLAY_NAME = "Read tool result";
    public static final String DISPLAY_NAME_CN = "回读工具结果";
    public static final String DESCRIPTION = """
            Read a previously omitted tool result by its callId (for example, an older browser page).
            Use it when a reference card replaced content you still need. Results are paginated:
            pass offset from nextOffset to continue. Default maxChars=4000, hard limit=8000.
            """;
    public static final String PARAM_SCHEMA = """
            {"type":"object","properties":{
              "callId":{"type":"string","description":"Tool call id from the omitted-result reference card"},
              "offset":{"type":"integer","minimum":0,"description":"Start character offset, default 0"},
              "maxChars":{"type":"integer","minimum":200,"maximum":8000,"description":"Chunk size, default 4000"}
            },"required":["callId"]}
            """;
    public static final int DEFAULT_MAX_CHARS = 4000;
    public static final int MIN_MAX_CHARS = 200;
    public static final int MAX_MAX_CHARS = 8000;

    private ToolResultReadConstants() {
    }
}
