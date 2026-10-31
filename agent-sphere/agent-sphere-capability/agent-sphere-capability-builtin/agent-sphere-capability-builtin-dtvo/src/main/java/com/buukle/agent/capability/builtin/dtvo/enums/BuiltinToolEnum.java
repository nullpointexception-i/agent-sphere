package com.buukle.agent.capability.builtin.dtvo.enums;

import com.buukle.agent.common.constant.ToolAccessMode;
import com.buukle.agent.common.constant.ToolApprovalScope;
import com.buukle.agent.common.constant.ToolSecurityLevel;

public enum BuiltinToolEnum {
    WEB_SEARCH(2, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    TODOWRITE(3, ToolAccessMode.WRITE, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    WEB_READ(4, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    CHROME(5, ToolAccessMode.READ_WRITE, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, true),
    DOCWRITE(6, ToolAccessMode.WRITE, ToolSecurityLevel.L2, ToolApprovalScope.SESSION, false),
    ASK_CLARIFICATION(7, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    SKILL_LIBRARY(8, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    SKILL_MANAGE(9, ToolAccessMode.WRITE, ToolSecurityLevel.L2, ToolApprovalScope.SESSION, false),
    WAIT(10, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    TEXT_CONTAINS(11, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    FINISH(12, ToolAccessMode.WRITE, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    CURRENT_TIME(13, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    READ_TOOL_RESULT(14, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    DOC_READ(15, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false),
    PROGRESS(16, ToolAccessMode.READ, ToolSecurityLevel.L1, ToolApprovalScope.SESSION, false);

    private final int id;
    private final ToolAccessMode accessMode;
    private final ToolSecurityLevel securityLevel;
    private final ToolApprovalScope approvalScope;
    private final boolean compressEnabled;

    BuiltinToolEnum(int id, ToolAccessMode accessMode, ToolSecurityLevel securityLevel,
                    ToolApprovalScope approvalScope, boolean compressEnabled) {
        this.id = id;
        this.accessMode = accessMode;
        this.securityLevel = securityLevel;
        this.approvalScope = approvalScope;
        this.compressEnabled = compressEnabled;
    }

    public int getId() {
        return id;
    }

    public ToolAccessMode getAccessMode() {
        return accessMode;
    }

    public ToolSecurityLevel getSecurityLevel() {
        return securityLevel;
    }

    public ToolApprovalScope getApprovalScope() {
        return approvalScope;
    }

    public boolean isCompressEnabled() {
        return compressEnabled;
    }
}

