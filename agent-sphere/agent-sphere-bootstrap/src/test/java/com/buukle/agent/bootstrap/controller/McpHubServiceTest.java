package com.buukle.agent.bootstrap.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.buukle.agent.admin.spi.TenantScopeSpi;
import com.buukle.agent.capability.mcp.domain.CapabilityMcp;
import com.buukle.agent.capability.mcp.domain.CapabilityMcpFollow;
import com.buukle.agent.capability.mcp.domain.CapabilityMcpHub;
import com.buukle.agent.capability.mcp.dtvo.vo.McpHubVO;
import com.buukle.agent.capability.mcp.dtvo.vo.McpVO;
import com.buukle.agent.capability.mcp.exception.CapabilityMcpErrorCode;
import com.buukle.agent.capability.mcp.repository.McpFollowMapper;
import com.buukle.agent.capability.mcp.repository.McpHubMapper;
import com.buukle.agent.capability.mcp.repository.McpMapper;
import com.buukle.agent.capability.mcp.service.converter.CapabilityMcpConverter;
import com.buukle.agent.capability.mcp.service.impl.McpHubServiceImpl;
import com.buukle.agent.capability.mcp.service.mcp.McpTransportFactory;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Tenant MCP Hub: publish versioning, follow without a copy, install lineage, shared credentials, ancestor visibility. */
@ExtendWith(MockitoExtension.class)
class McpHubServiceTest {

    @Mock
    McpMapper mcpMapper;
    @Mock
    McpHubMapper hubMapper;
    @Mock
    McpFollowMapper followMapper;
    @Mock
    TenantScopeSpi tenantScopeSpi;
    @Mock
    McpTransportFactory mcpTransportFactory;

