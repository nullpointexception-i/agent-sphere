package com.buukle.agent.instance.service.impl;

import com.buukle.agent.common.context.SessionSurface;
import com.buukle.agent.common.context.SessionSurfaceHolder;
import com.buukle.agent.common.error.CommonErrorCode;
import com.buukle.agent.common.exception.BizException;
import com.buukle.agent.instance.domain.AgentInstance;
import com.buukle.agent.instance.dtvo.dto.CreateSessionDTO;
import com.buukle.agent.instance.exception.InstanceErrorCode;
import com.buukle.agent.instance.repository.InstanceMapper;
import com.buukle.agent.instance.service.converter.SessionConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

/**
 * Session-creation surface switch interception (V89).
 *
 * <p>The policy truth table itself lives in {@code SessionSurfacePolicyTest}; what is locked here is
 * the **wiring** — `createSession` must actually call it. A missing call would not break compilation;
 * it would only silently let one surface through.
 *
 * <p>"Instance has no model route configured" is used as the later mandatory check to determine that
 * "the surface switch already passed": if the surface were blocked, it would throw
 * SESSION_SURFACE_DISABLED; otherwise execution reaches INSTANCE_NO_MODEL_ROUTE.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceImplSurfaceTest {

    private static final Long INSTANCE_ID = 43L;

    @Mock
    private InstanceMapper instanceMapper;
    @Mock
    private SessionConverter sessionConverter;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SessionServiceImpl service;

    @BeforeEach
    void setUp() {
        // widget denied, console allowed; modelRouteId is deliberately left unset so that when the
        // surface is allowed execution falls through to the later check
        AgentInstance instance = new AgentInstance();
        instance.setId(INSTANCE_ID);
        instance.setAllowWidgetSession(false);
        instance.setAllowConsoleSession(true);
        given(instanceMapper.selectById(INSTANCE_ID)).willReturn(instance);
    }

    @AfterEach
    void tearDown() {
        SessionSurfaceHolder.clear();
    }

    private static CreateSessionDTO dto() {
        CreateSessionDTO dto = new CreateSessionDTO();
        dto.setAgentInstanceId(INSTANCE_ID);
        return dto;
    }

    private static String errorCodeOf(Throwable t) {
        return ((BizException) t).getErrorCode();
    }

    @Test
    void widget_isRejectedWhenSwitchOff() {
        SessionSurfaceHolder.set(SessionSurface.WIDGET);

        BizException ex = assertThrows(BizException.class, () -> service.createSession(dto()));
        assertEquals(CommonErrorCode.SESSION_SURFACE_DISABLED.getCode(), ex.getErrorCode());
    }

    @Test
    void console_passesSurfaceCheck() {
        SessionSurfaceHolder.set(SessionSurface.CONSOLE);

        // Surface allowed → execution falls through to the later model route check
        BizException ex = assertThrows(BizException.class, () -> service.createSession(dto()));
        assertEquals(InstanceErrorCode.INSTANCE_NO_MODEL_ROUTE.getCode(), errorCodeOf(ex));
    }

    @Test
    void noSurface_internalCall_isAllowed() {
        // Task path: no HTTP interceptor, holder is unset → INTERNAL → must be allowed
        SessionSurfaceHolder.clear();

        BizException ex = assertThrows(BizException.class, () -> service.createSession(dto()));
        assertEquals(InstanceErrorCode.INSTANCE_NO_MODEL_ROUTE.getCode(), errorCodeOf(ex),
                "服务端内部调用不得被实例开关拦截，否则任务自己建不了会话");
    }
}
