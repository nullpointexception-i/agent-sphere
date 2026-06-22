package com.buukle.agent.runtime.kernel.constants;

public final class ExecBindingKeys {
    // ---- Builtin ----
    public static final String BUILTIN_INTERNAL_NAME = "internalName";
    // ---- CLI ----
    public static final String CLI_COMMAND_TEMPLATE = "commandTemplate";
    public static final String CLI_WORKING_DIR = "workingDir";
    // ---- Skill ----
    public static final String SKILL_PROMPT_TEMPLATE = "promptTemplate";
    // ---- MCP ----
    public static final String MCP_SERVER_URL = "serverUrl";
    public static final String MCP_SERVER_TYPE = "serverType";
    public static final String MCP_AUTH_CONFIG = "authConfig";
    public static final String MCP_NATIVE_TOOL_NAME = "nativeToolName";
    private ExecBindingKeys() {
    }
}
