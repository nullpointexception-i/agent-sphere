package com.buukle.agent.capability.mcp.service.converter;

import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.common.constant.ToolAccessMode;
import com.buukle.agent.common.constant.ToolApprovalScope;
import com.buukle.agent.common.constant.ToolSecurityLevel;
import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.dtvo.enums.McpCapabilityEnum;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
public class CapabilityMcpConverter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public McpVO toVO(CapabilityMcp mcp) {
        if (mcp == null) return null;
        McpVO vo = new McpVO();
        vo.setId(mcp.getId());
        vo.setName(mcp.getName());
        vo.setDescription(mcp.getDescription());
        vo.setServerUrl(mcp.getServerUrl());
        vo.setServerType(mcp.getServerType());
        vo.setAuthConfig(mcp.getAuthConfig());
        vo.setToolDefinitions(mcp.getToolDefinitions());
        vo.setStatus(mcp.getStatus());
        vo.setCompressEnabled(Boolean.TRUE.equals(mcp.getCompressEnabled()));
        vo.setAccessMode(mcp.getAccessMode());
        vo.setSecurityLevel(mcp.getSecurityLevel());
        vo.setApprovalScope(mcp.getApprovalScope());
        vo.setCreatedAt(mcp.getCreatedAt() != null ? mcp.getCreatedAt().format(DTF) : null);
        vo.setCreatedBy(mcp.getCreatedBy());
        vo.setUpdatedBy(mcp.getUpdatedBy());
        vo.setUpdatedAt(mcp.getUpdatedAt() != null ? mcp.getUpdatedAt().format(DTF) : null);
        return vo;
    }

    public CapabilityMcp toDO(CreateMcpDTO dto) {
        CapabilityMcp mcp = new CapabilityMcp();
        mcp.setName(dto.getName());
        mcp.setDescription(dto.getDescription());
        mcp.setServerUrl(dto.getServerUrl());
        mcp.setServerType(dto.getServerType());
        mcp.setAuthConfig(dto.getAuthConfig());
        mcp.setToolDefinitions(dto.getToolDefinitions());
        mcp.setStatus(McpCapabilityEnum.STATUS_ENABLED);
        mcp.setCompressEnabled(Boolean.TRUE.equals(dto.getCompressEnabled()));
        mcp.setAccessMode(dto.getAccessMode() != null ? dto.getAccessMode() : ToolAccessMode.READ_WRITE.name());
        mcp.setSecurityLevel(dto.getSecurityLevel() != null ? dto.getSecurityLevel() : ToolSecurityLevel.L1.name());
        mcp.setApprovalScope(dto.getApprovalScope() != null ? dto.getApprovalScope() : ToolApprovalScope.ONCE.name());
        return mcp;
    }
}
