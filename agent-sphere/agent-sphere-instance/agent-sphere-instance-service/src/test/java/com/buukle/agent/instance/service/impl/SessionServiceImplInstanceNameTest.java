package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.context.TenantScope;
import com.buukle.agent.common.context.TenantScopeHolder;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.vo.SessionVO;
import com.buukle.agent.instance.repository.InstanceMapper;
import com.buukle.agent.instance.repository.SessionMapper;
import com.buukle.agent.instance.service.converter.SessionConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * The session list shows {@code #id（instance name）}. A tenant-owned instance (the tenant Group
 * Router) is referenced by sessions the user may see but is itself filtered out by the self-only
 * data-permission rule, which used to leave the tag with the id only.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceImplInstanceNameTest {

    private static final Long GROUP_ROUTER_INSTANCE_ID = 74L;
    private static final String GROUP_ROUTER_NAME = "Group Router";
    private static final Long TENANT_ID = 2L;
    private static final String USERNAME = "testx0002";

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
    }

    @AfterEach
    void tearDown() {
        TenantScopeHolder.clear();
    }

    @Test
    void fillsNameOfTenantOwnedInstanceWithoutLosingTenantScope() {
        AgentInstance instance = new AgentInstance();
        instance.setId(GROUP_ROUTER_INSTANCE_ID);
        instance.setName(GROUP_ROUTER_NAME);
        AtomicBoolean selfOnlyDuringLookup = new AtomicBoolean(true);
        given(instanceMapper.selectBatchIds(any())).willAnswer(invocation -> {
            TenantScope active = TenantScopeHolder.currentOrFallback();
            selfOnlyDuringLookup.set(active != null && active.selfOnly());
            return List.of(instance);
        });

        TenantScopeHolder.start(selfOnlyScope());
        SessionVO session = new SessionVO();
        session.setAgentInstanceId(GROUP_ROUTER_INSTANCE_ID);

        ReflectionTestUtils.invokeMethod(service, "fillInstanceNames", List.of(session));

        assertFalse(selfOnlyDuringLookup.get(),
                "id-to-name lookup must not be filtered by created_by");
        assertEquals(GROUP_ROUTER_NAME, session.getAgentInstanceName());
        assertTrue(TenantScopeHolder.get().selfOnly(),
                "the caller's self-only scope must be restored afterwards");
        assertEquals(Set.of(TENANT_ID), TenantScopeHolder.get().visibleTenantIds(),
                "the tenant boundary itself must stay enforced");
    }

    private static TenantScope selfOnlyScope() {
        return new TenantScope(9L, USERNAME, TENANT_ID, Set.of(TENANT_ID), true, Set.of(TENANT_ID));
    }
}
