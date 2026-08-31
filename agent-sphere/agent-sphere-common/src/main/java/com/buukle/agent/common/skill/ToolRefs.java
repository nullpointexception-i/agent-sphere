package com.buukle.agent.common.skill;

import java.util.Locale;

/**
 * 工具白名单引用（稳定标识，不使用易变的 displayName）。
 *
 * <pre>
 * builtin:&lt;internalName&gt;
 * mcp:&lt;capabilityId&gt;:&lt;nativeToolName&gt;
 * cli:&lt;capabilityId&gt;
 * skill:&lt;skillId&gt;
 * </pre>
 */
public final class ToolRefs {

    public static final char SEPARATOR = ':';
    public static final String WILDCARD = "*";
    public static final String TYPE_BUILTIN = "builtin";
    public static final String TYPE_MCP = "mcp";
    public static final String TYPE_CLI = "cli";
    public static final String TYPE_SKILL = "skill";

    private ToolRefs() {
    }

    public static String builtin(String internalName) {
        return TYPE_BUILTIN + SEPARATOR + internalName;
    }

    public static String mcp(Long capabilityId, String nativeToolName) {
        return TYPE_MCP + SEPARATOR + capabilityId + SEPARATOR + nativeToolName;
    }

    public static String cli(Long capabilityId) {
        return TYPE_CLI + SEPARATOR + capabilityId;
    }

    public static String skill(Long skillId) {
        return TYPE_SKILL + SEPARATOR + skillId;
    }

    /** 校验引用格式，非法时抛出描述性异常。 */
    public static void validate(String ref) throws InvalidSkillDefinition {
        if (ref == null || ref.isBlank()) {
            throw new InvalidSkillDefinition("allowTools 工具引用不能为空");
        }
        if (WILDCARD.equals(ref.trim())) {
            return;
        }
        String r = ref.trim();
        if (r.startsWith(TYPE_BUILTIN + SEPARATOR)) {
            if (r.length() == TYPE_BUILTIN.length() + 1) {
                throw new InvalidSkillDefinition("builtin 引用缺少 internalName: " + ref);
            }
            return;
        }
        if (r.startsWith(TYPE_CLI + SEPARATOR)) {
            Long.parseLong(stripPrefix(r, TYPE_CLI));
            return;
        }
        if (r.startsWith(TYPE_SKILL + SEPARATOR)) {
            Long.parseLong(stripPrefix(r, TYPE_SKILL));
            return;
        }
        if (r.startsWith(TYPE_MCP + SEPARATOR)) {
            String[] parts = r.substring(TYPE_MCP.length() + 1).split(String.valueOf(SEPARATOR));
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new InvalidSkillDefinition("mcp 引用格式应为 mcp:<capabilityId>:<nativeToolName>: " + ref);
            }
            Long.parseLong(parts[0]);
            return;
        }
        throw new InvalidSkillDefinition("未知工具引用类型（应为 builtin/mcp/cli/skill）: " + ref);
    }

    public static boolean matches(String allowRef, String toolRef, String toolName) {
        if (allowRef == null) {
            return false;
        }
        String r = allowRef.trim();
        if (r.isEmpty()) {
            return false;
        }
        return WILDCARD.equals(r) || (toolRef != null && r.equalsIgnoreCase(toolRef))
                || (toolName != null && r.equalsIgnoreCase(toolName));
    }

    private static String stripPrefix(String ref, String prefix) {
        String id = ref.substring(prefix.length() + 1).trim();
        if (id.isEmpty()) {
            throw new InvalidSkillDefinition("引用缺少 id: " + ref);
        }
        try {
            Long value = Long.parseLong(id);
            return String.valueOf(value);
        } catch (NumberFormatException e) {
            throw new InvalidSkillDefinition("引用 id 必须是数字: " + ref);
        }
    }
}