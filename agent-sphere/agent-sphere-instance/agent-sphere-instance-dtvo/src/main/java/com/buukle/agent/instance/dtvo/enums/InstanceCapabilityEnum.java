package com.buukle.agent.instance.dtvo.enums;

import com.buukle.agent.common.constant.McpServerType;

public final class InstanceCapabilityEnum {
    public static final String CAPABILITY_TYPE_MCP = "mcp";
    public static final String CAPABILITY_TYPE_SKILL = "skill";
    /** A followed tenant-hub skill bound to an instance: read-only, always the latest hub version. */
    public static final String CAPABILITY_TYPE_SKILL_HUB = "skill_hub";
    public static final String CAPABILITY_TYPE_CLI = "cli";
    public static final String CAPABILITY_TYPE_BUILTIN = "builtin";
    public static final String STATUS_ENABLED = "ENABLED";
    // ---- Tool name prefixes (llmToolName) ----
    public static final String LLM_PREFIX_BUILTIN = "builtin_";
    public static final String LLM_PREFIX_CLI = "cli_";
    public static final String LLM_PREFIX_SKILL = "skill_";
    public static final String LLM_PREFIX_MCP = "mcp_";
    // ---- MCP server types ----
    public static final String MCP_SERVER_TYPE_HTTP = McpServerType.HTTP;
    public static final String MCP_SERVER_TYPE_SSE = McpServerType.SSE;
    // ---- Skill definition keys ----
    public static final String SKILL_DEF_PARAMETERS = "parameters";
    public static final String SKILL_DEF_PROMPT_TEMPLATE = "promptTemplate";
    public static final String SKILL_MARKDOWN_FENCE = "```json";
    private InstanceCapabilityEnum() {
    }
}
