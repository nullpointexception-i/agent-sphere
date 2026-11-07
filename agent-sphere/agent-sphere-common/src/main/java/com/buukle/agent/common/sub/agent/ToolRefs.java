package com.buukle.agent.common.sub.agent;

/**
 * Tool allowlist reference (stable identifier; avoids the volatile displayName).
 *
 * <pre>
 * builtin:&lt;internalName&gt;
 * mcp:&lt;capabilityId&gt;:&lt;nativeToolName&gt;
 * cli:&lt;capabilityId&gt;
 * skill:&lt;skillId&gt;
 * agent:&lt;key&gt;
 * </pre>
 */
public final class ToolRefs {

    public static final char SEPARATOR = ':';
    public static final String WILDCARD = "*";
    public static final String TYPE_BUILTIN = "builtin";
    public static final String TYPE_MCP = "mcp";
    public static final String TYPE_CLI = "cli";
    public static final String TYPE_SKILL = "skill";
    public static final String TYPE_AGENT = "agent";

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

    public static String agent(String key) {
        return TYPE_AGENT + SEPARATOR + key;
    }

    /** Validates the reference format; throws a descriptive exception when invalid. */
    public static void validate(String ref) throws InvalidSubRunDefinition {
        if (ref == null || ref.isBlank()) {
            throw new InvalidSubRunDefinition("tool reference must not be blank");
        }
        if (WILDCARD.equals(ref.trim())) {
            return;
        }
        String r = ref.trim();
        if (r.startsWith(TYPE_BUILTIN + SEPARATOR)) {
            if (r.length() == TYPE_BUILTIN.length() + 1) {
                throw new InvalidSubRunDefinition("builtin reference is missing internalName: " + ref);
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
        if (r.startsWith(TYPE_AGENT + SEPARATOR)) {
            if (r.length() == TYPE_AGENT.length() + 1) {
                throw new InvalidSubRunDefinition("agent reference is missing an identifier: " + ref);
            }
            return;
        }
        if (r.startsWith(TYPE_MCP + SEPARATOR)) {
            String[] parts = r.substring(TYPE_MCP.length() + 1).split(String.valueOf(SEPARATOR));
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new InvalidSubRunDefinition("mcp reference must use mcp:<capabilityId>:<nativeToolName>: " + ref);
            }
            Long.parseLong(parts[0]);
            return;
        }
        throw new InvalidSubRunDefinition("unknown tool reference type (expected builtin/mcp/cli/skill/agent): " + ref);
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
            throw new InvalidSubRunDefinition("reference is missing an id: " + ref);
        }
        try {
            Long value = Long.parseLong(id);
            return String.valueOf(value);
        } catch (NumberFormatException e) {
            throw new InvalidSubRunDefinition("reference id must be numeric: " + ref);
        }
    }
}