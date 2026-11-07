package com.buukle.agent.bootstrap.controller;

import com.buukle.agent.common.context.TenantScopeHolder;

import com.buukle.agent.capability.mcp.dtvo.dto.CreateMcpDTO;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.capability.mcp.repository.McpMapper;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.impl.CapabilityMcpServiceImpl;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** MCP serverUrl validation: must start with http/https so the transport does not throw a raw IAE. */
@ExtendWith(MockitoExtension.class)
class CapabilityMcpServerUrlTest {

    @Mock
    McpMapper mcpMapper;

    @Mock
    McpTransportFactory mcpTransportFactory;

    CapabilityMcpServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new CapabilityMcpServiceImpl(
                new CapabilityMcpConverter(), mcpTransportFactory,
                new com.buukle.agent.common.config.AgentRuntimeProperties());
        java.lang.reflect.Field f =
                com.baomidou.mybatisplus.extension.repository.CrudRepository.class.getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, mcpMapper);
    }

    @AfterEach
    void tearDown() {
        com.buukle.agent.common.context.AuthContext.clear();
        com.buukle.agent.common.context.TenantUtil.stop();
    }

    private CreateMcpDTO dto(String serverUrl) {
        CreateMcpDTO dto = new CreateMcpDTO();
        dto.setName("m");
        dto.setServerUrl(serverUrl);
        dto.setServerType("http");
        return dto;
    }

    @Test
    void create_missingScheme_throwsParamInvalid() {
        BizException e = assertThrows(BizException.class, () -> service.createMcp(dto("127.0.0.1:8080/mcp")));
        assertEquals(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID.getCode(), e.getErrorCode());
        assertTrue(e.getMessage().contains("http"));
        verify(mcpMapper, never()).insert(org.mockito.ArgumentMatchers.any(com.buukle.agent.capability.mcp.domain.CapabilityMcp.class));
    }

    @Test
    void create_withHttpScheme_ok() {
        org.mockito.BDDMockito.given(mcpMapper.insert(org.mockito.ArgumentMatchers.any(com.buukle.agent.capability.mcp.domain.CapabilityMcp.class)))
                .willReturn(1);

        service.createMcp(dto("http://localhost:8080/mcp"));

        verify(mcpMapper).insert(org.mockito.ArgumentMatchers.any(com.buukle.agent.capability.mcp.domain.CapabilityMcp.class));
    }

    @Test
    void update_missingScheme_throwsParamInvalid() {
        BizException e = assertThrows(BizException.class, () -> service.updateMcp(1L, dto("localhost:3000")));
        assertEquals(com.buukle.agent.common.error.CommonErrorCode.PARAM_INVALID.getCode(), e.getErrorCode());
        verify(mcpMapper, never()).updateById(org.mockito.ArgumentMatchers.any(com.buukle.agent.capability.mcp.domain.CapabilityMcp.class));
    }
}