    McpHubServiceImpl service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilityMcp.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilityMcpHub.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CapabilityMcpFollow.class);
        service = new McpHubServiceImpl(mcpMapper, hubMapper, followMapper,
                tenantScopeSpi, new CapabilityMcpConverter(), mcpTransportFactory);
        AuthContext.setUsername("alice");
        AuthContext.setUserId(7L);
        TenantContext.set(2L);
        org.mockito.Mockito.lenient().when(tenantScopeSpi.ancestorTenantIds(2L)).thenReturn(List.of(2L, 1L));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantContext.clear();
    }

    private CapabilityMcp mcp(Long id, String name, String createdBy, String authConfig) {
        CapabilityMcp m = new CapabilityMcp();
        m.setId(id);
        m.setTenantId(2L);
        m.setName(name);
        m.setDescription("d");
        m.setServerUrl("https://mcp.example.com/rpc");
        m.setServerType("http");
        m.setAuthConfig(authConfig);
        m.setToolDefinitions("{}");
        m.setStatus("ENABLED");
        m.setCreatedBy(createdBy);
        return m;
    }

    private CapabilityMcpHub hub(Long id, int version) {
        CapabilityMcpHub h = new CapabilityMcpHub();
        h.setId(id);
        h.setTenantId(2L);
        h.setSourceMcpId(10L);
        h.setName("shared");
        h.setDescription("d");
        h.setServerUrl("https://mcp.example.com/rpc");
        h.setServerType("http");
        h.setAuthConfig("{\"Authorization\":\"Bearer secret\"}");
        h.setToolDefinitions("{}");
        h.setVersion(version);
        h.setStatus("ACTIVE");
        h.setInstallCount(0);
        h.setFollowCount(0);
        h.setCreatedBy("alice");
        h.setPublisherUserId(99L);
        return h;
    }

    private void givenEmptyEnrichment() {
        org.mockito.Mockito.lenient().when(followMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(mcpMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(mcpMapper.selectBatchIds(any())).thenReturn(List.of());
    }

    @Test
    void publish_firstTime_createsHubVersionOne() {
        given(mcpMapper.selectById(10L)).willReturn(mcp(10L, "shared", "alice", "{}"));
        given(hubMapper.selectOne(any())).willReturn(null);
        given(hubMapper.insert(any(CapabilityMcpHub.class))).willReturn(1);
        givenEmptyEnrichment();

        McpHubVO vo = service.publish(10L);

        ArgumentCaptor<CapabilityMcpHub> captor = ArgumentCaptor.forClass(CapabilityMcpHub.class);
        verify(hubMapper).insert(captor.capture());
        CapabilityMcpHub saved = captor.getValue();
        assertEquals(1, saved.getVersion());
        assertEquals(2L, saved.getTenantId());
        assertEquals(10L, saved.getSourceMcpId());
        assertEquals(7L, saved.getPublisherUserId());
        assertEquals(1, vo.getVersion());
    }

    @Test
    void publish_again_alwaysIncrementsVersion() {
        given(mcpMapper.selectById(10L)).willReturn(mcp(10L, "shared", "alice", "{}"));
        given(hubMapper.selectOne(any())).willReturn(hub(5L, 1));
        given(hubMapper.updateById(any(CapabilityMcpHub.class))).willReturn(1);
        givenEmptyEnrichment();

        service.publish(10L);

        ArgumentCaptor<CapabilityMcpHub> captor = ArgumentCaptor.forClass(CapabilityMcpHub.class);
        verify(hubMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getVersion());
        verify(hubMapper, never()).insert(any(CapabilityMcpHub.class));
    }

    @Test
    void publish_otherUsersMcp_forbidden() {
        given(mcpMapper.selectById(10L)).willReturn(mcp(10L, "shared", "bob", "{}"));

        BizException error = assertThrows(BizException.class, () -> service.publish(10L));

        assertEquals(com.buukle.agent.common.error.CommonErrorCode.FORBIDDEN.getCode(), error.getErrorCode());
        verify(hubMapper, never()).insert(any(CapabilityMcpHub.class));
    }

    @Test
    void install_copiesEnabledStateAndSharedAuth() {
        given(hubMapper.selectById(5L)).willReturn(hub(5L, 4));
        given(mcpMapper.selectCount(any(Wrapper.class))).willReturn(0L);
        given(mcpMapper.insert(any(CapabilityMcp.class))).willReturn(1);
        given(hubMapper.update(org.mockito.ArgumentMatchers.<CapabilityMcpHub>isNull(), any(Wrapper.class))).willReturn(1);

        McpVO vo = service.install(5L, true);

        ArgumentCaptor<CapabilityMcp> captor = ArgumentCaptor.forClass(CapabilityMcp.class);
        verify(mcpMapper).insert(captor.capture());
        CapabilityMcp copy = captor.getValue();
        assertEquals(5L, copy.getHubMcpId());
        assertEquals(4, copy.getOriginVersion());
        assertTrue(copy.getAutoUpdate());
        assertEquals("ENABLED", copy.getStatus());
        assertEquals("{\"Authorization\":\"Bearer secret\"}", copy.getAuthConfig());
        assertEquals("alice", copy.getCreatedBy());
        assertEquals("shared", vo.getName());
        assertEquals("{\"Authorization\":\"Bearer secret\"}", vo.getAuthConfig());
        // install counter
        verify(hubMapper).update(org.mockito.ArgumentMatchers.<CapabilityMcpHub>isNull(), any(Wrapper.class));
    }

    @Test
    void install_publisher_forbidden() {
        CapabilityMcpHub publisherHub = hub(5L, 4);
        publisherHub.setPublisherUserId(7L);
        given(hubMapper.selectById(5L)).willReturn(publisherHub);

        BizException error = assertThrows(BizException.class, () -> service.install(5L, false));

        assertEquals(com.buukle.agent.common.error.CommonErrorCode.FORBIDDEN.getCode(), error.getErrorCode());
        verify(mcpMapper, never()).insert(any(CapabilityMcp.class));
    }

    @Test
    void getHub_returnsSharedAuthConfig() {
        given(hubMapper.selectById(5L)).willReturn(hub(5L, 2));
        givenEmptyEnrichment();

        McpHubVO vo = service.getHub(5L);

        assertEquals("{\"Authorization\":\"Bearer secret\"}", vo.getAuthConfig());
        assertEquals("shared", vo.getName());
        assertEquals(2, vo.getVersion());
    }

    @Test
    void follow_insertsSubscriptionAndCounts() {
        given(hubMapper.selectById(5L)).willReturn(hub(5L, 1));
        given(followMapper.selectOne(any())).willReturn(null);
        given(followMapper.insert(any(CapabilityMcpFollow.class))).willReturn(1);
        given(hubMapper.update(org.mockito.ArgumentMatchers.<CapabilityMcpHub>isNull(), any(Wrapper.class))).willReturn(1);

        service.follow(5L);

        ArgumentCaptor<CapabilityMcpFollow> captor = ArgumentCaptor.forClass(CapabilityMcpFollow.class);
        verify(followMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(5L, captor.getValue().getHubMcpId());
        assertEquals(2L, captor.getValue().getTenantId());
        // Follow never creates a local MCP copy.
        verify(mcpMapper, never()).insert(any(CapabilityMcp.class));
    }

    @Test
    void pageHub_queriesCurrentTenantAndAncestors() {
        Page<CapabilityMcpHub> page = new Page<>(1, 10);
        page.setRecords(List.of(hub(5L, 1)));
        page.setTotal(1);
        given(hubMapper.selectPage(any(), any())).willReturn(page);
        givenEmptyEnrichment();

        IPage<McpHubVO> result = service.pageHubMcps(1, 10, null);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(hubMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("tenant_id IN"), "hub must be scoped to the tenant chain: " + sql);
        assertEquals(1, result.getRecords().size());
        assertEquals(1, result.getRecords().get(0).getVersion());
        assertFalse(Boolean.TRUE.equals(result.getRecords().get(0).getFollowed()));
    }
}
