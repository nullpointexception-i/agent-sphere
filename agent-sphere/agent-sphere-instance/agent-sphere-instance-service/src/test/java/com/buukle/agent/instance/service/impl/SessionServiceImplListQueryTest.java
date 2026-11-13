package com.buukle.agent.instance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.common.constant.PermissionCodes;
import com.buukle.agent.common.constant.SessionListScope;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.instance.domain.AgentSession;
import com.buukle.agent.instance.dtvo.enums.SessionType;
import com.buukle.agent.instance.repository.InstanceMapper;
import com.buukle.agent.instance.repository.SessionMapper;
import com.buukle.agent.instance.service.converter.SessionConverter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Locks the list-scope contract: tenant conversations own the project assistant baseline and
 * every user fork, while user conversations stay on the ordinary console types.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceImplListQueryTest {

    private static final Long TENANT = 2L;
    private static final String USERNAME = "alice";

    @Mock
    private InstanceMapper instanceMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private SessionConverter sessionConverter;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SessionServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "entityClass", AgentSession.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentSession.class);
        AuthContext.setUsername(USERNAME);
        AuthContext.setPermissions(Set.of(PermissionCodes.INSTANCE_TENANT));
        TenantScopeHolder.start(
                new TenantScope(1L, USERNAME, TENANT, Set.of(TENANT), false, Set.of(TENANT)));
        given(sessionMapper.selectList(any())).willReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantScopeHolder.clear();
    }

    @Test
    void tenantListIncludesGroupRouterAndProjectAssistant() {
        service.listSessions(0, 20, null, SessionListScope.TENANT, null);

        String sql = capturedSql();
        Collection<Object> params = capturedParams();

        assertTrue(sql.contains("session_type"));
        assertTrue(sql.contains("parent_session_id"));
        assertTrue(params.contains(SessionType.GROUP_ROUTER));
        assertTrue(params.contains(SessionType.PROJECT_ROUTER));
    }

    @Test
    void userListDoesNotIncludeProjectAssistant() {
        service.listSessions(0, 20, null, SessionListScope.USER, null);

        capturedSql();
        Collection<Object> params = capturedParams();

        assertTrue(flattenedParams(params).contains(SessionType.CONSOLE));
        assertFalse(flattenedParams(params).contains(SessionType.PROJECT_ROUTER));
    }

    private String capturedSql() {
        return capturedWrapper().getSqlSegment();
    }

    private Collection<Object> capturedParams() {
        return ((AbstractWrapper<?, ?, ?>) capturedWrapper()).getParamNameValuePairs().values();
    }

    private static Collection<Object> flattenedParams(Collection<Object> params) {
        return params.stream()
                .flatMap(value -> value instanceof Collection<?> values
                        ? values.stream() : Stream.of(value))
                .toList();
    }

    private Wrapper<AgentSession> capturedWrapper() {
        ArgumentCaptor<Wrapper<AgentSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).selectList(captor.capture());
        return captor.getValue();
    }
}
