package com.buukle.agent.common.constant;

/**
 * Permission codes used by {@code @RequirePermission} and by the front-end access checks.
 *
 * <p>Codes are part of the persisted RBAC contract ({@code sys_permission.code}) and must
 * remain stable. SQL seed data is the source of truth for the permission catalogue; this
 * class only prevents string literals from spreading across controllers and clients.
 */
public final class PermissionCodes {

    // ---- admin ----
    public static final String ADMIN_USER_READ = "admin:user:read";
    public static final String ADMIN_ROLE_READ = "admin:role:read";
    public static final String ADMIN_ROLE_CREATE = "admin:role:create";
    public static final String ADMIN_ROLE_UPDATE = "admin:role:update";
    public static final String ADMIN_ROLE_DELETE = "admin:role:delete";
    public static final String ADMIN_ROLE_ASSIGN = "admin:role:assign";
    public static final String ADMIN_PERMISSION_READ = "admin:permission:read";
    public static final String ADMIN_PERMISSION_CREATE = "admin:permission:create";
    public static final String ADMIN_PERMISSION_UPDATE = "admin:permission:update";
    public static final String ADMIN_PERMISSION_DELETE = "admin:permission:delete";
    public static final String ADMIN_AUDIT_LOG_READ = "admin:audit-log:read";
    public static final String ADMIN_SETTINGS_READ = "admin:settings:read";
    public static final String ADMIN_SETTINGS_UPDATE = "admin:settings:update";
    public static final String ADMIN_SETTINGS_REGENERATE_AES = "admin:settings:regenerate-aes";
    public static final String ADMIN_DICTIONARY_READ = "admin:dictionary:read";
    public static final String ADMIN_DICTIONARY_CREATE = "admin:dictionary:create";
    public static final String ADMIN_DICTIONARY_UPDATE = "admin:dictionary:update";
    public static final String ADMIN_DICTIONARY_DELETE = "admin:dictionary:delete";
    public static final String ADMIN_IDENTITY_PROVIDER_CREATE = "admin:identity-provider:create";
    public static final String ADMIN_IDENTITY_PROVIDER_READ = "admin:identity-provider:read";
    public static final String ADMIN_IDENTITY_PROVIDER_UPDATE = "admin:identity-provider:update";
    public static final String ADMIN_IDENTITY_PROVIDER_DELETE = "admin:identity-provider:delete";
    public static final String ADMIN_COMPLETIONS_CREATE = "admin:completions:create";
    public static final String ADMIN_COMPLETIONS_READ = "admin:completions:read";
    public static final String ADMIN_COMPLETIONS_UPDATE = "admin:completions:update";
    public static final String ADMIN_COMPLETIONS_DELETE = "admin:completions:delete";
    public static final String ADMIN_TENANT_READ = "admin:tenant:read";
    public static final String ADMIN_TENANT_CREATE = "admin:tenant:create";
    public static final String ADMIN_TENANT_UPDATE = "admin:tenant:update";
    public static final String ADMIN_TENANT_DELETE = "admin:tenant:delete";
    public static final String ADMIN_TENANT_ASSIGN = "admin:tenant:assign";
    public static final String ADMIN_TASKS_CREATE = "admin:tasks:create";
    public static final String ADMIN_TASKS_READ = "admin:tasks:read";
    public static final String ADMIN_TASKS_UPDATE = "admin:tasks:update";

    // ---- instance ----
    public static final String INSTANCE_CREATE = "instance:create";
    public static final String INSTANCE_UPDATE = "instance:update";
    public static final String INSTANCE_DELETE = "instance:delete";
    public static final String INSTANCE_BIND_MODEL = "instance:bind-model";
    public static final String INSTANCE_CAPABILITY_BIND = "instance:capability:bind";
    public static final String INSTANCE_CAPABILITY_UNBIND = "instance:capability:unbind";

    // ---- model ----
    public static final String MODEL_PROVIDER_CREATE = "model:provider:create";
    public static final String MODEL_PROVIDER_UPDATE = "model:provider:update";
    public static final String MODEL_PROVIDER_DELETE = "model:provider:delete";
    public static final String MODEL_APIKEY_CREATE = "model:apikey:create";
    public static final String MODEL_APIKEY_UPDATE = "model:apikey:update";
    public static final String MODEL_APIKEY_DELETE = "model:apikey:delete";
    public static final String MODEL_APIKEY_SET_ACTIVE = "model:apikey:set-active";
    public static final String MODEL_ROUTE_CREATE = "model:route:create";
    public static final String MODEL_ROUTE_UPDATE = "model:route:update";
    public static final String MODEL_ROUTE_DELETE = "model:route:delete";

    // ---- capability ----
    public static final String CAPABILITY_MCP_CREATE = "capability:mcp:create";
    public static final String CAPABILITY_MCP_UPDATE = "capability:mcp:update";
    public static final String CAPABILITY_MCP_DELETE = "capability:mcp:delete";
    public static final String CAPABILITY_MCP_TEST = "capability:mcp:test";
    public static final String CAPABILITY_SKILL_CREATE = "capability:skill:create";
    public static final String CAPABILITY_SKILL_READ = "capability:skill:read";
    public static final String CAPABILITY_SKILL_UPDATE = "capability:skill:update";
    public static final String CAPABILITY_SKILL_DELETE = "capability:skill:delete";
    public static final String CAPABILITY_SKILL_PUBLISH = "capability:skill:publish";
    public static final String CAPABILITY_SKILL_INSTALL = "capability:skill:install";
    public static final String CAPABILITY_CLI_CREATE = "capability:cli:create";
    public static final String CAPABILITY_CLI_UPDATE = "capability:cli:update";
    public static final String CAPABILITY_CLI_DELETE = "capability:cli:delete";

    // ---- document ----
    public static final String DOCUMENT_UPDATE = "document:update";
    public static final String DOCUMENT_DELETE = "document:delete";
    public static final String DOCUMENT_SHARE = "document:share";

    // ---- IM ----
    public static final String IM_CONVERSATION_CREATE = "im:conversation:create";
    public static final String IM_CONVERSATION_READ = "im:conversation:read";
    public static final String IM_CONVERSATION_UPDATE = "im:conversation:update";
    public static final String IM_MESSAGE_SEND = "im:message:send";
    public static final String IM_MEMBER_MANAGE = "im:member:manage";

    // ---- Super-Agent ----
    public static final String SUPER_AGENT_CREATE = "super-agent:create";
    public static final String SUPER_AGENT_READ = "super-agent:read";
    public static final String SUPER_AGENT_UPDATE = "super-agent:update";
    public static final String SUPER_AGENT_DELETE = "super-agent:delete";

    // ---- user ----
    public static final String USER_PASSWORD_UPDATE = "user:password:update";
    public static final String USER_PROFILE_UPDATE = "user:profile:update";

    private PermissionCodes() {
    }
}
