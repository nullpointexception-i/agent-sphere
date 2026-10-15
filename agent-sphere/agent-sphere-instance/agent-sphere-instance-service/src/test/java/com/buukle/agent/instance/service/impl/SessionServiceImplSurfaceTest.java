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
 * 建会话的来源开关拦截（V89）。
 *
 * <p>策略真值表本身在 {@code SessionSurfacePolicyTest}；这里锁的是**接线** ——
 * `createSession` 必须真的调用它。少一行调用不会有编译错误，只会在某一端悄悄放行。
 *
 * <p>用「实例未配模型路由」作为落在后面的必经校验来判定「来源开关已放行」：
 * 若来源被拦，抛的是 SESSION_SURFACE_DISABLED；否则才会走到 INSTANCE_NO_MODEL_ROUTE。
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
        // widget 禁止、主站放行；且刻意不配 modelRouteId，让「来源放行」时能落到后一个校验
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

        // 来源放行 → 落到后面的模型路由校验
        BizException ex = assertThrows(BizException.class, () -> service.createSession(dto()));
        assertEquals(InstanceErrorCode.INSTANCE_NO_MODEL_ROUTE.getCode(), errorCodeOf(ex));
    }

    @Test
    void noSurface_internalCall_isAllowed() {
        // 任务路径：不经过 HTTP 拦截器，holder 未设置 → INTERNAL → 必须放行
        SessionSurfaceHolder.clear();

        BizException ex = assertThrows(BizException.class, () -> service.createSession(dto()));
        assertEquals(InstanceErrorCode.INSTANCE_NO_MODEL_ROUTE.getCode(), errorCodeOf(ex),
                "服务端内部调用不得被实例开关拦截，否则任务自己建不了会话");
    }
}
