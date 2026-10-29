package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.buukle.agent.instance.repository.AgentToolApprovalGrantMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolApprovalGrantServiceTest {

    @Mock AgentToolApprovalGrantMapper mapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.buukle.agent.instance.domain.AgentToolApprovalGrant.class);
    }

    private ToolApprovalGrantServiceImpl service() {
        return new ToolApprovalGrantServiceImpl(mapper);
    }

    @Test
    void runGrantOnlyMatchesCurrentRun() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertTrue(service().isGranted(1L, 2L, "builtin:6", "RUN"));

        String sql = capturedSql();
        assertTrue(sql.contains("run_id"));
        assertTrue(sql.contains("scope"));
        assertFalse(sql.contains("session_id"),
                "RUN-scoped grant must not match by session id: " + sql);
    }

    @Test
    void sessionScopedToolAcceptsSessionOrCurrentRunGrant() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertTrue(service().isGranted(1L, 2L, "builtin:6", "SESSION"));

        String sql = capturedSql();
        assertTrue(sql.contains("session_id"));
        assertTrue(sql.contains("run_id"));
        assertTrue(sql.contains("OR") || sql.contains("or"));
    }

    @Test
    void onceScopeNeverUsesPersistentGrant() {
        assertFalse(service().isGranted(1L, 2L, "builtin:6", "ONCE"));
        verify(mapper, never()).selectCount(any());
    }

    @Test
    void deniedGrantOnlyMatchesCurrentRun() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertTrue(service().isDenied(1L, 2L, "builtin:6"));

        String sql = capturedSql();
        assertTrue(sql.contains("run_id"));
        assertTrue(sql.contains("scope"));
        assertFalse(sql.contains("session_id"),
                "DENIED must not leak across runs by session id: " + sql);
    }

    @Test
    void deniedGrantWithoutRunIdDoesNotQuery() {
        assertFalse(service().isDenied(1L, null, "builtin:6"));
        verify(mapper, never()).selectCount(any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String capturedSql() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectCount(captor.capture());
        return captor.getValue().getSqlSegment();
    }
}
