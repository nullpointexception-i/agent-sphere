package com.buukle.agent.capability.mcp.service.converter;

import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
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
        return mcp;
    }
}
