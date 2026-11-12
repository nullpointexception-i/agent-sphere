package com.buukle.agent.superagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.buukle.agent.common.context.AuthContext;
import com.buukle.agent.common.context.TenantContext;
import com.buukle.agent.superagent.domain.AgentLocalRuntime;
import com.buukle.agent.superagent.dtvo.dto.CreateLocalRuntimeDTO;
import com.buukle.agent.superagent.dtvo.vo.IssuedLocalRuntimeTokenVO;
import com.buukle.agent.superagent.repository.AgentLocalRuntimeMapper;
import com.buukle.agent.superagent.repository.AgentLocalTaskMapper;
import com.buukle.agent.superagent.service.impl.LocalRuntimeServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Reconnecting the same device must reuse its runtime instead of failing on the unique key. */
@ExtendWith(MockitoExtension.class)
class LocalRuntimeCreateTest {

    private static final Long USER_ID = 20L;
    private static final String DEVICE_KEY = "device-key-1";

    @Mock
    AgentLocalRuntimeMapper runtimeMapper;
    @Mock
    AgentLocalTaskMapper taskMapper;

    private LocalRuntimeServiceImpl service;

    @BeforeAll
    static void initMpTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AgentLocalRuntime.class);
    }

    @BeforeEach
    void setUp() {
        service = new LocalRuntimeServiceImpl(runtimeMapper, taskMapper);
        AuthContext.setUserId(USER_ID);
        TenantContext.set(2L);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
        TenantContext.clear();
    }

    @Test
    void existingDeviceIsReusedWithFreshToken() {
        AgentLocalRuntime existing = existing(5L, USER_ID);
        given(runtimeMapper.selectOne(any())).willReturn(existing);

        IssuedLocalRuntimeTokenVO issued = service.create(dto());

        assertEquals(5L, issued.getId());
        assertNotNull(issued.getToken());
        assertEquals(existing.getTokenPrefix(), issued.getTokenPrefix());
        verify(runtimeMapper).updateById(existing);
        verify(runtimeMapper, never()).insert(any(AgentLocalRuntime.class));
    }

    @Test
    void sameDeviceUnderAnotherOwnerCreatesItsOwnRuntime() {
        given(runtimeMapper.selectOne(any())).willReturn(null);
        given(runtimeMapper.insert(any(AgentLocalRuntime.class))).willAnswer(invocation -> {
            AgentLocalRuntime runtime = invocation.getArgument(0);
            runtime.setId(10L);
            return 1;
        });

        IssuedLocalRuntimeTokenVO issued = service.create(dto());

        assertEquals(10L, issued.getId());
        verify(runtimeMapper).insert(any(AgentLocalRuntime.class));
    }

    @Test
    void newDeviceCreatesRuntime() {
        given(runtimeMapper.selectOne(any())).willReturn(null);
        given(runtimeMapper.insert(any(AgentLocalRuntime.class))).willAnswer(invocation -> {
            AgentLocalRuntime runtime = invocation.getArgument(0);
            runtime.setId(9L);
            return 1;
        });

        IssuedLocalRuntimeTokenVO issued = service.create(dto());

        assertEquals(9L, issued.getId());
        assertNotNull(issued.getToken());
    }

    private static CreateLocalRuntimeDTO dto() {
        CreateLocalRuntimeDTO dto = new CreateLocalRuntimeDTO();
        dto.setName("code-agent");
        dto.setRuntimeKind("CODEX");
        dto.setDeviceKey(DEVICE_KEY);
        return dto;
    }

    private static AgentLocalRuntime existing(Long id, Long ownerUserId) {
        AgentLocalRuntime runtime = new AgentLocalRuntime();
        runtime.setId(id);
        runtime.setOwnerUserId(ownerUserId);
        runtime.setName("code-agent");
        runtime.setRuntimeKind("CODEX");
        runtime.setDeviceKey(DEVICE_KEY);
        runtime.setTokenHash("old-hash");
        runtime.setTokenPrefix("old-toke");
        runtime.setStatus("ACTIVE");
        return runtime;
    }
}
